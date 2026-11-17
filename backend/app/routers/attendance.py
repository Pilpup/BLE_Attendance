from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from typing import List, Optional, Union

logger = logging.getLogger("uvicorn.error")

from fastapi import APIRouter, Body, Depends, HTTPException, Request, status
from sqlalchemy import delete, func, and_, or_, select
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
    limiter_ip,
    require_teacher,
)
from app.database import get_db
from app.services.rate_limiter import join_rate_limiter
from app.models.attendance import (
    AttendanceRecord,
    AttendanceSession,
    Course,
    CourseUnit,
    LeaveApplication,
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
    SessionJoinRequest,
    SessionJoinResponse,
    SubmitNeighborsRequest,
    UnitTopicCreate,
    UnitTopicResponse,
    FaceLabCheckRequest,
    FaceLabCheckResponse,
    FaceLabCompareRequest,
    FaceLabCompareResponse,
)
from app.services.face_engine import face_registry
from app.services.rate_limiter import face_lab_limiter, submit_rate_limiter
from app.services.session_audit import (
    lock_session as svc_lock_session,
    maybe_run_due_audit,
    maybe_run_due_lock,
    register_session_schedule,
    run_engine_audit as svc_run_engine_audit,
    unregister_session_schedule,
)
from app.services.submission_buffer import submission_buffer

# --- H5: server-issued session salt helpers ---
# Canonical salt = high-entropy secret stored server-side (never on BLE wire).
# Wire salt = fixed 4-char token derived for the BLE beacon; deterministic so
# teacher (who receives canonical salt at create) and server compute the same
# value. Static per session today; epoch parameter reserved for rotation.
WIRE_SALT_EPOCH_SECONDS = 0  # 0 => static wire salt for session lifetime


def derive_wire_salt(canonical_salt: str, session_token: str, epoch: int = 0) -> str:
    if not canonical_salt:
        return ""
    msg = f"{session_token}:{epoch}".encode()
    return hmac.new(canonical_salt.encode(), msg, hashlib.sha256).hexdigest()[:4].upper()


# M4: collision-resistant 6-char BLE/device key derived from canonical identity.
# Must match Android (full roll preferred, else full user UUID string).
# 6 hex chars = 16.7M space: birthday collision odds drop from ~3.7% per
# 70-student class (4 chars = 65,536) to ~0.015% — two honest students can no
# longer share one attendance row. Key travels in JSON/neighbors only, never
# in the beacon payload, so the widened format costs no wire budget.
def derive_short_device_key(canonical: str) -> str:
    src = (canonical or "").strip().upper()
    if not src:
        return "000000"
    return hashlib.sha256(src.encode("utf-8")).hexdigest()[:6].upper()


def user_device_key(user: User) -> str:
    canonical = user.roll_number if getattr(user, "roll_number", None) else str(user.id)
    return derive_short_device_key(canonical)


def current_wire_epoch() -> int:
    # Rotation hook: when WIRE_SALT_EPOCH_SECONDS > 0, derive per epoch and
    # accept current + previous in valid_wire_salts during the grace window.
    return 0


def session_wire_salt(session_obj) -> str:
    token = getattr(session_obj, "session_token", "") or ""
    canonical = getattr(session_obj, "session_salt", "") or ""
    return derive_wire_salt(canonical, token, current_wire_epoch())


def valid_wire_salts(session_obj) -> list[str]:
    token = getattr(session_obj, "session_token", "") or ""
    canonical = getattr(session_obj, "session_salt", "") or ""
    if not canonical:
        return []
    epoch = current_wire_epoch()
    salts = [derive_wire_salt(canonical, token, epoch)]
    # Grace window: accept previous epoch during rotation handoff.
    if WIRE_SALT_EPOCH_SECONDS > 0 and epoch > 0:
        salts.append(derive_wire_salt(canonical, token, epoch - 1))
    # Test-only debug sessions: legacy fixed salt accepted for deterministic tests.
    if is_test_environment() and token.upper() in ("AUTO-101", "DEMO", "DEBUG-1"):
        salts.append("DEMO1")
    return salts


def _attach_wire_salt(session_obj):
    # Expose derived wire salt on the ORM object so SessionResponse
    # (from_attributes) can serialize it alongside the canonical salt.
    try:
        session_obj.session_wire_salt = session_wire_salt(session_obj)
    except Exception:
        session_obj.session_wire_salt = None
    return session_obj


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
            started_at = datetime.now(timezone.utc)
            debug_sess = AttendanceSession(
                id=uuid.uuid4(),
                course_id=course.id,
                teacher_id=teacher.id,
                session_token=debug_token,
                session_salt="DEMO1",
                status="ACTIVE",
                started_at=started_at,
                game_starts_at=started_at + timedelta(seconds=10),
                game_ends_at=started_at + timedelta(seconds=37, microseconds=500000),
            )
            db.add(debug_sess)
            await db.commit()
            await db.refresh(debug_sess)
        else:
            debug_sess.status = "ACTIVE"
            if debug_sess.started_at is None:
                debug_sess.started_at = datetime.now(timezone.utc)
            if debug_sess.game_starts_at is None or debug_sess.game_ends_at is None:
                base = debug_sess.started_at
                if base.tzinfo is None:
                    base = base.replace(tzinfo=timezone.utc)
                debug_sess.game_starts_at = base + timedelta(seconds=10)
                debug_sess.game_ends_at = base + timedelta(seconds=37, microseconds=500000)
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


# R5-consistent counter recompute, shared by both finalize paths and the
# manual override route: counters must reflect committed DB rows, not
# payload-computed tallies — prior VERIFIED rows absent from the current
# request (grace-path additions, out-of-band verdicts, later overrides) would
# otherwise be dropped from the visible counts. LEAVE rows count as neither
# verified nor unverified (approved-leave overlay output).
async def _recompute_session_counters(session_obj: AttendanceSession, db: AsyncSession) -> None:
    await db.flush()
    verified_res = await db.execute(
        select(func.count()).select_from(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            # Two-flag engine writes VERIFIED; legacy rows (pre-migration
            # sessions, old-APK finalize payloads) carry VERIFIED_PRESENT.
            AttendanceRecord.status.in_(["VERIFIED", "VERIFIED_PRESENT"]),
        )
    )
    session_obj.total_verified = verified_res.scalar() or 0
    # Count every record that did not end VERIFIED — witnessed offline
    # students, isolated seats, spoofers, proxy flags, challenge failures.
    unverified_res = await db.execute(
        select(func.count()).select_from(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.status.not_in(
                ["VERIFIED", "VERIFIED_PRESENT", "LEAVE"]
            ),
        )
    )
    session_obj.total_unverified = unverified_res.scalar() or 0


# R11-B fix: the audit-and-finalize response is the teacher's LIVE post-class
# list (the app maps body.records straight onto finalizedRecords), but unlike
# the history/detail routes it returned raw AttendanceRecord rows — with no
# users join, so student_name/roll_number stayed NULL and the teacher screen
# degraded to "Student 20" fallback names. Same enrichment the other two read
# routes perform; records carry no persistent name column (name is never
# stored, always joined from users).
async def _populate_record_student_details(
    session_obj: AttendanceSession, db: AsyncSession
) -> None:
    if not session_obj.records:
        return
    student_ids = [r.student_id for r in session_obj.records if r.student_id]
    if not student_ids:
        return
    u_res = await db.execute(select(User).where(User.id.in_(student_ids)))
    user_map = {u.id: u for u in u_res.scalars().all()}
    for r in session_obj.records:
        u = user_map.get(r.student_id) if r.student_id else None
        if u is not None:
            r.roll_number = u.roll_number
            r.student_name = u.full_name


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
    game_score: Optional[int] = None,
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
        if game_score is not None:
            rec.game_score = game_score if rec.game_score is None else max(rec.game_score, game_score)
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
        game_score=game_score,
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
            if game_score is not None:
                rec.game_score = game_score if rec.game_score is None else max(rec.game_score, game_score)
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
    return _attach_wire_salt(session_obj)


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

    query = select(Course).options(
        selectinload(Course.units).selectinload(CourseUnit.topics)
    )
    if semester is not None:
        query = query.where(Course.semester == semester)
    if department is not None:
        query = query.where(Course.department.ilike(f"%{department.strip()}%"))
    if program is not None:
        query = query.where(Course.program.ilike(f"%{program.strip()}%"))
    result = await db.execute(query.order_by(Course.semester.asc(), Course.course_code.asc()))
    return list(result.scalars().all())


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
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A course with this course_code already exists",
        )
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
    # Same owner-gate policy as every other session endpoint: owner OR
    # DEVELOPER/ADMIN (dev/support tooling must read course sessions).
    user_role = (current_user.role or "").upper()
    if course.teacher_id != current_user.id and user_role not in ("DEVELOPER", "ADMIN"):
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
    if user_role not in ("TEACHER", "HOD", "DEVELOPER", "STAFF", "FACULTY", "ADMIN"):
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

    for s in sessions:
        _attach_wire_salt(s)
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

    # Roster anchor: the audit engine weights collusion clusters against the
    # enrolled class size (graph_engine only anchors when total_enrolled > 0).
    # The teacher app sends no roster count, so derive it from the same
    # eligibility snapshot the student check-in gate uses (semester + program).
    roster_enrolled = payload.total_enrolled
    roster_roll_numbers: list[str] = []
    if (not roster_enrolled) and eligibility_semester is not None and (eligibility_program or "").strip():
        candidates = await db.execute(
            select(User).where(
                User.semester == eligibility_semester,
                User.is_active.is_(True),
                User.role == "STUDENT",
            )
        )
        matched_students = [
            u for u in candidates.scalars().all()
            if _program_key(u.program) == _program_key(eligibility_program)
        ]
        roster_enrolled = len(matched_students)
        roster_roll_numbers = [u.roll_number for u in matched_students if u.roll_number]

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
    # student class-binding gate). H5: server generates canonical salt;
    # client-supplied value is ignored. Wire salt is derived for the beacon.
    canonical_salt = secrets.token_hex(16)
    started_at = datetime.now(timezone.utc)
    session = AttendanceSession(
        id=uuid.uuid4(),
        course_id=target_course_id,
        teacher_id=current_user.id,
        session_token=payload.session_token,
        session_salt=canonical_salt,
        eligibility_program=eligibility_program,
        eligibility_department=eligibility_department,
        eligibility_semester=eligibility_semester,
        status="ACTIVE",
        total_enrolled=roster_enrolled,
        started_at=started_at,
        game_starts_at=started_at + timedelta(seconds=10),
        game_ends_at=started_at + timedelta(seconds=37, microseconds=500000),
    )
    db.add(session)
    try:
        await db.commit()
    except IntegrityError:
        # R-concurrency: a concurrent create committed the same ACTIVE token
        # between our pre-insert SELECT and this commit — the partial unique
        # index rejected us. Re-check to confirm the collision (any other
        # IntegrityError re-raises): same 409 the pre-insert check produces.
        await db.rollback()
        conflict_res = await db.execute(
            select(AttendanceSession).where(
                AttendanceSession.session_token == payload.session_token,
                AttendanceSession.status == "ACTIVE",
            )
        )
        if conflict_res.scalars().first():
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"Session ID '{payload.session_token}' is already in use by an active session — pick a new session ID",
            )
        raise
    await db.refresh(session)
    session.records = []

    await _populate_course_metadata(session, db)

    # Register event-driven audit/lock schedule (no DB polling).
    try:
        await register_session_schedule(str(session.id), session.started_at, session.game_ends_at)
    except Exception:
        logger.warning("Failed to register audit schedule for session %s", session.id)

    # M9 (audit): never log gate material — token/salt in container logs
    # defeats the BLE-range assumption (anyone with log access gets the gate).
    logger.info(f"[Session Created] ID: {session.id}, Course: {session.course_code}")
    # Roster diagnostic: the enrolled count is DERIVED from the eligibility
    # snapshot (semester + program) over active STUDENT accounts — the teacher
    # app sends no roster. Only the COUNT is logged: individual roll numbers
    # are PII and were sitting in Render's retained logs for no runtime
    # benefit.
    if roster_roll_numbers:
        logger.info(
            "[Roster] Derived total_enrolled=%d for program=%r semester=%r (%d matched active STUDENT accounts)",
            roster_enrolled, eligibility_program, eligibility_semester,
            len(roster_roll_numbers),
        )
    _attach_wire_salt(session)
    return session


# Student join preflight (latch-time): eligibility + salt + authoritative schedule
@router.post("/sessions/{session_id}/join", response_model=SessionJoinResponse)
async def join_attendance_session(
    session_id: str,
    payload: SessionJoinRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    server_time = datetime.now(timezone.utc)
    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.status == "CANCELLED":
        raise HTTPException(status_code=400, detail="Attendance session has been cancelled")
    if session_obj.status in ("COMPLETED", "EXPIRED"):
        raise HTTPException(status_code=410, detail="Attendance session is no longer active")
    if session_obj.status != "ACTIVE":
        raise HTTPException(status_code=400, detail="Attendance session is not active")
    if _is_session_stale(session_obj.started_at, minutes=5):
        raise HTTPException(status_code=400, detail="Attendance session has expired")
    if session_obj.game_starts_at is None or session_obj.game_ends_at is None:
        raise HTTPException(status_code=400, detail="Session uses old timing — restart class")

    # Brute-force budget (per account): the wire salt gate is only 16 bits, so
    # an unthrottled /join would let an off-site student discover the in-room
    # salt by enumeration and then check in remotely.
    if not await join_rate_limiter.check(f"{session_obj.id}:{current_user.id}"):
        raise HTTPException(status_code=429, detail="Too many join attempts — wait a moment")

    provided_salt = (payload.session_salt or "").strip().upper()
    expected_salts = valid_wire_salts(session_obj)
    salt_ok = bool(provided_salt) and any(secrets.compare_digest(provided_salt, w) for w in expected_salts)
    if not salt_ok:
        logger.warning("[Join Gate] Session %s: salt mismatch for user %s", session_obj.id, current_user.id)
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="In-room beacon verification failed")

    eligibility_detail = student_eligibility_detail(session_obj, current_user)
    if eligibility_detail:
        logger.warning("[Join Gate] Session %s: ineligible user %s — %s", session_obj.id, current_user.id, eligibility_detail)
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You don't belong to this class session")

    return SessionJoinResponse(
        game_starts_at=session_obj.game_starts_at,
        game_ends_at=session_obj.game_ends_at,
        server_time=server_time,
    )


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
        return _attach_wire_salt(session_obj)

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
    await unregister_session_schedule(str(session_obj.id))

    await _populate_course_metadata(session_obj, db)
    logger.info(f"[Session Cancelled] Session: {session_obj.id}")
    return _attach_wire_salt(session_obj)


# Finalize attendance session (idempotent wrapper; engine runs server-side at t=45/lock)
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
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only finalize your own attendance sessions")
    if session_obj.status == "CANCELLED":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Cannot finalize a cancelled session")

    # Compatibility: ingest client-provided records before server audit/lock.
    if payload and getattr(payload, "records", None):
        for item in payload.records:
            device_id = (item.ble_device_id or "").strip().upper()
            if not device_id:
                continue
            rec_res = await db.execute(select(AttendanceRecord).where(
                AttendanceRecord.session_id == session_obj.id,
                AttendanceRecord.ble_device_id == device_id,
            ))
            rec = rec_res.scalars().first()
            if rec is None and item.student_id:
                rec_res = await db.execute(select(AttendanceRecord).where(
                    AttendanceRecord.session_id == session_obj.id,
                    AttendanceRecord.student_id == item.student_id,
                ))
                rec = rec_res.scalars().first()
            status = getattr(item, "status", None) or "UNVERIFIED"
            if status == "VERIFIED_PRESENT":
                status = "VERIFIED"
            if rec is None:
                rec = AttendanceRecord(
                    id=uuid.uuid4(), session_id=session_obj.id, student_id=item.student_id,
                    ble_device_id=device_id, status=status,
                    verification_method=getattr(item, "verification_method", "NONE") or "NONE",
                    affinity_score=float(getattr(item, "affinity_score", 0.0) or 0.0),
                    bridge_device_id=(item.bridge_device_id.upper() if getattr(item, "bridge_device_id", None) else None),
                    reported_neighbors=getattr(item, "reported_neighbors", None) or [],
                    remarks=getattr(item, "remarks", None),
                    game_score=getattr(item, "game_score", None),
                    recorded_at=datetime.now(timezone.utc),
                )
                db.add(rec)
            else:
                frozen = {"DUPLICATE_FACE_PROXY","CO_LOCATED_SAME_DESK_PROXY","DIVIDED_ATTENTION_PROXY","COGNITIVE_CHALLENGE_FAILED","NO_FACE_DETECTED","SPOOFER_REJECTED","ELIGIBILITY_REJECTED"}
                if rec.status not in frozen:
                    rec.status = status
                    rec.verification_method = getattr(item, "verification_method", rec.verification_method) or rec.verification_method
                    rec.affinity_score = float(getattr(item, "affinity_score", rec.affinity_score) or 0.0)
                    if getattr(item, "remarks", None) is not None:
                        rec.remarks = item.remarks
                    if getattr(item, "reported_neighbors", None) is not None:
                        rec.reported_neighbors = item.reported_neighbors
                    if getattr(item, "game_score", None) is not None:
                        gs = item.game_score
                        rec.game_score = gs if rec.game_score is None else max(rec.game_score, gs)
                if item.student_id and not rec.student_id:
                    rec.student_id = item.student_id
        try:
            await db.commit()
        except Exception:
            await db.rollback()

    try:
        await svc_run_engine_audit(session_obj.id, db)
        await svc_lock_session(session_obj.id, db)
    except HTTPException:
        raise
    except Exception as e:
        logger.warning("Finalize wrapper failed for session %s: %s", session_id, e)
        raise HTTPException(status_code=500, detail="Failed to finalize session")

    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    final_recs = await db.execute(select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id))
    session_obj.records = list(final_recs.scalars().all())
    await _populate_course_metadata(session_obj, db)
    await _populate_record_student_details(session_obj, db)
    await unregister_session_schedule(str(session_obj.id))
    return _attach_wire_salt(session_obj)


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
    raw_face_bytes = None
    if isinstance(payload, bytes):
        raw_face_bytes = payload
        session_salt = request.headers.get("x-session-salt")
        student_id = request.headers.get("x-student-id")
        neighbors_raw = request.headers.get("x-neighbors", "")
        neighbors = [n.strip() for n in neighbors_raw.split(",") if n.strip()] if neighbors_raw else []
        close_raw = request.headers.get("x-close-neighbors", "")
        close_neighbors = [n.strip() for n in close_raw.split(",") if n.strip()] if close_raw else []
        challenge_report_hdr = request.headers.get("x-challenge-report")
        challenge_report = None
        game_score = None
        game_score_hdr = request.headers.get("x-game-score") or request.headers.get("x-chimp-score")
        if game_score_hdr:
            try:
                game_score = int(game_score_hdr.strip())
            except ValueError:
                game_score = None
        if challenge_report_hdr:
            try:
                challenge_report = {
                    k.strip(): int(v)
                    for k, v in (p.split("=", 1) for p in challenge_report_hdr.split(",") if "=" in p)
                }
                if game_score is None:
                    for k in ("chimp_score", "score", "game_score"):
                        if k in challenge_report:
                            game_score = int(challenge_report[k])
                            break
            except Exception:
                challenge_report = {"raw": challenge_report_hdr}
                if game_score is None:
                    try:
                        game_score = int(challenge_report_hdr.strip())
                    except Exception:
                        pass
        # Mirror the JSON schema cap (0-26): the raw-bytes header path must not
        # bypass the gate, else x-game-score: 99 trivially passes >= 15 checks.
        if game_score is not None:
            game_score = max(0, min(26, game_score))
        payload = SubmitNeighborsRequest(
            student_id=student_id,
            neighbors=neighbors,
            close_neighbors=close_neighbors,
            session_salt=session_salt,
            face_image_base64=None,
            challenge_report=challenge_report,
            game_score=game_score,
        )

    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.status == "CANCELLED":
        raise HTTPException(status_code=400, detail="Attendance session has been cancelled")
    if session_obj.status == "COMPLETED":
        # Stragglers allowed only while within lock window is handled below; post-lock -> 400.
        pass
    if session_obj.status not in ("ACTIVE", "COMPLETED"):
        raise HTTPException(status_code=400, detail="Attendance session is not active")
    if session_obj.status == "ACTIVE" and _is_session_stale(session_obj.started_at, minutes=5):
        session_obj.status = "EXPIRED"
        session_obj.ended_at = datetime.now(timezone.utc)
        await db.commit()
        raise HTTPException(status_code=400, detail="Attendance session has expired")

    # Hard cutover: legacy sessions without schedule cannot use new pipeline.
    if session_obj.game_starts_at is None or session_obj.game_ends_at is None:
        raise HTTPException(status_code=400, detail="Session uses old timing — restart class")

    # Lock window: started_at + 60s. After lock, reject.
    started = session_obj.started_at
    if started and started.tzinfo is None:
        started = started.replace(tzinfo=timezone.utc)
    if started and (datetime.now(timezone.utc) - started).total_seconds() >= 60 and session_obj.status == "COMPLETED":
        raise HTTPException(status_code=400, detail="Session finalized")

    eligibility_detail = student_eligibility_detail(session_obj, current_user)
    student_key = user_device_key(current_user)

    def _norm_list(items: list[str] | None) -> list[str]:
        if not items:
            return []
        out = []
        seen = set()
        for n in items:
            k = str(n).strip().upper()
            k = k[-6:] if len(k) >= 6 else k
            if k and k not in seen:
                seen.add(k)
                out.append(k)
        return out

    normalized_neighbors = _norm_list(payload.neighbors)
    normalized_close = _norm_list(payload.close_neighbors)

    if eligibility_detail:
        await _record_proxy_violation(
            db=db, session_id=session_obj.id, device_id=student_key,
            status="SPOOFER_REJECTED", verification_method="ELIGIBILITY_REJECTED",
            remarks=eligibility_detail, student_id=current_user.id,
            reported_neighbors=normalized_neighbors,
        )
        await _commit_proxy_violation(db)
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=eligibility_detail)

    if not await submit_rate_limiter.check(str(session_obj.id), str(current_user.id)):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many check-in submissions for this session; resubmit shortly")

    provided_salt = (payload.session_salt or "").strip().upper()
    expected_salts = valid_wire_salts(session_obj)
    salt_ok = bool(provided_salt) and any(secrets.compare_digest(provided_salt, w) for w in expected_salts)
    if not salt_ok:
        await _record_proxy_violation(
            db=db, session_id=session_obj.id, device_id=student_key,
            status="SPOOFER_REJECTED", verification_method="SALT_REJECTED",
            remarks="Remote check-in blocked: in-room beacon salt mismatch",
            student_id=current_user.id, reported_neighbors=normalized_neighbors,
        )
        await _commit_proxy_violation(db)
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Nice try lil bro")

    # Score: stored on upload; engine stamps UNVERIFIED below gate (no 403 here).
    challenge_score = payload.game_score
    if challenge_score is None and payload.challenge_report:
        for k in ("chimp_score", "score", "game_score"):
            if k in payload.challenge_report:
                try:
                    challenge_score = int(payload.challenge_report[k])
                except Exception:
                    pass
                break

    face_check_result = None
    if payload.face_image_base64 or raw_face_bytes:
        raw_roll = current_user.roll_number or student_key
        cluster_peers = set(normalized_close)
        cluster_peers.update(normalized_neighbors)
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
            await _record_proxy_violation(
                db=db, session_id=session_obj.id, device_id=student_key,
                status="NO_FACE_DETECTED", verification_method="FACE_BIOMETRIC_FAILED",
                remarks=face_check_result.remarks, student_id=current_user.id,
                reported_neighbors=normalized_neighbors,
            )
            await _commit_proxy_violation(db)
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=f"Biometric proxy rejected: {face_check_result.remarks}")
        if face_check_result.is_duplicate:
            await _record_proxy_violation(
                db=db, session_id=session_obj.id, device_id=student_key,
                status="DUPLICATE_FACE_PROXY", verification_method="FACE_BIOMETRIC_MATCH",
                remarks=face_check_result.remarks, student_id=current_user.id,
                reported_neighbors=normalized_neighbors,
            )
            await _commit_proxy_violation(db)
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=f"Biometric proxy rejected: {face_check_result.remarks}")

    # Upsert by (session_id, ble_device_id); fallback on (session_id, student_id).
    rec_res = await db.execute(select(AttendanceRecord).where(
        AttendanceRecord.session_id == session_obj.id,
        AttendanceRecord.ble_device_id == student_key,
    ))
    rec = rec_res.scalars().first()
    if rec is None:
        rec_res2 = await db.execute(select(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.student_id == current_user.id,
        ))
        rec = rec_res2.scalars().first()

    now_utc = datetime.now(timezone.utc)
    if rec is None:
        rec = AttendanceRecord(
            id=uuid.uuid4(), session_id=session_obj.id, student_id=current_user.id,
            ble_device_id=student_key, status="UNVERIFIED", verification_method="AWAITING_AUDIT",
            affinity_score=0.00, reported_neighbors=normalized_neighbors,
            remarks="Check-in received; awaiting audit", game_score=challenge_score,
            recorded_at=now_utc,
        )
        db.add(rec)
    else:
        # Do not overwrite frozen security verdicts except allow score/list refresh context.
        if rec.status not in ("DUPLICATE_FACE_PROXY", "CO_LOCATED_SAME_DESK_PROXY", "DIVIDED_ATTENTION_PROXY", "NO_FACE_DETECTED", "SPOOFER_REJECTED", "ELIGIBILITY_REJECTED"):
            rec.reported_neighbors = normalized_neighbors
            rec.remarks = "Check-in received; awaiting audit"
            if session_obj.audit_run_at is None:
                rec.status = "UNVERIFIED"
                rec.verification_method = "AWAITING_AUDIT"
                rec.affinity_score = 0.00
        if challenge_score is not None:
            rec.game_score = challenge_score if rec.game_score is None else max(rec.game_score, challenge_score)
        if not rec.student_id:
            rec.student_id = current_user.id
        rec.recorded_at = now_utc

    try:
        await db.flush()
    except IntegrityError:
        await db.rollback()
        # Retry once: fetch existing row and update.
        rec_res = await db.execute(select(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.ble_device_id == student_key,
        ))
        rec = rec_res.scalars().first()
        if rec:
            if rec.status not in ("DUPLICATE_FACE_PROXY", "CO_LOCATED_SAME_DESK_PROXY", "DIVIDED_ATTENTION_PROXY", "NO_FACE_DETECTED", "SPOOFER_REJECTED", "ELIGIBILITY_REJECTED") and session_obj.audit_run_at is None:
                rec.reported_neighbors = normalized_neighbors
                rec.status = "UNVERIFIED"
                rec.verification_method = "AWAITING_AUDIT"
                rec.affinity_score = 0.00
            if challenge_score is not None:
                rec.game_score = challenge_score if rec.game_score is None else max(rec.game_score, challenge_score)

    # Straggler O(1) path: audit already ran.
    if session_obj.audit_run_at is not None and session_obj.status == "ACTIVE":
        started = session_obj.started_at
        if started and started.tzinfo is None:
            started = started.replace(tzinfo=timezone.utc)
        if started and (datetime.now(timezone.utc) - started).total_seconds() >= 60:
            await db.commit()
            raise HTTPException(status_code=400, detail="Session finalized")
        # Strict reciprocated edge with an already-VERIFIED, non-frozen row.
        hard_violations = {"DUPLICATE_FACE_PROXY", "CO_LOCATED_SAME_DESK_PROXY", "DIVIDED_ATTENTION_PROXY", "SPOOFER_REJECTED", "NO_FACE_DETECTED", "ELIGIBILITY_REJECTED"}
        verified_partners = []
        if normalized_neighbors and rec and rec.status not in hard_violations:
            p_res = await db.execute(select(AttendanceRecord).where(
                AttendanceRecord.session_id == session_obj.id,
                AttendanceRecord.ble_device_id.in_(normalized_neighbors),
                AttendanceRecord.status.in_(["VERIFIED", "VERIFIED_PRESENT"]),
            ))
            for partner in p_res.scalars().all():
                if partner.status in hard_violations:
                    continue
                partner_neighbors = {str(n).strip().upper()[-6:] if len(str(n).strip()) >= 6 else str(n).strip().upper() for n in (partner.reported_neighbors or [])}
                if student_key.upper() in partner_neighbors:
                    verified_partners.append(partner.ble_device_id)
        if rec and rec.status not in hard_violations and rec.status not in ("VERIFIED", "VERIFIED_PRESENT"):
            # Engine already issued a verdict this lock window; a straggler
            # re-check-in must never downgrade an existing VERIFIED row.
            score_ok = (rec.game_score is not None and rec.game_score >= 15)
            if verified_partners and score_ok:
                rec.status = "VERIFIED"
                rec.verification_method = "STRAGGLER_MUTUAL"
                rec.affinity_score = 1.00
                rec.remarks = f"Late check-in verified via mutual desk neighbor [{', '.join(verified_partners)}]"
            else:
                rec.status = "UNVERIFIED"
                rec.verification_method = "STRAGGLER"
                rec.affinity_score = 0.00
                if not score_ok:
                    rec.remarks = f"Late check-in: challenge score {rec.game_score if rec.game_score is not None else 'missing'} < 15 - Teacher check"
                elif verified_partners:
                    rec.remarks = "Late check-in: mutual evidence insufficient - Teacher check"
                else:
                    rec.remarks = "Late check-in without reciprocal verified neighbor - Teacher check"
        await db.flush()

    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()

    # Keep session counters fresh for live reads; audit_run_at lock recomputes at 60.
    try:
        await _recompute_session_counters(session_obj, db)
        await db.commit()
    except Exception:
        await db.rollback()

    logger.info("[Check-In] Student: %s neighbors=%d session=%s", student_key, len(normalized_neighbors), session_obj.id)
    return {
        "status": "success",
        "student": student_key,
        "recorded_neighbors": normalized_neighbors,
        "face_verified": (
            (face_check_result.has_human_face and not face_check_result.is_duplicate)
            if face_check_result else None
        ),
        "face_diagnostic": face_check_result.remarks if face_check_result else None,
    }

# Execute social-graph audit and finalize session (idempotent wrapper)
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
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only finalize your own attendance sessions")
    if session_obj.status == "CANCELLED":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Cannot finalize a cancelled session")

    try:
        await svc_run_engine_audit(session_obj.id, db)
        await svc_lock_session(session_obj.id, db)
    except HTTPException:
        raise
    except Exception as e:
        logger.warning("Audit-finalize wrapper failed for session %s: %s", session_id, e)
        raise HTTPException(status_code=500, detail="Failed to audit/finalize session")

    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    final_recs = await db.execute(select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id))
    session_obj.records = list(final_recs.scalars().all())
    await _populate_course_metadata(session_obj, db)
    await _populate_record_student_details(session_obj, db)
    await unregister_session_schedule(str(session_obj.id))
    return _attach_wire_salt(session_obj)

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

    # Ownership gate for non-students: TEACHER/STAFF/FACULTY/HOD callers may
    # only read their own sessions (privileged auditors excepted). Without
    # this, any authenticated instructor could read the full roster — names,
    # roll numbers, statuses — of every other teacher's session.
    user_role = (current_user.role or "").upper() if current_user else ""
    if user_role not in ("STUDENT", ""):
        if session_obj.teacher_id != current_user.id and user_role not in ("DEVELOPER", "ADMIN"):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You are not authorized to view this attendance session",
            )

    # Lazy trigger (Render/Neon safe): roster fetch / student poll may be the only wakeup.
    try:
        await maybe_run_due_audit(session_obj, db)
        await db.refresh(session_obj)
        await maybe_run_due_lock(session_obj, db)
        session_obj = await _resolve_session(session_id, db) or session_obj
    except Exception as e:
        logger.warning("Lazy audit/lock trigger failed for session %s: %s", session_id, e)

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

        # Filter records for student callers to protect classmate privacy.
        # Own-key match uses the hashed device key derived from the JWT account
        # (the old roll[-4:] comparison never matched hashed keys, so rows with
        # an unbound student_id were invisible to the student they belonged to).
        if user_role == "STUDENT":
            own_key = user_device_key(current_user)
            session_obj.records = [
                r for r in session_obj.records
                if r.student_id == current_user.id
                or r.ble_device_id.upper() == own_key
            ]

    _attach_wire_salt(session_obj)
    # H5 gate material must not travel to student clients: the wire salt's
    # purpose is proving physical presence via the teacher's BLE beacon. The
    # student app derives the salt from the beacon packet only — it never
    # reads this route for it — so blanking both salts here raises the cost
    # of remote check-ins without breaking any client flow. (The gate remains
    # weak against two in-room colluders sharing the salt out-of-band.)
    user_role = (current_user.role or "").upper() if current_user else ""
    if user_role in ("STUDENT", ""):
        session_obj.session_salt = None
        session_obj.session_wire_salt = None
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

    # Deliberate chimp-gate exemption: the instructor's manual verdict is the
    # trusted authority, so an override may set VERIFIED without a
    # passing game_score (the schema whitelist at RecordOverrideRequest bounds
    # what can be stamped). Counters are recomputed from durable rows (the
    # helper flushes) so the dashboard reflects the override immediately.
    await _recompute_session_counters(session_obj, db)
    await db.commit()
    await db.refresh(record_obj)
    return record_obj


# Verify human face presence in diagnostic image
@router.post("/lab/face-check", response_model=FaceLabCheckResponse)
async def lab_face_check(
    payload: FaceLabCheckRequest,
    current_user: User = Depends(require_teacher),
):
    # Per-teacher budget: decode + cv2 inference is the heaviest CPU work in
    # the app; the gate keeps a misbehaving client from pinning the worker.
    if not await face_lab_limiter.check(str(current_user.id)):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many face lab requests; slow down")
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
        # R5 (review §5 honesty): failures are server errors, not a pretend
        # 200 carrying an error string in remarks.
        logger.error(f"[Face Lab] face-check failed: {e}")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Face processing failed")


# Compare two diagnostic face images
@router.post("/lab/face-compare", response_model=FaceLabCompareResponse)
async def lab_face_compare(
    payload: FaceLabCompareRequest,
    current_user: User = Depends(require_teacher),
):
    if not await face_lab_limiter.check(str(current_user.id)):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many face lab requests; slow down")
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
        # R5 (review §5 honesty): failures are server errors, not a pretend
        # 200 carrying an error string in remarks.
        logger.error(f"[Face Lab] compare failed: {e}")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Image comparison failed")

