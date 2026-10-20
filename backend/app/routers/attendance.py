from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import delete, or_, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload


def _is_session_stale(started_at: Optional[datetime], minutes: int) -> bool:
    if not started_at:
        return False
    now = datetime.now(timezone.utc)
    if started_at.tzinfo is None:
        started_at = started_at.replace(tzinfo=timezone.utc)
    return (now - started_at) > timedelta(minutes=minutes)

from app.core.security import (
    get_current_user,
    get_optional_user,
    require_student,
    require_teacher,
)
from app.database import get_db
from app.models.attendance import AttendanceRecord, AttendanceSession, Course, SessionSubmission, User
from app.schemas.attendance import (
    AttendanceRecordResponse,
    AttendanceSessionResponse,
    CourseCreateRequest,
    CourseResponse,
    MeshAuditRunRequest,
    RecordOverrideRequest,
    SessionCreateRequest,
    SessionFinalizeRequest,
    SubmitNeighborsRequest,
)
from app.services.graph_engine import audit_classroom_mesh

router = APIRouter(prefix="/attendance", tags=["Attendance"])




async def _resolve_session(session_id: str, db: AsyncSession) -> Optional[AttendanceSession]:
    """Resolves an AttendanceSession by UUID or 4-char session token."""
    target_uuid = None
    try:
        target_uuid = uuid.UUID(session_id)
    except ValueError:
        pass

    if target_uuid:
        query = (
            select(AttendanceSession)
            .options(selectinload(AttendanceSession.records))
            .where(or_(AttendanceSession.id == target_uuid, AttendanceSession.session_token == session_id))
        )
    else:
        query = (
            select(AttendanceSession)
            .options(selectinload(AttendanceSession.records))
            .where(AttendanceSession.session_token == session_id)
        )
    result = await db.execute(query)
    return result.scalars().first()


# ─────────────────────────────────────────────────────────────────────────────
# Helper Context for dev testing if db is empty
# ─────────────────────────────────────────────────────────────────────────────
async def _get_or_create_mock_context(db: AsyncSession):
    """Ensures a default teacher and course exist for development testing."""
    result = await db.execute(select(User).limit(1))
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


# ─────────────────────────────────────────────────────────────────────────────
# Course Endpoints
# ─────────────────────────────────────────────────────────────────────────────
@router.get("/courses", response_model=List[CourseResponse])
async def list_courses(db: AsyncSession = Depends(get_db)):
    """List available courses. Use the returned UUID as course_id when creating a session."""
    await _get_or_create_mock_context(db)
    result = await db.execute(select(Course))
    return result.scalars().all()


@router.post("/courses", response_model=CourseResponse, status_code=status.HTTP_201_CREATED)
async def create_course(
    payload: CourseCreateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """Teachers can register new courses to take attendance for."""
    course = Course(
        id=uuid.uuid4(),
        course_code=payload.course_code.strip().upper(),
        course_name=payload.course_name.strip(),
        teacher_id=current_user.id,
    )
    db.add(course)
    await db.commit()
    await db.refresh(course)
    return course


@router.get("/courses/{course_id}/sessions", response_model=List[AttendanceSessionResponse])
async def list_course_sessions(
    course_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """Teacher lists all past attendance sessions for a specific course."""
    course_res = await db.execute(select(Course).where(Course.id == course_id))
    course = course_res.scalars().first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")
    if course.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You are not authorized to view sessions for this course",
        )

    result = await db.execute(
        select(AttendanceSession)
        .where(AttendanceSession.course_id == course_id)
        .order_by(AttendanceSession.started_at.desc())
    )
    return result.scalars().all()


# ─────────────────────────────────────────────────────────────────────────────
# Session Lifecycle & Guardrails
# ─────────────────────────────────────────────────────────────────────────────
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
    """
    Teacher initiates an attendance session before starting BLE conductor ticks.
    [Guardrail]: Rejects creation if an ACTIVE session is already running for this course.
    """
    if payload.course_id:
        course_res = await db.execute(select(Course).where(Course.id == payload.course_id))
        course = course_res.scalars().first()
        if not course:
            raise HTTPException(status_code=404, detail="Course not found")
        # Allow if it's the demo course CS401 or belongs to current teacher
        if course.teacher_id != current_user.id and course.course_code != "CS401":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You are not authorized to start attendance for this course",
            )
        target_course_id = course.id
    else:
        # Check if this teacher already has a course, otherwise fallback to default
        c_res = await db.execute(select(Course).where(Course.teacher_id == current_user.id))
        teacher_course = c_res.scalars().first()
        if teacher_course:
            target_course_id = teacher_course.id
        else:
            default_c = Course(
                id=uuid.uuid4(),
                course_code="CS101",
                course_name="General Lecture & BLE Attendance",
                teacher_id=current_user.id,
            )
            db.add(default_c)
            await db.commit()
            await db.refresh(default_c)
            target_course_id = default_c.id

    # Auto-supersede and expire any lingering ACTIVE sessions for this course
    active_check = await db.execute(
        select(AttendanceSession).where(
            AttendanceSession.course_id == target_course_id,
            AttendanceSession.status == "ACTIVE",
        )
    )
    existing_active = active_check.scalars().all()
    now_utc = datetime.now(timezone.utc)
    for stale in existing_active:
        is_stale = _is_session_stale(stale.started_at, minutes=2)
        stale.status = "EXPIRED" if is_stale else "CANCELLED"
        stale.ended_at = now_utc
    if existing_active:
        await db.commit()

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
    session.records = []
    return session


@router.post("/sessions/{session_id}/cancel", response_model=AttendanceSessionResponse)
async def cancel_attendance_session(
    session_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """
    [Guardrail]: Teacher cancels an accidental or abandoned session.
    Prevents lingering active session locks.
    """
    result = await db.execute(
        select(AttendanceSession).where(AttendanceSession.id == session_id)
    )
    session_obj = result.scalars().first()
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only cancel your own attendance sessions",
        )
    if session_obj.status == "COMPLETED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot cancel an attendance session that has already been finalized/completed",
        )
    if session_obj.status == "CANCELLED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Attendance session is already cancelled",
        )

    session_obj.status = "CANCELLED"
    session_obj.ended_at = datetime.now(timezone.utc)
    await db.execute(
        delete(SessionSubmission).where(SessionSubmission.session_id == session_obj.id)
    )
    await db.commit()
    await db.refresh(session_obj)
    return session_obj


@router.post(
    "/sessions/{session_id}/finalize",
    response_model=AttendanceSessionResponse,
)
async def finalize_attendance_session(
    session_id: str,
    payload: SessionFinalizeRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """
    Client-side audit upload: Teacher phone posts pre-calculated graph results.
    [Guardrail]: Enforces status != COMPLETED and status != CANCELLED.
    """
    session_obj = await _resolve_session(session_id, db)

    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only finalize your own attendance sessions",
        )
    if session_obj.status == "CANCELLED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot finalize a cancelled session",
        )
    if session_obj.status == "COMPLETED":
        return session_obj

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
    session_id: str,
    payload: SubmitNeighborsRequest,
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    """
    Student uploads their Top-3 discovered desk neighbors via Wi-Fi/5G.
    Accepts either UUID or 4-char session token (e.g. '89F2').
    Supports both authenticated students and unauthenticated/guest students.
    """
    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.status != "ACTIVE":
        raise HTTPException(status_code=400, detail="Attendance session is not active")

    # TTL check: Auto-expire active sessions older than 5 minutes
    if _is_session_stale(session_obj.started_at, minutes=5):
        session_obj.status = "EXPIRED"
        session_obj.ended_at = datetime.now(timezone.utc)
        await db.commit()
        raise HTTPException(status_code=400, detail="Attendance session has expired")

    target_session_id = session_obj.id
    if current_user:
        # Normalize student key to match 4-character BLE short ID
        if current_user.roll_number and len(current_user.roll_number) >= 4:
            student_key = current_user.roll_number[-4:].upper()
        elif current_user.roll_number:
            student_key = current_user.roll_number.upper()
        else:
            student_key = str(current_user.id).replace("-", "")[:4].upper()
        user_id = current_user.id
    else:
        raw_id = payload.student_id or "STUD"
        student_key = raw_id[-4:].upper() if len(raw_id) >= 4 else raw_id.upper()
        user_id = None

    normalized_neighbors = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in payload.neighbors]

    # Persist student check-in in database (multi-worker safe, crash resilient)
    existing_stmt = select(SessionSubmission).where(
        SessionSubmission.session_id == target_session_id,
        SessionSubmission.device_key == student_key,
    )
    existing_res = await db.execute(existing_stmt)
    existing_sub = existing_res.scalar_one_or_none()

    if existing_sub:
        existing_sub.reported_neighbors = normalized_neighbors
        if user_id:
            existing_sub.student_id = user_id
    else:
        new_sub = SessionSubmission(
            session_id=target_session_id,
            student_id=user_id,
            device_key=student_key,
            reported_neighbors=normalized_neighbors,
        )
        db.add(new_sub)

    if payload.relayed_reports:
        for offline_sender, offline_nbrs in payload.relayed_reports.items():
            sender_key = offline_sender[-4:].upper() if len(offline_sender) >= 4 else offline_sender.upper()
            norm_nbrs = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in offline_nbrs]

            relay_stmt = select(SessionSubmission).where(
                SessionSubmission.session_id == target_session_id,
                SessionSubmission.device_key == sender_key,
            )
            relay_res = await db.execute(relay_stmt)
            existing_relay = relay_res.scalar_one_or_none()

            if not existing_relay:
                relay_sub = SessionSubmission(
                    session_id=target_session_id,
                    student_id=None,
                    device_key=sender_key,
                    reported_neighbors=norm_nbrs,
                )
                db.add(relay_sub)

    await db.commit()

    return {
        "status": "success",
        "student": student_key,
        "recorded_neighbors": normalized_neighbors,
    }


@router.post(
    "/sessions/{session_id}/audit-and-finalize",
    response_model=AttendanceSessionResponse,
)
async def audit_and_finalize_session(
    session_id: str,
    payload: MeshAuditRunRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """
    Server-side audit execution: Runs audit_classroom_mesh() on the backend.
    [Guardrail]: Enforces status != COMPLETED and status != CANCELLED.
    """
    session_obj = await _resolve_session(session_id, db)

    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only finalize your own attendance sessions",
        )
    if session_obj.status == "CANCELLED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot finalize a cancelled session",
        )
    if session_obj.status == "COMPLETED":
        return session_obj

    # Fetch all cloud-submitted student and relay reports from the database
    sub_stmt = select(SessionSubmission).where(SessionSubmission.session_id == session_obj.id)
    sub_res = await db.execute(sub_stmt)
    submissions = sub_res.scalars().all()

    cloud_reports: dict[str, List[str]] = {s.device_key: s.reported_neighbors for s in submissions}
    user_map: dict[str, uuid.UUID] = {s.device_key: s.student_id for s in submissions if s.student_id}

    # Clean up ephemeral session submissions now that attendance records are finalized
    await db.execute(delete(SessionSubmission).where(SessionSubmission.session_id == session_obj.id))

    combined_reports = {**cloud_reports, **payload.reports}

    # execute social-graph audit engine
    audit_results = audit_classroom_mesh(combined_reports)

    verified_count = 0
    spoofer_count = 0

    for item in audit_results:
        device_id = item["ble_device_id"].upper()
        if item["status"] == "VERIFIED_PRESENT":
            verified_count += 1
        elif item["status"] == "SPOOFER_REJECTED":
            spoofer_count += 1

        resolved_student_id = user_map.get(device_id)
        if not resolved_student_id:
            user_stmt = select(User).where(User.roll_number.ilike(f"%{device_id}")).limit(1)
            user_res = await db.execute(user_stmt)
            matched_user = user_res.scalar_one_or_none()
            if matched_user:
                resolved_student_id = matched_user.id

        record = AttendanceRecord(
            id=uuid.uuid4(),
            session_id=session_obj.id,
            student_id=resolved_student_id,
            ble_device_id=device_id,
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
    session_id: str,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    return session_obj


# ─────────────────────────────────────────────────────────────────────────────
# Teacher Record Override Endpoint
# ─────────────────────────────────────────────────────────────────────────────
@router.patch("/records/{record_id}/override", response_model=AttendanceRecordResponse)
async def override_attendance_record(
    record_id: uuid.UUID,
    payload: RecordOverrideRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    """
    Teacher manual override for an edge-case student (e.g. isolated or dead battery).
    """
    result = await db.execute(
        select(AttendanceRecord, AttendanceSession)
        .join(AttendanceSession, AttendanceRecord.session_id == AttendanceSession.id)
        .where(AttendanceRecord.id == record_id)
    )
    row = result.first()
    if not row:
        raise HTTPException(status_code=404, detail="Attendance record not found")

    record_obj, session_obj = row
    if session_obj.teacher_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You are not authorized to override records for this session",
        )

    record_obj.status = payload.status
    record_obj.verification_method = "MANUAL_OVERRIDE"
    if payload.remarks:
        record_obj.remarks = payload.remarks

    await db.commit()
    await db.refresh(record_obj)
    return record_obj
