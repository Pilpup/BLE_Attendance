import uuid
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.security import get_current_user, require_student, require_teacher
from app.database import get_db
from app.models.attendance import AttendanceRecord, AttendanceSession, Course, User
from app.schemas.attendance import (
    AttendanceSessionResponse,
    CourseResponse,
    MeshAuditRunRequest,
    SessionCreateRequest,
    SessionFinalizeRequest,
    SubmitNeighborsRequest,
)
from app.services.graph_engine import audit_classroom_mesh

router = APIRouter(prefix="/attendance", tags=["Attendance"])

# In-memory store for live peer reports submitted by students during active sessions
live_session_reports: dict[uuid.UUID, dict[str, List[str]]] = {}


# Mock context: auto-creates a test teacher + course so Swagger testing works
# without JWT. Your teammate replaces `get_current_mock_teacher` with:
#   current_user: User = Depends(get_current_user)
async def _get_or_create_mock_context(db: AsyncSession):
    """Ensures a default teacher and course exist for development testing."""
    result = await db.execute(select(User).where(User.role == "TEACHER"))
    teacher = result.scalars().first()
    if not teacher:
        teacher = User(
            id=uuid.uuid4(),
            email="teacher@university.edu",
            hashed_password="mock_hashed_pw",
            full_name="Prof. Alan Turing",
            role="TEACHER",
        )
        db.add(teacher)
        await db.commit()
        await db.refresh(teacher)

    c_result = await db.execute(select(Course).where(Course.course_code == "CS401"))
    course = c_result.scalars().first()
    if not course:
        course = Course(
            id=uuid.uuid4(),
            course_code="CS401",
            course_name="Distributed Systems & BLE Mesh",
            teacher_id=teacher.id,
        )
        db.add(course)
        await db.commit()
        await db.refresh(course)

    return teacher, course


# Endpoints
@router.get("/courses", response_model=List[CourseResponse])
async def list_courses(db: AsyncSession = Depends(get_db)):
    """List available courses. Use the returned UUID as course_id when creating a session."""
    await _get_or_create_mock_context(db)
    result = await db.execute(select(Course))
    return result.scalars().all()


@router.post(
    "/sessions",
    response_model=AttendanceSessionResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_attendance_session(
    payload: SessionCreateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    if payload.course_id:
        course_res = await db.execute(select(Course).where(Course.id == payload.course_id))
        course = course_res.scalars().first()
        if not course:
            raise HTTPException(status_code=404, detail="Course not found")
        if course.teacher_id != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You are not authorized to start attendance for this course",
            )
        target_course_id = course.id
    else:
        _, default_course = await _get_or_create_mock_context(db)
        target_course_id = default_course.id

    session = AttendanceSession(
        id=uuid.uuid4(),
        course_id=target_course_id,
        teacher_id=current_user.id,
        session_token=payload.session_token,
        status="ACTIVE",
        total_enrolled=payload.total_enrolled,
        started_at=datetime.now(timezone.utc),
    )
    db.add(session)
    await db.commit()
    await db.refresh(session)
    return session


@router.post(
    "/sessions/{session_id}/finalize",
    response_model=AttendanceSessionResponse,
)
async def finalize_attendance_session(
    session_id: uuid.UUID,
    payload: SessionFinalizeRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """
    Client-side audit upload:
    Teacher's phone posts the full mesh audit results computed locally.
    Stores affinity scores (1.0 / 0.5 / 0.0), bridge nodes, and reported neighbors.
    """
    result = await db.execute(
        select(AttendanceSession)
        .options(selectinload(AttendanceSession.records))
        .where(AttendanceSession.id == session_id)
    )
    session_obj = result.scalars().first()

    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only finalize your own attendance sessions",
        )
    if session_obj.status == "COMPLETED":
        raise HTTPException(status_code=400, detail="Session has already been finalized")

    verified_count = 0
    spoofer_count = 0

    for item in payload.records:
        if item.status == "VERIFIED_PRESENT":
            verified_count += 1
        elif item.status == "SPOOFER_REJECTED":
            spoofer_count += 1

        record = AttendanceRecord(
            id=uuid.uuid4(),
            session_id=session_obj.id,
            student_id=item.student_id,
            ble_device_id=item.ble_device_id.upper(),
            status=item.status,
            verification_method=item.verification_method,
            affinity_score=item.affinity_score,
            bridge_device_id=item.bridge_device_id.upper() if item.bridge_device_id else None,
            reported_neighbors=item.reported_neighbors,
            remarks=item.remarks,
            recorded_at=datetime.now(timezone.utc),
        )
        db.add(record)

    session_obj.status = "COMPLETED"
    session_obj.ended_at = datetime.now(timezone.utc)
    session_obj.total_verified = verified_count
    session_obj.total_spoofers = spoofer_count

    await db.commit()
    await db.refresh(session_obj)
    return session_obj


@router.post(
    "/sessions/{session_id}/submit-neighbors",
    status_code=status.HTTP_200_OK,
)
async def submit_student_neighbors(
    session_id: uuid.UUID,
    payload: SubmitNeighborsRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_student),
):
    """
    Student uploads their Top-3 discovered desk neighbors via Wi-Fi/5G.
    """
    result = await db.execute(
        select(AttendanceSession).where(AttendanceSession.id == session_id)
    )
    session_obj = result.scalars().first()
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.status != "ACTIVE":
        raise HTTPException(status_code=400, detail="Attendance session is not active")

    student_key = current_user.roll_number or str(current_user.id)
    live_session_reports.setdefault(session_id, {})[student_key] = payload.neighbors

    return {
        "status": "success",
        "student": student_key,
        "recorded_neighbors": payload.neighbors,
    }


@router.post(
    "/sessions/{session_id}/audit-and-finalize",
    response_model=AttendanceSessionResponse,
)
async def audit_and_finalize_session(
    session_id: uuid.UUID,
    payload: MeshAuditRunRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    result = await db.execute(
        select(AttendanceSession)
        .options(selectinload(AttendanceSession.records))
        .where(AttendanceSession.id == session_id)
    )
    session_obj = result.scalars().first()

    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only finalize your own attendance sessions",
        )
    if session_obj.status == "COMPLETED":
        raise HTTPException(status_code=400, detail="Session has already been finalized")

    # merge cloud-submitted student reports with BLE teacher-collected reports
    combined_reports = {**live_session_reports.pop(session_id, {}), **payload.reports}

    # execute social-graph audit engine
    audit_results = audit_classroom_mesh(combined_reports)


    verified_count = 0
    spoofer_count = 0

    for item in audit_results:
        if item["status"] == "VERIFIED_PRESENT":
            verified_count += 1
        elif item["status"] == "SPOOFER_REJECTED":
            spoofer_count += 1

        record = AttendanceRecord(
            id=uuid.uuid4(),
            session_id=session_obj.id,
            ble_device_id=item["ble_device_id"].upper(),
            status=item["status"],
            verification_method=item["verification_method"],
            affinity_score=item["affinity_score"],
            bridge_device_id=item["bridge_device_id"].upper() if item["bridge_device_id"] else None,
            reported_neighbors=item["reported_neighbors"],
            remarks=item["remarks"],
            recorded_at=datetime.now(timezone.utc),
        )
        db.add(record)

    session_obj.status = "COMPLETED"
    session_obj.ended_at = datetime.now(timezone.utc)
    session_obj.total_verified = verified_count
    session_obj.total_spoofers = spoofer_count

    await db.commit()
    await db.refresh(session_obj)
    return session_obj


@router.get("/sessions/{session_id}", response_model=AttendanceSessionResponse)
async def get_session_details(
    session_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    result = await db.execute(
        select(AttendanceSession)
        .options(selectinload(AttendanceSession.records))
        .where(AttendanceSession.id == session_id)
    )
    session_obj = result.scalars().first()
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    return session_obj
