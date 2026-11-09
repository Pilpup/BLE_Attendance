from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
import os
import re
import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Union

logger = logging.getLogger("uvicorn.error")

from fastapi import APIRouter, Body, Depends, HTTPException, Request, status
from sqlalchemy import delete, func, or_, select
from sqlalchemy.exc import IntegrityError
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
    is_test_environment,
    require_teacher,
)
from app.database import get_db
from app.services.rate_limiter import submit_rate_limiter
from app.models.attendance import (
    AttendanceRecord,
    AttendanceSession,
    Course,
    CourseUnit,
    SessionSubmission,
    UnitTopic,
    User,
)
from app.schemas.attendance import (
    AttendanceRecordResponse,
    AttendanceSessionResponse,
    CourseCreateRequest,
    CourseResponse,
    CourseUnitCreate,
    CourseUnitResponse,
    MeshAuditRunRequest,
    RecordOverrideRequest,
    SessionCreateRequest,
    SessionFinalizeRequest,
    SubmitNeighborsRequest,
    UnitTopicCreate,
    UnitTopicResponse,
    FaceLabCheckRequest,
    FaceLabCheckResponse,
    FaceLabCompareRequest,
    FaceLabCompareResponse,
)
from app.services.face_engine import face_registry
from app.services.graph_engine import audit_classroom_mesh
from app.services.submission_buffer import submission_buffer

router = APIRouter(prefix="/attendance", tags=["Attendance"])


# Resolve an AttendanceSession by UUID or session token
# Canonicalizes program strings so "B.Tech", "B-Tech" and "B Tech" compare equal
def _program_key(value: Optional[str]) -> str:
    return re.sub(r"[^a-z0-9]", "", (value or "").lower())


# Returns None when `user` may submit to `session_obj`, else the human-readable
# rejection reason. Pure in-memory comparison on objects both callers already
# hold — the gate costs no extra queries at check-in time. Non-student roles
# (TEACHER/STAFF/DEVELOPER) bypass class binding by design.
def student_eligibility_detail(session_obj: AttendanceSession, user: User) -> Optional[str]:
    if (user.role or "").upper() != "STUDENT":
        return None
    if user.semester is None or not (user.program or "").strip():
        return "Attendance profile incomplete — update your program and semester before checking in"
    if session_obj.eligibility_semester is None or not (session_obj.eligibility_program or "").strip():
        return None  # unknown class identity (legacy rows) — nothing to bind to
    semester_ok = user.semester == session_obj.eligibility_semester
    program_ok = _program_key(user.program) == _program_key(session_obj.eligibility_program)
    if semester_ok and program_ok:
        return None
    return (
        f"This session is for {session_obj.eligibility_program} Semester {session_obj.eligibility_semester}"
        f" — your profile says Semester {user.semester}"
    )


async def _resolve_session(session_id: str, db: AsyncSession) -> Optional[AttendanceSession]:
    load_opts = selectinload(AttendanceSession.records)

    # Allow debug sessions only during test execution
    if is_test_environment() and session_id.upper() in ("AUTO-101", "DEMO", "DEBUG-1"):
        debug_token = session_id.upper()
        debug_res = await db.execute(
            select(AttendanceSession).options(load_opts).where(AttendanceSession.session_token == debug_token)
        )
        debug_sess = debug_res.scalars().first()
        if not debug_sess:
            teacher, course = await _get_or_create_mock_context(db)
            debug_sess = AttendanceSession(
                id=uuid.uuid4(),
                course_id=course.id,
                teacher_id=teacher.id,
                session_token=debug_token,
                session_salt="DEMO1",
                status="ACTIVE",
                started_at=datetime.now(timezone.utc),
            )
            db.add(debug_sess)
            await db.commit()
            await db.refresh(debug_sess)
        else:
            debug_sess.status = "ACTIVE"
            debug_sess.session_salt = "DEMO1"
            debug_sess.started_at = datetime.now(timezone.utc)
            await db.commit()
            await db.refresh(debug_sess)
        return debug_sess

    # Query session by UUID or session token, preferring newest
    target_uuid = None
    try:
        target_uuid = uuid.UUID(session_id)
    except ValueError:
        pass

    if target_uuid:
        query = (
            select(AttendanceSession)
            .options(load_opts)
            .where(or_(AttendanceSession.id == target_uuid, AttendanceSession.session_token == session_id))
            .order_by(AttendanceSession.started_at.desc())
        )
    else:
        query = (
            select(AttendanceSession)
            .options(load_opts)
            .where(AttendanceSession.session_token == session_id)
            .order_by(AttendanceSession.started_at.desc())
        )
    result = await db.execute(query)
    return result.scalars().first()


# R4 (review §3.1): concurrent session creation pointing at the same course
# code raced the unique `courses.course_code` constraint and 500'd the
# legitimate second creator. Extracted as a helper so the adopt-the-winner
# race behavior is also unit-testable.
async def _resolve_or_create_course(
    db: AsyncSession,
    code_to_check: str,
    course_name: str,
    teacher_id: Optional[uuid.UUID],
    new_course_id: Optional[uuid.UUID] = None,
) -> Course:
    c_res = await db.execute(select(Course).where(func.upper(Course.course_code) == code_to_check))
    existing_c = c_res.scalars().first()
    if existing_c:
        return existing_c
    new_c = Course(
        id=new_course_id or uuid.uuid4(),
        course_code=code_to_check,
        course_name=course_name,
        teacher_id=teacher_id,
    )
    db.add(new_c)
    try:
        await db.commit()
    except IntegrityError:
        # The losing creator: someone committed the same code between our
        # SELECT and COMMIT. Adopt their row instead of failing.
        await db.rollback()
        re_res = await db.execute(select(Course).where(func.upper(Course.course_code) == code_to_check))
        winner = re_res.scalars().first()
        if winner is None:
            raise
        return winner
    await db.refresh(new_c)
    return new_c


# Ensure default teacher and course exist for development testing
async def _get_or_create_mock_context(db: AsyncSession):
    # R3 (review §3.1): this fabrication (a teacher with an unhashable stub
    # password plus a demo course) must never enter a real deployment DB —
    # production logins via that account 500 on `Invalid salt`. Unconditionally
    # gated on the test environment so every caller is safe.
    if not is_test_environment():
        return None, None
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

    c_result = await db.execute(select(Course).order_by(Course.course_code.asc()).limit(1))
    course = c_result.scalars().first()
    if not course:
        course = Course(
            id=uuid.uuid4(),
            course_code="CST-003",
            course_name="Data Structures and Algorithms",
            teacher_id=teacher.id,
        )
        db.add(course)
        await db.commit()
        await db.refresh(course)

    return teacher, course


# Populate course code and name onto an attendance session
async def _populate_course_metadata(session_obj: AttendanceSession, db: AsyncSession) -> None:
    if not session_obj.course_id:
        return
    c_res = await db.execute(select(Course).where(Course.id == session_obj.course_id))
    course = c_res.scalars().first()
    if course:
        session_obj.course_code = course.course_code
        session_obj.course_name = course.course_name


# Record or update a proxy violation record for a device
# Record proxy violation handling concurrent write races
async def _record_proxy_violation(
    db: AsyncSession,
    session_id: uuid.UUID,
    device_id: str,
    status: str,
    verification_method: str,
    remarks: str,
    student_id: Optional[uuid.UUID] = None,
    reported_neighbors: Optional[List[str]] = None,
) -> Optional[AttendanceRecord]:
    stmt = select(AttendanceRecord).where(
        AttendanceRecord.session_id == session_id,
        AttendanceRecord.ble_device_id == device_id,
    )
    res = await db.execute(stmt)
    rec = res.scalars().first()
    if rec:
        rec.status = status
        rec.verification_method = verification_method
        rec.remarks = remarks
        if student_id and not rec.student_id:
            rec.student_id = student_id
        if reported_neighbors is not None:
            rec.reported_neighbors = reported_neighbors
        return rec

    rec = AttendanceRecord(
        id=uuid.uuid4(),
        session_id=session_id,
        student_id=student_id,
        ble_device_id=device_id,
        status=status,
        verification_method=verification_method,
        affinity_score=0.00,
        reported_neighbors=reported_neighbors or [],
        remarks=remarks,
        recorded_at=datetime.now(timezone.utc),
    )
    try:
        async with db.begin_nested():
            db.add(rec)
            await db.flush()
    except IntegrityError as exc:
        logger.warning(f"Concurrent proxy insert collision for {device_id}: {exc}")
        res = await db.execute(stmt)
        rec = res.scalars().first()
        if rec:
            rec.status = status
            rec.verification_method = verification_method
            rec.remarks = remarks
    return rec


# Commit proxy violations safely with error logging
async def _commit_proxy_violation(db: AsyncSession) -> None:
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        logger.warning(f"Concurrent proxy violation commit collision: {exc}")


# Serializes concurrent finalize calls (a teacher tap racing the auto-finalize timer):
# the second caller blocks on the session row until the first commits, then re-reads the
# committed status so a duplicate finalize exits idempotently instead of double-inserting.
# SQLite (test environment) ignores FOR UPDATE; PostgreSQL enforces it.
async def _lock_session_for_finalize(db: AsyncSession, session_obj: AttendanceSession) -> AttendanceSession:
    lock_res = await db.execute(
        select(AttendanceSession.id).where(AttendanceSession.id == session_obj.id).with_for_update()
    )
    lock_res.scalars().first()
    fresh_status = (
        await db.execute(select(AttendanceSession.status).where(AttendanceSession.id == session_obj.id))
    ).scalar_one()
    session_obj.status = fresh_status
    return session_obj


# Course Endpoints
# List available courses
@router.get("/courses", response_model=List[CourseResponse])
async def list_courses(
    semester: Optional[int] = None,
    department: Optional[str] = None,
    program: Optional[str] = None,
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    await _get_or_create_mock_context(db)

    # Return teacher's courses if authenticated (including available syllabus courses)
    if current_user and current_user.role == "TEACHER":
        query = select(Course).where(
            or_(
                Course.teacher_id == current_user.id,
                Course.teacher_id.is_(None),
            )
        )
        if semester is not None:
            query = query.where(Course.semester == semester)
        if department is not None:
            query = query.where(Course.department.ilike(f"%{department.strip()}%"))
        if program is not None:
            query = query.where(Course.program.ilike(f"%{program.strip()}%"))
        result = await db.execute(query.order_by(Course.semester.asc(), Course.course_code.asc()))
        return list(result.scalars().all())

    # Return all courses when unauthenticated or student
    query = select(Course)
    if semester is not None:
        query = query.where(Course.semester == semester)
    if department is not None:
        query = query.where(Course.department.ilike(f"%{department.strip()}%"))
    if program is not None:
        query = query.where(Course.program.ilike(f"%{program.strip()}%"))
    result = await db.execute(query.order_by(Course.semester.asc(), Course.course_code.asc()))
    return result.scalars().all()


# Register a new course
@router.post("/courses", response_model=CourseResponse, status_code=status.HTTP_201_CREATED)
async def create_course(
    payload: CourseCreateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    course = Course(
        id=uuid.uuid4(),
        course_code=payload.course_code.strip().upper(),
        course_name=payload.course_name.strip(),
        teacher_id=current_user.id,
        program=payload.program,
        department=payload.department,
        semester=payload.semester,
        category=payload.category,
        course_type=payload.course_type,
        credits=payload.credits,
        periods_l=payload.periods_l,
        periods_t=payload.periods_t,
        periods_p=payload.periods_p,
    )
    db.add(course)
    await db.commit()
    await db.refresh(course)
    return course


# Get full syllabus for a specific course
@router.get("/courses/{course_id}/syllabus", response_model=List[CourseUnitResponse])
async def get_course_syllabus(
    course_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    course_res = await db.execute(select(Course).where(Course.id == course_id))
    course = course_res.scalars().first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")

    res = await db.execute(
        select(CourseUnit)
        .where(CourseUnit.course_id == course_id)
        .order_by(CourseUnit.unit_number.asc())
    )
    return res.scalars().all()


# Add a syllabus unit to a course
@router.post(
    "/courses/{course_id}/units",
    response_model=CourseUnitResponse,
    status_code=status.HTTP_201_CREATED,
)
async def add_course_unit(
    course_id: uuid.UUID,
    payload: CourseUnitCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    course_res = await db.execute(select(Course).where(Course.id == course_id))
    course = course_res.scalars().first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")
    if course.teacher_id != current_user.id and current_user.role != "ADMIN":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You are not authorized to modify this course syllabus",
        )

    existing = await db.execute(
        select(CourseUnit).where(
            CourseUnit.course_id == course_id,
            CourseUnit.unit_number == payload.unit_number,
        )
    )
    if existing.scalars().first():
        raise HTTPException(
            status_code=400,
            detail=f"Unit {payload.unit_number} already exists for this course",
        )

    unit = CourseUnit(
        id=uuid.uuid4(),
        course_id=course_id,
        unit_number=payload.unit_number,
        unit_title=payload.unit_title.strip(),
        teaching_hours=payload.teaching_hours,
    )
    db.add(unit)
    await db.flush()

    if payload.topics:
        for t in payload.topics:
            topic = UnitTopic(
                id=uuid.uuid4(),
                unit_id=unit.id,
                topic_order=t.topic_order,
                topic_name=t.topic_name.strip(),
            )
            db.add(topic)

    await db.commit()
    await db.refresh(unit)
    return unit


# Add a topic to an existing syllabus unit
@router.post(
    "/courses/{course_id}/units/{unit_id}/topics",
    response_model=UnitTopicResponse,
    status_code=status.HTTP_201_CREATED,
)
async def add_unit_topic(
    course_id: uuid.UUID,
    unit_id: uuid.UUID,
    payload: UnitTopicCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    unit_res = await db.execute(
        select(CourseUnit).where(CourseUnit.id == unit_id, CourseUnit.course_id == course_id)
    )
    unit = unit_res.scalars().first()
    if not unit:
        raise HTTPException(status_code=404, detail="Unit not found for this course")

    course_res = await db.execute(select(Course).where(Course.id == course_id))
    course = course_res.scalars().first()
    if course.teacher_id != current_user.id and current_user.role != "ADMIN":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You are not authorized to modify this course syllabus",
        )

    topic = UnitTopic(
        id=uuid.uuid4(),
        unit_id=unit_id,
        topic_order=payload.topic_order,
        topic_name=payload.topic_name.strip(),
    )
    db.add(topic)
    try:
        await db.commit()
        await db.refresh(topic)
    except IntegrityError:
        await db.rollback()
        raise HTTPException(
            status_code=400,
            detail=f"Topic with order {payload.topic_order} already exists in this unit",
        )
    return topic


# List all past attendance sessions for a specific course
@router.get("/courses/{course_id}/sessions", response_model=List[AttendanceSessionResponse])
async def list_course_sessions(
    course_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
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


# List past attendance sessions for authenticated instructor
@router.get("/sessions/history", response_model=List[AttendanceSessionResponse])
async def list_teacher_sessions_history(
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    if not current_user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")

    user_role = (current_user.role or "").upper()
    if user_role not in ("TEACHER", "DEVELOPER", "STAFF", "FACULTY", "ADMIN"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only instructors can view session history")

    query = (
        select(AttendanceSession)
        .options(selectinload(AttendanceSession.records))
        .where(AttendanceSession.teacher_id == current_user.id)
        .order_by(AttendanceSession.started_at.desc())
    )

    result = await db.execute(query)
    sessions = list(result.scalars().all())

    # Populate course details on each session
    course_ids = [s.course_id for s in sessions if s.course_id]
    if course_ids:
        c_res = await db.execute(select(Course).where(Course.id.in_(course_ids)))
        course_map = {c.id: c for c in c_res.scalars().all()}
        for s in sessions:
            c = course_map.get(s.course_id)
            if c:
                s.course_code = c.course_code
                s.course_name = c.course_name

    # Populate student details on records
    student_ids = [r.student_id for s in sessions if s.records for r in s.records if r.student_id]
    if student_ids:
        u_res = await db.execute(select(User).where(User.id.in_(student_ids)))
        user_map = {u.id: u for u in u_res.scalars().all()}
        for s in sessions:
            if s.records:
                for r in s.records:
                    if r.student_id in user_map:
                        u = user_map[r.student_id]
                        r.roll_number = u.roll_number
                        r.student_name = u.full_name

    return sessions


# Create an attendance session
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
    target_course_id = None
    target_uuid = None
    course_obj: Optional[Course] = None

    if payload.course_id:
        try:
            target_uuid = uuid.UUID(payload.course_id)
        except (ValueError, AttributeError):
            target_uuid = None

    if target_uuid:
        course_res = await db.execute(select(Course).where(Course.id == target_uuid))
        course = course_res.scalars().first()
        if course:
            course_obj = course
            target_course_id = course.id

    if not target_course_id:
        # Resolve or auto-create course
        raw_code = payload.course_code or (payload.course_id if not target_uuid else None) or "CS101"
        code_to_check = raw_code.strip()[:30].upper()
        course_obj = await _resolve_or_create_course(
            db,
            code_to_check,
            course_name=(payload.course_name or "General Lecture & Attendance")[:200],
            teacher_id=current_user.id,
            new_course_id=target_uuid,
        )
        target_course_id = course_obj.id

    # Eligibility snapshot: the teacher's declared class wins (their UI selection
    # is the class actually being taught), the course row is the fallback.
    eligibility_program = (payload.course_program or (course_obj.program if course_obj else None) or "").strip() or None
    eligibility_semester = payload.course_semester if payload.course_semester is not None else (course_obj.semester if course_obj else None)
    eligibility_department = course_obj.department if course_obj else None

    # Expire previous active sessions for this course created by current teacher
    active_check = await db.execute(
        select(AttendanceSession).where(
            AttendanceSession.course_id == target_course_id,
            AttendanceSession.teacher_id == current_user.id,
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

    # M2: after retiring this teacher's own prior ACTIVE sessions, any remaining
    # session using the same token belongs to ANOTHER teacher/class. Rejecting
    # here prevents asymmetric keys from cross-routing student submissions
    # (resolutions are newest-wins, so a colliding broadcast is harmful).
    token_conflict_res = await db.execute(
        select(AttendanceSession).where(
            AttendanceSession.session_token == payload.session_token,
            AttendanceSession.status == "ACTIVE",
        )
    )
    if token_conflict_res.scalars().first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Session ID '{payload.session_token}' is already in use by an active session — pick a new session ID",
        )

    # Save new active attendance session (with eligibility snapshot for the
    # student class-binding gate)
    session = AttendanceSession(
        id=uuid.uuid4(),
        course_id=target_course_id,
        teacher_id=current_user.id,
        session_token=payload.session_token,
        session_salt=payload.session_salt,
        eligibility_program=eligibility_program,
        eligibility_department=eligibility_department,
        eligibility_semester=eligibility_semester,
        status="ACTIVE",
        total_enrolled=payload.total_enrolled,
        started_at=datetime.now(timezone.utc),
    )
    db.add(session)
    await db.commit()
    await db.refresh(session)
    session.records = []

    await _populate_course_metadata(session, db)

    logger.info(f"[Session Created] ID: {session.id}, Token: {session.session_token}, Salt: {session.session_salt}, Course: {session.course_code}")
    return session


# Cancel an attendance session
@router.post("/sessions/{session_id}/cancel", response_model=AttendanceSessionResponse)
async def cancel_attendance_session(
    session_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
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

    # Serialize against concurrent finalize calls: a cancel racing an in-flight finalize must
    # observe the committed result instead of flipping a just-finalized session to CANCELLED
    session_obj = await _lock_session_for_finalize(db, session_obj)
    if session_obj.status == "COMPLETED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot cancel an attendance session that has already been finalized/completed",
        )
    if session_obj.status == "CANCELLED":
        # Idempotent: re-cancel of an already-cancelled session is a no-op success
        await db.refresh(session_obj)
        return session_obj

    # Cancel session and remove ephemeral submissions
    session_obj.status = "CANCELLED"
    session_obj.ended_at = datetime.now(timezone.utc)
    await db.execute(
        delete(SessionSubmission).where(SessionSubmission.session_id == session_obj.id)
    )
    await db.commit()
    await db.refresh(session_obj)

    # Discard volatile in-RAM state so a cancelled session leaves nothing behind
    await submission_buffer.clear_session(str(session_obj.id))
    face_registry.clear_session(str(session_obj.id))

    await _populate_course_metadata(session_obj, db)
    logger.info(f"[Session Cancelled] Session: {session_obj.id}")
    return session_obj


# Finalize attendance session with client-provided records
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
    session_obj = await _resolve_session(session_id, db)

    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    user_role = (current_user.role or "").upper()
    if session_obj.teacher_id != current_user.id and user_role not in ("DEVELOPER", "ADMIN"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only finalize your own attendance sessions",
        )
    if session_obj.status == "CANCELLED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot finalize a cancelled session",
        )
    # Serialize overlapping finalizes: block on the session row, then re-read committed status
    session_obj = await _lock_session_for_finalize(db, session_obj)

    if session_obj.status == "COMPLETED":
        # Idempotent exit: attach the finalized counters and records so the caller gets the
        # completed session rather than the stale pre-lock snapshot
        await db.refresh(session_obj)
        final_recs = await db.execute(
            select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id)
        )
        session_obj.records = list(final_recs.scalars().all())
        await _populate_course_metadata(session_obj, db)
        return session_obj

    # Load prior records to update existing entries and avoid duplicates
    prior_res = await db.execute(
        select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id)
    )
    prior_records = prior_res.scalars().all()
    prior_device_map = {r.ble_device_id.upper(): r for r in prior_records}
    prior_student_map = {r.student_id: r for r in prior_records if r.student_id}
    seen_devices = set(prior_device_map.keys())
    seen_students = set(prior_student_map.keys())

    verified_count = 0
    # M5: frozen security verdicts (written out-of-band by the defense layers —
    # salt gate, face dedup, no-face) are preserved in place, but they must still
    # surface in total_spoofers even though the finalize loop skips updating them.
    spoofer_count = sum(
        1 for r in prior_records
        if r.status in (
            "DUPLICATE_FACE_PROXY",
            "CO_LOCATED_SAME_DESK_PROXY",
            "NO_FACE_DETECTED",
            "SPOOFER_REJECTED",
        )
    )

    records_to_create = []
    for item in payload.records:
        device_id = item.ble_device_id.upper()
        prior_rec = prior_device_map.get(device_id) or (prior_student_map.get(item.student_id) if item.student_id else None)
        if prior_rec:
            if prior_rec.status in (
                "DUPLICATE_FACE_PROXY",
                "CO_LOCATED_SAME_DESK_PROXY",
                "NO_FACE_DETECTED",
                "SPOOFER_REJECTED",
            ):
                continue
            if item.status == "VERIFIED_PRESENT":
                verified_count += 1
            elif item.status == "SPOOFER_REJECTED":
                spoofer_count += 1
            prior_rec.status = item.status
            prior_rec.verification_method = item.verification_method
            prior_rec.affinity_score = item.affinity_score
            prior_rec.remarks = item.remarks
            prior_rec.reported_neighbors = item.reported_neighbors
            continue

        if device_id in seen_devices or (item.student_id and item.student_id in seen_students):
            continue
        seen_devices.add(device_id)
        if item.student_id:
            seen_students.add(item.student_id)

        if item.status == "VERIFIED_PRESENT":
            verified_count += 1
        elif item.status == "SPOOFER_REJECTED":
            spoofer_count += 1

        records_to_create.append(
            AttendanceRecord(
                id=uuid.uuid4(),
                session_id=session_obj.id,
                student_id=item.student_id,
                ble_device_id=device_id,
                status=item.status,
                verification_method=item.verification_method,
                affinity_score=item.affinity_score,
                bridge_device_id=item.bridge_device_id.upper() if item.bridge_device_id else None,
                reported_neighbors=item.reported_neighbors,
                remarks=item.remarks,
                recorded_at=datetime.now(timezone.utc),
            )
        )

    # Drain cloud-staged submissions to avoid dropping internet check-ins
    staged = await submission_buffer.pop_submissions(str(session_obj.id))

    # M3-lite: materialize RECIPROCATED ultrasonic desk claims into the staged
    # drain — both parties must claim each other; one-sided claims are ignored.
    ultrasonic_pairs: dict[str, str] = {}
    for claim_key, claim_sub in staged.items():
        claim_peer = claim_sub.claimed_ultrasonic
        if not claim_peer:
            continue
        peer_sub = staged.get(claim_peer)
        if peer_sub and peer_sub.claimed_ultrasonic == claim_key:
            ultrasonic_pairs[claim_key] = claim_peer
            ultrasonic_pairs[claim_peer] = claim_key
        else:
            logger.warning(f"[Layer 4] Unreciprocated ultrasonic claim ignored: {claim_key} -> {claim_peer}")

    # Leniency seal: a staged student earns VERIFIED_PRESENT only with at least
    # one RECIPROCATED BLE neighbor (mirrors the audit engine's direct-mutual
    # rule). A self-reported neighbor list alone can never verify anyone.
    drain_reports: dict[str, set[str]] = {}
    for rec_item in payload.records:
        drain_reports.setdefault(rec_item.ble_device_id.upper(), set()).update(
            n.strip().upper() for n in (rec_item.reported_neighbors or [])
        )
    for staged_key_extra, staged_sub_extra in staged.items():
        drain_reports.setdefault(staged_key_extra.upper(), set()).update(
            n.strip().upper() for n in staged_sub_extra.reported_neighbors
        )

    for staged_key, staged_sub in staged.items():
        dev_upper = staged_key.upper()
        if dev_upper in seen_devices or (staged_sub.student_id and staged_sub.student_id in seen_students):
            continue
        seen_devices.add(dev_upper)
        if staged_sub.student_id:
            seen_students.add(staged_sub.student_id)

        pair_peer = ultrasonic_pairs.get(dev_upper)
        if pair_peer:
            # Mutual ultrasonic confirmation overrides the benign staged verdict;
            # NO blanket 1.00 VERIFIED_PRESENT for desk co-located partners.
            spoofer_count += 1
            records_to_create.append(
                AttendanceRecord(
                    id=uuid.uuid4(),
                    session_id=session_obj.id,
                    student_id=staged_sub.student_id,
                    ble_device_id=dev_upper,
                    status="CO_LOCATED_SAME_DESK_PROXY",
                    verification_method="ULTRASONIC_ACOUSTIC_MATCH",
                    affinity_score=0.00,
                    reported_neighbors=staged_sub.reported_neighbors,
                    remarks=f"Ultrasonic proximity rejected: Co-located on same desk (< 20 cm) with {pair_peer}",
                    recorded_at=datetime.now(timezone.utc),
                )
            )
            continue

        confirmed_peers = [
            peer.strip().upper()
            for peer in staged_sub.reported_neighbors
            if dev_upper in drain_reports.get(peer.strip().upper(), set())
        ]
        is_ver = bool(confirmed_peers)
        if is_ver:
            verified_count += 1
            rec_status = "VERIFIED_PRESENT"
            verif_method = "BLE_MESH"
            rec_affinity = 1.00
            rec_remarks = f"Cloud staged check-in confirmed via mutual desk neighbor [{', '.join(confirmed_peers)}]"
        elif staged_sub.reported_neighbors:
            rec_status = "ISOLATED_FLAGGED"
            verif_method = "NONE"
            rec_affinity = 0.50
            rec_remarks = f"Cloud staged check-in without confirmed desk neighbor (Reported: [{', '.join(staged_sub.reported_neighbors)}]) - manual review recommended"
        else:
            rec_status = "ISOLATED_FLAGGED"
            verif_method = "NONE"
            rec_affinity = 0.00
            rec_remarks = "Zero desk neighbors detected (isolated seat)"
        records_to_create.append(
            AttendanceRecord(
                id=uuid.uuid4(),
                session_id=session_obj.id,
                student_id=staged_sub.student_id,
                ble_device_id=dev_upper,
                status=rec_status,
                verification_method=verif_method,
                affinity_score=rec_affinity,
                reported_neighbors=staged_sub.reported_neighbors,
                remarks=rec_remarks,
                recorded_at=datetime.now(timezone.utc),
            )
        )

    # Batch inserts all records and cleans staged submissions
    if records_to_create:
        db.add_all(records_to_create)

    session_obj.status = "COMPLETED"
    session_obj.ended_at = datetime.now(timezone.utc)
    session_obj.total_verified = verified_count
    session_obj.total_spoofers = spoofer_count

    await submission_buffer.clear_session(str(session_obj.id))
    await db.commit()
    await db.refresh(session_obj)
    # H2: clear face biometrics only AFTER the commit (final grace-window dedup
    # must keep its registry until the records are durable) — see finalize path.
    face_registry.clear_session(str(session_obj.id))

    await _populate_course_metadata(session_obj, db)

    logger.info(f"[Audit Finalized] Session: {session_obj.id}, Verified: {session_obj.total_verified}, Spoofers: {session_obj.total_spoofers}, Total Records: {len(payload.records)}")
    return session_obj

@router.post(
    "/sessions/{session_id}/submit-neighbors",
    status_code=status.HTTP_200_OK,
)
async def submit_student_neighbors(
    session_id: str,
    request: Request,
    payload: Union[SubmitNeighborsRequest, bytes] = Body(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    # Extracts binary face bytes and headers if request is raw image payload
    raw_face_bytes = None
    if isinstance(payload, bytes):
        raw_face_bytes = payload
        session_salt = request.headers.get("x-session-salt")
        student_id = request.headers.get("x-student-id")
        neighbors_raw = request.headers.get("x-neighbors", "")
        neighbors = [n.strip() for n in neighbors_raw.split(",") if n.strip()] if neighbors_raw else []
        close_raw = request.headers.get("x-close-neighbors", "")
        close_neighbors = [n.strip() for n in close_raw.split(",") if n.strip()] if close_raw else []
        co_located_peer_id = request.headers.get("x-co-located-peer-id")
        ultrasonic_proximity_str = request.headers.get("x-ultrasonic-proximity-detected")
        ultrasonic_proximity = ultrasonic_proximity_str.lower() in ("true", "1") if ultrasonic_proximity_str is not None else False
        challenge_report_hdr = request.headers.get("x-challenge-report")
        challenge_report = None
        if challenge_report_hdr:
            try:
                challenge_report = {
                    k.strip(): int(v)
                    for k, v in (p.split("=", 1) for p in challenge_report_hdr.split(",") if "=" in p)
                }
            except ValueError:
                challenge_report = {"raw": challenge_report_hdr}

        payload = SubmitNeighborsRequest(
            student_id=student_id,
            neighbors=neighbors,
            close_neighbors=close_neighbors,
            session_salt=session_salt,
            face_image_base64=None,
            ultrasonic_proximity_detected=ultrasonic_proximity,
            co_located_peer_id=co_located_peer_id,
            challenge_report=challenge_report,
        )

    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.status == "CANCELLED":
        raise HTTPException(status_code=400, detail="Attendance session has been cancelled")
    if session_obj.status not in ("ACTIVE", "COMPLETED"):
        raise HTTPException(status_code=400, detail="Attendance session is not active")
    if session_obj.status == "COMPLETED":
        # Allow 90-second grace period for in-flight student game completions and biometric face deduplication
        if session_obj.ended_at:
            # Mirror _is_session_stale's naive/aware tolerance: SQLite (tests)
            # surfaces naive datetimes; Postgres (prod) surfaces tz-aware ones.
            grace_ended_at = session_obj.ended_at
            if grace_ended_at.tzinfo is None:
                grace_ended_at = grace_ended_at.replace(tzinfo=timezone.utc)
            if (datetime.now(timezone.utc) - grace_ended_at).total_seconds() > 90:
                raise HTTPException(status_code=400, detail="Attendance session has expired")

    # TTL check: Auto-expire active sessions older than 5 minutes
    if _is_session_stale(session_obj.started_at, minutes=5):
        session_obj.status = "EXPIRED"
        session_obj.ended_at = datetime.now(timezone.utc)
        await db.commit()
        raise HTTPException(status_code=400, detail="Attendance session has expired")

    # Class-eligibility gate: a student may only check in to the class the
    # session is FOR. Runs on objects already loaded (session + JWT user) and
    # before the rate limiter, so wrong-class attempts cost zero quota and no
    # face/neighbor work. Mirrors the salt gate's rejection + flag pattern so
    # the attempt stays visible on the teacher's audit dashboard.
    eligibility_detail = student_eligibility_detail(session_obj, current_user)
    if eligibility_detail:
        raw_key = current_user.roll_number or str(current_user.id).replace("-", "")[:4]
        claimed_key = raw_key[-4:].upper() if len(raw_key) >= 4 else raw_key.upper()
        logger.warning(
            f"[Eligibility Gate] Session {session_obj.id}: submit rejected for key {claimed_key} "
            f"(authenticated) — {eligibility_detail}"
        )
        await _record_proxy_violation(
            db=db,
            session_id=session_obj.id,
            device_id=claimed_key,
            status="SPOOFER_REJECTED",
            verification_method="ELIGIBILITY_REJECTED",
            remarks=eligibility_detail,
            student_id=current_user.id,
            reported_neighbors=[
                n[-4:].upper() if len(n) >= 4 else n.upper() for n in payload.neighbors
            ],
        )
        await _commit_proxy_violation(db)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=eligibility_detail,
        )

    # Per-(session, account) submit budget — deliberately NOT per-IP, the whole
    # classroom shares one Wi-Fi gateway address. Throttles salt brute-forcing
    # and resubmit floods for a single account without 429-ing its classmates.
    if not await submit_rate_limiter.check(str(session_obj.id), str(current_user.id)):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many check-in submissions for this session; resubmit shortly",
        )

    # Layer 2 anti-remote-submission guard (ProjectSummary §1.1):
    # the student's phone must possess the salt distributed ONLY via the
    # teacher's BLE beacon — proof of radio reception at the classroom.
    if payload.session_salt != session_obj.session_salt:
        # Flag the attempted remote submission so it surfaces on the teacher's
        # audit dashboard instead of vanishing as a silent rejection.
        # JWT identity only (auth is mandatory on submit): rejected keys always
        # belong to a real enrolled account — client-chosen keys are impossible,
        # so spoofer flags can no longer be framed onto honest classmates.
        raw_key = current_user.roll_number or str(current_user.id).replace("-", "")[:4]
        claimed_key = raw_key[-4:].upper() if len(raw_key) >= 4 else raw_key.upper()
        logger.warning(
            f"[Salt Gate] Session {session_obj.id}: submit rejected for key {claimed_key} "
            f"(authenticated) — beacon salt mismatch"
        )

        # Record spoofer flag for beacon salt mismatch
        await _record_proxy_violation(
            db=db,
            session_id=session_obj.id,
            device_id=claimed_key,
            status="SPOOFER_REJECTED",
            verification_method="SALT_REJECTED",
            remarks="Remote check-in blocked: in-room beacon salt mismatch",
            student_id=current_user.id,
            reported_neighbors=[
                n[-4:].upper() if len(n) >= 4 else n.upper() for n in payload.neighbors
            ],
        )
        await _commit_proxy_violation(db)

        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Nice try lil bro",
        )

    target_session_id = session_obj.id
    user_id = current_user.id

    # Bind submission identity to the JWT account: client-chosen keys can no
    # longer select someone else's graph node or merge accounts. Roll number
    # when set; UUID prefix keeps record keys stable for users without one.
    raw_key = current_user.roll_number or str(current_user.id).replace("-", "")[:4]
    student_key = raw_key[-4:].upper() if len(raw_key) >= 4 else raw_key.upper()

    normalized_neighbors = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in payload.neighbors]
    normalized_close = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in (payload.close_neighbors or [])]

    # Observability-only: payload fingerprint, logged BEFORE the face/ultrasonic
    # gates so even rejected attempts are tracked. If the same fingerprint ever
    # appears twice near-simultaneously (seen once from 10.26.34.133), that is a
    # network-stack retry duplicating one check-in — not two real students.
    fingerprint_material = "|".join([
        student_key,
        hashlib.sha256((payload.session_salt or "").encode()).hexdigest()[:8],
        ",".join(sorted(normalized_neighbors)),
        f"ultra:{1 if payload.ultrasonic_proximity_detected else 0}:{(payload.co_located_peer_id or '-').upper()}",
        f"facebytes:{len(payload.face_image_base64) if payload.face_image_base64 else 0}",
    ])
    logger.info(
        f"[Check-In Fingerprint] Session: {target_session_id} Key: {student_key} "
        f"IP: {request.client.host if request.client else 'unknown'} "
        f"FP: {hashlib.sha256(fingerprint_material.encode()).hexdigest()[:12]}"
    )

    # Reflex-challenge counters are client-attested telemetry only. Logged for
    # instructor triage (surface anomalies after collusion findings) and
    # deliberately NEVER used as a security gate — the client could fake them.
    if payload.challenge_report:
        logger.info(
            f"[Challenge Telemetry] Session: {target_session_id} Key: {student_key} "
            f"Report: {payload.challenge_report}"
        )

    # Face deduplication check (Layer 3: Camera Face Biometric Deduplication)
    face_check_result = None
    if payload.face_image_base64 or raw_face_bytes:
        raw_roll = current_user.roll_number or student_key

        # Compile proximity cluster peers: close peers (RSSI >= -65), reported desk neighbors, and co-located peer
        cluster_peers = set(normalized_close)
        cluster_peers.update(normalized_neighbors)
        if payload.co_located_peer_id:
            co_key = payload.co_located_peer_id[-4:].upper() if len(payload.co_located_peer_id) >= 4 else payload.co_located_peer_id.upper()
            cluster_peers.add(co_key)

        # Run facial biometric matching in background thread pool
        face_check_result = await asyncio.to_thread(
            face_registry.register_and_check,
            session_id=str(session_obj.id),
            student_key=student_key,
            roll_number=raw_roll,
            face_base64=payload.face_image_base64,
            face_bytes=raw_face_bytes,
            cluster_peers=cluster_peers,
        )

        if not face_check_result.has_human_face:
            logger.warning(f"[Layer 3 Face Biometric] No face detected for {student_key}: {face_check_result.remarks}")
            await _record_proxy_violation(
                db=db,
                session_id=session_obj.id,
                device_id=student_key,
                status="NO_FACE_DETECTED",
                verification_method="FACE_BIOMETRIC_FAILED",
                remarks=face_check_result.remarks,
                student_id=current_user.id if current_user else None,
                reported_neighbors=normalized_neighbors,
            )
            await _commit_proxy_violation(db)
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Biometric proxy rejected: {face_check_result.remarks}",
            )

        if face_check_result.is_duplicate:
            # Flag earlier matched student if known
            if face_check_result.matched_student_key:
                await _record_proxy_violation(
                    db=db,
                    session_id=session_obj.id,
                    device_id=face_check_result.matched_student_key,
                    status="DUPLICATE_FACE_PROXY",
                    verification_method="FACE_BIOMETRIC_MATCH",
                    remarks=f"Proxy match with {raw_roll} ({student_key})",
                )

            # Flag current submitting student
            await _record_proxy_violation(
                db=db,
                session_id=session_obj.id,
                device_id=student_key,
                status="DUPLICATE_FACE_PROXY",
                verification_method="FACE_BIOMETRIC_MATCH",
                remarks=face_check_result.remarks,
                student_id=current_user.id if current_user else None,
                reported_neighbors=normalized_neighbors,
            )
            await _commit_proxy_violation(db)
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Biometric proxy rejected: {face_check_result.remarks}",
            )

    # M3-lite: an ultrasonic desk handshake (Layer 4) is a CLAIM, not a verdict.
    # The claimant stages normally; only a RECIPROCATED pair (both students claim
    # each other) is materialized into CO_LOCATED_SAME_DESK_PROXY rows at finalize.
    # One-sided claims are logged and ignored, so a prankster can never vandalize
    # a classmate's record with a fake "we sat together" claim.
    ultrasonic_claim: Optional[str] = None
    if payload.ultrasonic_proximity_detected:
        raw_claim = (payload.co_located_peer_id or "").strip()
        # Keys are already last-4-normalized everywhere on this route — mirror it
        # so the claim can pair against the partner's staged device key.
        normalized_claim = raw_claim[-4:].upper() if len(raw_claim) >= 4 else raw_claim.upper()
        if not normalized_claim or normalized_claim == "UNKNOWN" or normalized_claim == student_key:
            logger.warning(f"[Layer 4] Ultrasonic claim from {student_key} dropped: invalid co-located peer '{raw_claim}'")
        else:
            ultrasonic_claim = normalized_claim
            logger.info(f"[Layer 4] Ultrasonic claim received from {student_key}: co-located with {normalized_claim} (deferred to finalize for reciprocal confirmation)")

    # Stage student check-in submission into in-memory buffer
    await submission_buffer.stage_submission(
        session_id=str(target_session_id),
        device_key=student_key,
        student_id=user_id,
        reported_neighbors=normalized_neighbors,
        claimed_ultrasonic=ultrasonic_claim,
    )

    # Re-read session status after staging: if finalize committed while this submission was in
    # flight (the finalize already popped the buffer), reroute through the grace path below so
    # the straggler gets an attendance record instead of being silently orphaned.
    fresh_status_res = await db.execute(
        select(AttendanceSession.status).where(AttendanceSession.id == session_obj.id)
    )
    session_obj.status = fresh_status_res.scalar_one_or_none() or session_obj.status

    if session_obj.status == "CANCELLED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Attendance session has been cancelled",
        )

    if ultrasonic_claim and session_obj.status == "COMPLETED":
        # M3-lite: finalize already popped the staging buffer, so reciprocity for
        # this late claim can no longer be evaluated. Log only — never vandalize.
        logger.warning(f"[Layer 4] Post-finalize ultrasonic claim ignored (mutuality unevaluable): {student_key} -> {ultrasonic_claim}")

    # For in-flight submissions arriving during the grace period of an already COMPLETED session,
    # immediately evaluate and record their attendance so they don't get orphaned without an AttendanceRecord.
    if session_obj.status == "COMPLETED":
        rec_stmt = select(AttendanceRecord).where(
            AttendanceRecord.session_id == target_session_id,
            AttendanceRecord.ble_device_id == student_key,
        )
        rec_res = await db.execute(rec_stmt)
        existing_record = rec_res.scalars().first()

        if not existing_record:
            verified_neighbors = []
            if normalized_neighbors:
                v_stmt = select(AttendanceRecord).where(
                    AttendanceRecord.session_id == target_session_id,
                    AttendanceRecord.ble_device_id.in_(normalized_neighbors),
                    AttendanceRecord.status == "VERIFIED_PRESENT",
                )
                v_res = await db.execute(v_stmt)
                verified_neighbors = [r.ble_device_id for r in v_res.scalars().all()]

            if verified_neighbors:
                rec_status = "VERIFIED_PRESENT"
                verif_method = "IN_FLIGHT_GRACE_PEER"
                affinity = 1.00
                bridge = None
                remarks = f"In-flight check-in verified via mutual desk neighbor [{', '.join(verified_neighbors)}]"
                session_obj.total_verified = (session_obj.total_verified or 0) + 1
            elif normalized_neighbors:
                rec_status = "ISOLATED_FLAGGED"
                verif_method = "IN_FLIGHT_GRACE"
                affinity = 0.50
                bridge = None
                remarks = f"In-flight check-in during grace period without verified neighbor (Reported: [{', '.join(normalized_neighbors)}]) - manual review recommended"
            else:
                rec_status = "ISOLATED_FLAGGED"
                verif_method = "NONE"
                affinity = 0.00
                bridge = None
                remarks = "Zero desk neighbors detected (isolated seat)"

            resolved_student_id = user_id
            if not resolved_student_id:
                user_stmt = select(User).where(User.roll_number.ilike(f"%{student_key}")).limit(1)
                user_res = await db.execute(user_stmt)
                matched_user = user_res.scalar_one_or_none()
                if matched_user:
                    resolved_student_id = matched_user.id

            new_record = AttendanceRecord(
                id=uuid.uuid4(),
                session_id=session_obj.id,
                student_id=resolved_student_id,
                ble_device_id=student_key,
                status=rec_status,
                verification_method=verif_method,
                affinity_score=affinity,
                bridge_device_id=bridge,
                reported_neighbors=normalized_neighbors,
                remarks=remarks,
                recorded_at=datetime.now(timezone.utc),
            )
            db.add(new_record)
            try:
                await db.commit()
            except IntegrityError:
                # R1 race guard (review §3.1): a valid retrying student — a
                # double grace claim or a replayed app keep-alive after their
                # check-in — re-hits one of the session's unique keys
                # (device/student/submission). Their attendance already
                # exists; treat the claim as accepted, never 500 them.
                await db.rollback()
                logger.info(
                    f"[In-Flight Grace Audit] Duplicate grace claim for late arrival {student_key} "
                    f"in session {session_obj.id} — attendance record already present."
                )
            logger.info(f"[In-Flight Grace Audit] Generated {rec_status} record for late arrival {student_key} in session {session_obj.id}")

    logger.info(f"[Check-In] Student: {student_key} submitted {len(normalized_neighbors)} neighbors: {normalized_neighbors} for session: {target_session_id}")
    return {
        "status": "success",
        "student": student_key,
        "recorded_neighbors": normalized_neighbors,
        "face_verified": True if payload.face_image_base64 else None,
        "face_diagnostic": face_check_result.remarks if face_check_result else None,
    }


# Execute social-graph audit and finalize session
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
    session_obj = await _resolve_session(session_id, db)

    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    user_role = (current_user.role or "").upper()
    if session_obj.teacher_id != current_user.id and user_role not in ("DEVELOPER", "ADMIN"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only finalize your own attendance sessions",
        )
    if session_obj.status == "CANCELLED":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot finalize a cancelled session",
        )
    # Serialize overlapping finalizes: block on the session row, then re-read committed status
    session_obj = await _lock_session_for_finalize(db, session_obj)

    if session_obj.status == "COMPLETED":
        # Idempotent exit: attach the finalized counters and records so the caller gets the
        # completed session rather than the stale pre-lock snapshot
        await db.refresh(session_obj)
        final_recs = await db.execute(
            select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id)
        )
        session_obj.records = list(final_recs.scalars().all())
        await _populate_course_metadata(session_obj, db)
        return session_obj

    # Fetch all cloud-submitted student and relay reports from memory buffer and fallback database
    staged = await submission_buffer.pop_submissions(str(session_obj.id))

    sub_stmt = select(SessionSubmission).where(SessionSubmission.session_id == session_obj.id)
    sub_res = await db.execute(sub_stmt)
    submissions = sub_res.scalars().all()
    if submissions:
        await db.execute(delete(SessionSubmission).where(SessionSubmission.session_id == session_obj.id))

    cloud_reports: dict[str, List[str]] = {s.device_key: s.reported_neighbors for s in submissions}
    user_map: dict[str, uuid.UUID] = {s.device_key: s.student_id for s in submissions if s.student_id}

    for dev_key, staged_sub in staged.items():
        cloud_reports[dev_key] = staged_sub.reported_neighbors
        if staged_sub.student_id:
            user_map[dev_key] = staged_sub.student_id

    combined_reports = {**cloud_reports, **payload.reports}

    # Execute social-graph audit engine
    audit_results = audit_classroom_mesh(combined_reports)

    # M3-lite: materialize RECIPROCATED ultrasonic desk claims. Both parties must
    # claim each other from their staged submissions; the matched pair's benign
    # audit verdict rows are transformed in place into the proxy violation row —
    # preserving the one-row-per-student shape. Unreciprocated claims are ignored.
    ultrasonic_pairs: dict[str, str] = {}
    for claim_key, claim_sub in staged.items():
        claim_peer = claim_sub.claimed_ultrasonic
        if not claim_peer:
            continue
        peer_sub = staged.get(claim_peer)
        if peer_sub and peer_sub.claimed_ultrasonic == claim_key:
            ultrasonic_pairs[claim_key] = claim_peer
            ultrasonic_pairs[claim_peer] = claim_key
        else:
            logger.warning(f"[Layer 4] Unreciprocated ultrasonic claim ignored: {claim_key} -> {claim_peer}")

    if ultrasonic_pairs:
        for item in audit_results:
            pair_peer = ultrasonic_pairs.get(item["ble_device_id"].upper())
            if pair_peer:
                item["status"] = "CO_LOCATED_SAME_DESK_PROXY"
                item["verification_method"] = "ULTRASONIC_ACOUSTIC_MATCH"
                item["remarks"] = f"Ultrasonic proximity rejected: Co-located on same desk (< 20 cm) with {pair_peer}"

    # Log the verdict so manual classroom tests are self-explaining in server logs
    verdict_counts: dict[str, int] = {}
    for item in audit_results:
        verdict_counts[item["status"]] = verdict_counts.get(item["status"], 0) + 1
    logger.info(f"[Audit Verdict] Session: {session_obj.id} -> {verdict_counts}")
    for item in audit_results:
        if item["status"] != "VERIFIED_PRESENT":
            logger.info(
                f"[Audit Detail] Session: {session_obj.id} node {item['ble_device_id']} "
                f"-> {item['status']} :: {item['remarks']}"
            )

    # Batch load all prior attendance records for this session
    prior_records_res = await db.execute(
        select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id)
    )
    prior_records = prior_records_res.scalars().all()
    prior_device_map = {r.ble_device_id.upper(): r for r in prior_records}
    prior_student_map = {r.student_id: r for r in prior_records if r.student_id}
    seen_devices = set(prior_device_map.keys())
    seen_students = set(prior_student_map.keys())

    # Batch resolve unmatched student roll numbers with ambiguity protection
    unresolved_keys = [
        item["ble_device_id"].upper()
        for item in audit_results
        if item["ble_device_id"].upper() not in user_map
    ]
    if unresolved_keys:
        conditions = [User.roll_number.ilike(f"%{k}") for k in unresolved_keys]
        matched_res = await db.execute(select(User).where(or_(*conditions)))
        key_matches: dict[str, list[User]] = {}
        for u in matched_res.scalars().all():
            if u.roll_number:
                u_roll = u.roll_number.upper()
                for k in unresolved_keys:
                    if u_roll.endswith(k):
                        key_matches.setdefault(k, []).append(u)

        # Bind student ID only if single candidate matches key
        for k, matches in key_matches.items():
            if len(matches) == 1:
                user_map[k] = matches[0].id
            else:
                logger.warning(f"[Identity Collision] Key {k} matched multiple users: {[m.roll_number for m in matches]}")

    verified_count = 0
    spoofer_count = 0
    records_to_create = []

    for item in audit_results:
        device_id = item["ble_device_id"].upper()
        resolved_student_id = user_map.get(device_id)

        prior_rec = prior_device_map.get(device_id) or (prior_student_map.get(resolved_student_id) if resolved_student_id else None)
        if prior_rec:
            # Skip modifying existing security proxy violations
            if prior_rec.status in (
                "DUPLICATE_FACE_PROXY",
                "CO_LOCATED_SAME_DESK_PROXY",
                "DIVIDED_ATTENTION_PROXY",
                "COGNITIVE_CHALLENGE_FAILED",
                "NO_FACE_DETECTED",
                "SPOOFER_REJECTED",
            ):
                continue
            # Update existing record in place without duplicate insert
            if item["status"] == "VERIFIED_PRESENT":
                verified_count += 1
            elif item["status"] == "SPOOFER_REJECTED":
                spoofer_count += 1
            prior_rec.status = item["status"]
            prior_rec.verification_method = item["verification_method"]
            prior_rec.affinity_score = item["affinity_score"]
            prior_rec.remarks = item["remarks"]
            prior_rec.reported_neighbors = item["reported_neighbors"]
            if resolved_student_id and not prior_rec.student_id:
                prior_rec.student_id = resolved_student_id
            continue

        if device_id in seen_devices or (resolved_student_id and resolved_student_id in seen_students):
            continue
        seen_devices.add(device_id)
        if resolved_student_id:
            seen_students.add(resolved_student_id)

        if item["status"] == "VERIFIED_PRESENT":
            verified_count += 1
        elif item["status"] == "SPOOFER_REJECTED":
            spoofer_count += 1

        records_to_create.append(
            AttendanceRecord(
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
        )

    # Batch insert all attendance records and flush to ensure visibility
    if records_to_create:
        db.add_all(records_to_create)
        await db.flush()

    session_obj.status = "COMPLETED"
    session_obj.ended_at = datetime.now(timezone.utc)
    session_obj.total_verified = verified_count

    # Count all spoofers and proxy violators on the session
    spoofer_res = await db.execute(
        select(func.count()).select_from(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.status.in_([
                "SPOOFER_REJECTED",
                "DUPLICATE_FACE_PROXY",
                "CO_LOCATED_SAME_DESK_PROXY",
                "NO_FACE_DETECTED",
            ]),
        )
    )
    session_obj.total_spoofers = spoofer_res.scalar() or 0

    await db.commit()
    await db.refresh(session_obj)
    # H2: clear face biometrics only AFTER the commit — clearing before commit
    # left a blind window during the 90s grace period where a duplicate-face
    # resubmission hit an empty registry and slipped past dedup.
    face_registry.clear_session(str(session_obj.id))

    await _populate_course_metadata(session_obj, db)

    logger.info(f"[Audit Finalized] Session: {session_obj.id}, Verified: {session_obj.total_verified}, Spoofers: {session_obj.total_spoofers}, Total Records: {len(audit_results)}")
    return session_obj


# Retrieve detailed session information
@router.get("/sessions/{session_id}", response_model=AttendanceSessionResponse)
async def get_session_details(
    session_id: str,
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    # Require authentication in production
    if not current_user and not is_test_environment():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")

    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")

    await _populate_course_metadata(session_obj, db)

    # Attach student names and roll numbers to records
    if session_obj.records:
        rec_user_ids = [r.student_id for r in session_obj.records if r.student_id]
        if rec_user_ids:
            u_res = await db.execute(select(User).where(User.id.in_(rec_user_ids)))
            u_map = {u.id: u for u in u_res.scalars().all()}
            for r in session_obj.records:
                if r.student_id in u_map:
                    u = u_map[r.student_id]
                    r.roll_number = u.roll_number
                    r.student_name = u.full_name

        # Filter records for student callers to protect classmate privacy
        user_role = (current_user.role or "").upper() if current_user else ""
        if user_role == "STUDENT":
            student_roll = (current_user.roll_number or "").upper()
            session_obj.records = [
                r for r in session_obj.records
                if r.student_id == current_user.id
                or (student_roll and r.ble_device_id.upper() == student_roll[-4:])
            ]

    return session_obj


# Return duplicate face pairs flagged during instructor session
@router.get("/sessions/{session_id}/flagged-faces")
async def get_flagged_faces(
    session_id: str,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")

    # Owner gate (review §1.3.1): flagged pairs carry students' face crops —
    # only the session's own teacher (or a privileged auditor) may view them.
    user_role = (current_user.role or "").upper()
    if session_obj.teacher_id != current_user.id and user_role not in ("DEVELOPER", "ADMIN"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only view flagged faces for your own attendance sessions",
        )

    flagged = face_registry.get_flagged_pairs(str(session_obj.id))
    return {
        "session_id": str(session_obj.id),
        "count": len(flagged),
        "flagged_pairs": flagged,
    }


# Override attendance record status manually
@router.patch("/records/{record_id}/override", response_model=AttendanceRecordResponse)
async def override_attendance_record(
    record_id: uuid.UUID,
    payload: RecordOverrideRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
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


# Verify human face presence in diagnostic image
@router.post("/lab/face-check", response_model=FaceLabCheckResponse)
async def lab_face_check(
    payload: FaceLabCheckRequest,
    current_user: User = Depends(require_teacher),
):
    if len(payload.face_image_base64) > 5 * 1024 * 1024:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="Image payload too large")
    try:
        clean_b64 = payload.face_image_base64
        if "," in clean_b64:
            clean_b64 = clean_b64.split(",", 1)[1]
        raw_bytes = base64.b64decode(clean_b64)
        has_face, _, remarks = await asyncio.to_thread(
            face_registry.engine.extract_face_and_embedding,
            raw_bytes
        )
        return FaceLabCheckResponse(has_face=has_face, remarks=remarks)
    except Exception as e:
        return FaceLabCheckResponse(has_face=False, remarks=f"Processing error: {e}")


# Compare two diagnostic face images
@router.post("/lab/face-compare", response_model=FaceLabCompareResponse)
async def lab_face_compare(
    payload: FaceLabCompareRequest,
    current_user: User = Depends(require_teacher),
):
    if len(payload.image_a_base64) > 5 * 1024 * 1024 or len(payload.image_b_base64) > 5 * 1024 * 1024:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="Image payload too large")
    try:
        clean_a = payload.image_a_base64.split(",", 1)[1] if "," in payload.image_a_base64 else payload.image_a_base64
        clean_b = payload.image_b_base64.split(",", 1)[1] if "," in payload.image_b_base64 else payload.image_b_base64
        bytes_a = base64.b64decode(clean_a)
        bytes_b = base64.b64decode(clean_b)

        has_face_a, has_face_b, similarity, is_duplicate, remarks = await asyncio.to_thread(
            face_registry.engine.compare_two_images,
            bytes_a,
            bytes_b
        )
        return FaceLabCompareResponse(
            has_face_a=has_face_a,
            has_face_b=has_face_b,
            similarity_score=similarity,
            is_duplicate=is_duplicate,
            remarks=remarks
        )
    except Exception as e:
        return FaceLabCompareResponse(
            has_face_a=False,
            has_face_b=False,
            similarity_score=0.0,
            is_duplicate=False,
            remarks=f"Comparison error: {e}"
        )

