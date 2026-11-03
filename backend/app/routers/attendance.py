from __future__ import annotations

import base64
import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

logger = logging.getLogger("uvicorn.error")

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import delete, func, or_, select
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
    FaceLabCheckRequest,
    FaceLabCheckResponse,
    FaceLabCompareRequest,
    FaceLabCompareResponse,
)
from app.services.face_engine import face_registry
from app.services.graph_engine import audit_classroom_mesh

router = APIRouter(prefix="/attendance", tags=["Attendance"])


# Resolve an AttendanceSession by UUID or session token
async def _resolve_session(session_id: str, db: AsyncSession) -> Optional[AttendanceSession]:
    load_opts = selectinload(AttendanceSession.records)

    # Auto-provision active session for standalone debug testing
    if session_id.upper() in ("AUTO-101", "DEMO", "DEBUG-1"):
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
        )
    else:
        query = (
            select(AttendanceSession)
            .options(load_opts)
            .where(AttendanceSession.session_token == session_id)
        )
    result = await db.execute(query)
    return result.scalars().first()


# Ensure default teacher and course exist for development testing
async def _get_or_create_mock_context(db: AsyncSession):
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
async def _record_proxy_violation(
    db: AsyncSession,
    session_id: uuid.UUID,
    device_id: str,
    status: str,
    verification_method: str,
    remarks: str,
    student_id: Optional[uuid.UUID] = None,
    reported_neighbors: Optional[List[str]] = None,
) -> AttendanceRecord:
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
    else:
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
        db.add(rec)
    return rec


# Course Endpoints
# List available courses
@router.get("/courses", response_model=List[CourseResponse])
async def list_courses(
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    await _get_or_create_mock_context(db)

    # Return teacher's courses if authenticated
    if current_user and current_user.role == "TEACHER":
        result = await db.execute(select(Course).where(Course.teacher_id == current_user.id))
        courses = list(result.scalars().all())
        if not courses:
            default_subjects = [
                ("CS401", "Distributed Systems & BLE Mesh"),
                ("CS302", "Computer Networks & Protocols"),
                ("CS205", "Operating Systems Architecture"),
            ]
            for code, name in default_subjects:
                existing_c = (await db.execute(select(Course).where(Course.course_code == code))).scalars().first()
                if existing_c:
                    existing_c.teacher_id = current_user.id
                    courses.append(existing_c)
                else:
                    new_c = Course(
                        id=uuid.uuid4(),
                        course_code=code,
                        course_name=name,
                        teacher_id=current_user.id,
                    )
                    db.add(new_c)
                    courses.append(new_c)
            await db.commit()
            for c in courses:
                await db.refresh(c)
        return courses

    # Return all courses when unauthenticated or student
    result = await db.execute(select(Course))
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
    )
    db.add(course)
    await db.commit()
    await db.refresh(course)
    return course


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


# List past attendance sessions with academic details
@router.get("/sessions/history", response_model=List[AttendanceSessionResponse])
async def list_teacher_sessions_history(
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    query = (
        select(AttendanceSession)
        .options(selectinload(AttendanceSession.records))
        .order_by(AttendanceSession.started_at.desc())
    )
    user_role = (current_user.role or "").upper() if current_user else ""
    if current_user and user_role in ("TEACHER", "DEVELOPER", "STAFF", "FACULTY", "ADMIN"):
        query = query.where(AttendanceSession.teacher_id == current_user.id)
    else:
        query = query.limit(20)

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

    if payload.course_id:
        try:
            target_uuid = uuid.UUID(payload.course_id)
        except (ValueError, AttributeError):
            target_uuid = None

    if target_uuid:
        course_res = await db.execute(select(Course).where(Course.id == target_uuid))
        course = course_res.scalars().first()
        if course:
            target_course_id = course.id

    if not target_course_id:
        # Resolve or auto-create course
        raw_code = payload.course_code or (payload.course_id if not target_uuid else None) or "CS101"
        code_to_check = raw_code.strip()[:30].upper()
        c_res = await db.execute(select(Course).where(func.upper(Course.course_code) == code_to_check))
        existing_c = c_res.scalars().first()
        if existing_c:
            target_course_id = existing_c.id
        else:
            new_course_id = target_uuid or uuid.uuid4()
            new_c = Course(
                id=new_course_id,
                course_code=code_to_check,
                course_name=(payload.course_name or "General Lecture & Attendance")[:200],
                teacher_id=current_user.id,
            )
            db.add(new_c)
            await db.commit()
            await db.refresh(new_c)
            target_course_id = new_c.id

    # Auto-supersede and expire lingering active sessions for this course
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

    # Save new active attendance session
    session = AttendanceSession(
        id=uuid.uuid4(),
        course_id=target_course_id,
        teacher_id=current_user.id,
        session_token=payload.session_token,
        session_salt=payload.session_salt,
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

    # Cancel session and remove ephemeral submissions
    session_obj.status = "CANCELLED"
    session_obj.ended_at = datetime.now(timezone.utc)
    await db.execute(
        delete(SessionSubmission).where(SessionSubmission.session_id == session_obj.id)
    )
    await db.commit()
    await db.refresh(session_obj)

    await _populate_course_metadata(session_obj, db)

    face_registry.clear_session(str(session_obj.id))
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

    await _populate_course_metadata(session_obj, db)

    logger.info(f"[Audit Finalized] Session: {session_obj.id}, Verified: {session_obj.total_verified}, Spoofers: {session_obj.total_spoofers}, Total Records: {len(payload.records)}")
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
    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")
    if session_obj.status == "CANCELLED":
        raise HTTPException(status_code=400, detail="Attendance session has been cancelled")
    if session_obj.status not in ("ACTIVE", "COMPLETED"):
        raise HTTPException(status_code=400, detail="Attendance session is not active")
    if session_obj.status == "COMPLETED":
        # Allow 90-second grace period for in-flight student game completions and biometric face deduplication
        if session_obj.ended_at and (datetime.now(timezone.utc) - session_obj.ended_at).total_seconds() > 90:
            raise HTTPException(status_code=400, detail="Attendance session has expired")

    # TTL check: Auto-expire active sessions older than 5 minutes
    if _is_session_stale(session_obj.started_at, minutes=5):
        session_obj.status = "EXPIRED"
        session_obj.ended_at = datetime.now(timezone.utc)
        await db.commit()
        raise HTTPException(status_code=400, detail="Attendance session has expired")

    # Layer 2 anti-remote-submission guard (ProjectSummary §1.1):
    # the student's phone must possess the salt distributed ONLY via the
    # teacher's BLE beacon — proof of radio reception at the classroom.
    if payload.session_salt != session_obj.session_salt:
        # Flag the attempted remote submission so it surfaces on the teacher's
        # audit dashboard instead of vanishing as a silent rejection.
        if current_user:
            raw_key = current_user.roll_number or str(current_user.id).replace("-", "")[:4]
        else:
            raw_key = payload.student_id or "STUD"
        claimed_key = raw_key[-4:].upper() if len(raw_key) >= 4 else raw_key.upper()

        # One spoofer flag per claimed device per session (deduplicated across retries)
        spoof_res = await db.execute(
            select(AttendanceRecord).where(
                AttendanceRecord.session_id == session_obj.id,
                AttendanceRecord.ble_device_id == claimed_key,
            )
        )
        if spoof_res.scalars().first() is None:
            db.add(
                AttendanceRecord(
                    id=uuid.uuid4(),
                    session_id=session_obj.id,
                    student_id=current_user.id if current_user else None,
                    ble_device_id=claimed_key,
                    status="SPOOFER_REJECTED",
                    verification_method="SALT_REJECTED",
                    affinity_score=0.00,
                    reported_neighbors=[
                        n[-4:].upper() if len(n) >= 4 else n.upper() for n in payload.neighbors
                    ],
                    remarks="Remote check-in blocked: in-room beacon salt mismatch",
                    recorded_at=datetime.now(timezone.utc),
                )
            )
            await db.commit()

        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Nice try lil bro",
        )

    target_session_id = session_obj.id
    user_id = current_user.id if current_user else None

    # Always use the BLE ID actually transmitted over the radio (payload.student_id)
    raw_key = payload.student_id or (current_user.roll_number if current_user else None) or "STUD"
    student_key = raw_key[-4:].upper() if len(raw_key) >= 4 else raw_key.upper()

    normalized_neighbors = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in payload.neighbors]
    normalized_close = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in (payload.close_neighbors or [])]

    # Face deduplication check (Layer 3: Camera Face Biometric Deduplication)
    face_check_result = None
    if payload.face_image_base64:
        raw_roll = (current_user.roll_number if current_user else None) or payload.student_id or student_key

        # Compile proximity cluster peers: close peers (RSSI >= -65), reported desk neighbors, and co-located peer
        cluster_peers = set(normalized_close)
        cluster_peers.update(normalized_neighbors)
        if payload.co_located_peer_id:
            co_key = payload.co_located_peer_id[-4:].upper() if len(payload.co_located_peer_id) >= 4 else payload.co_located_peer_id.upper()
            cluster_peers.add(co_key)

        face_check_result = face_registry.register_and_check(
            session_id=str(session_obj.id),
            student_key=student_key,
            roll_number=raw_roll,
            face_base64=payload.face_image_base64,
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
            await db.commit()
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
            await db.commit()
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Biometric proxy rejected: {face_check_result.remarks}",
            )

    # Flag divided attention proxy (simultaneous multi-device gameplay)
    if payload.divided_attention_detected:
        remarks = "Divided attention proxy detected (simultaneous multi-device gameplay)"
        logger.warning(f"[Layer 2 Behaviour Proxy] {student_key} divided attention flagged")
        await _record_proxy_violation(
            db=db,
            session_id=session_obj.id,
            device_id=student_key,
            status="DIVIDED_ATTENTION_PROXY",
            verification_method="BEHAVIOUR_ANALYSIS_FAILED",
            remarks=remarks,
            student_id=current_user.id if current_user else None,
            reported_neighbors=normalized_neighbors,
        )
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=remarks,
        )

    # Flag ultrasonic acoustic proximity proxy (< 25 cm)
    if payload.ultrasonic_proximity_detected:
        peer_key = payload.co_located_peer_id or "UNKNOWN"
        remarks_current = f"Ultrasonic proximity rejected: Co-located on same desk (< 25 cm) with {peer_key}"
        remarks_peer = f"Ultrasonic proximity rejected: Co-located on same desk (< 25 cm) with {student_key}"
        logger.warning(f"[Layer 4 Ultrasonic Proxy] {student_key} co-located with {peer_key}")

        if peer_key != "UNKNOWN":
            await _record_proxy_violation(
                db=db,
                session_id=session_obj.id,
                device_id=peer_key,
                status="CO_LOCATED_SAME_DESK_PROXY",
                verification_method="ULTRASONIC_ACOUSTIC_MATCH",
                remarks=remarks_peer,
            )
        await _record_proxy_violation(
            db=db,
            session_id=session_obj.id,
            device_id=student_key,
            status="CO_LOCATED_SAME_DESK_PROXY",
            verification_method="ULTRASONIC_ACOUSTIC_MATCH",
            remarks=remarks_current,
            student_id=current_user.id if current_user else None,
            reported_neighbors=normalized_neighbors,
        )
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=remarks_current,
        )

    # Persist student check-in submission
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

    # Save relayed neighbor reports from offline peers in batch
    if payload.relayed_reports:
        sender_keys = [
            offline_sender[-4:].upper() if len(offline_sender) >= 4 else offline_sender.upper()
            for offline_sender in payload.relayed_reports
        ]
        relay_check = await db.execute(
            select(SessionSubmission.device_key).where(
                SessionSubmission.session_id == target_session_id,
                SessionSubmission.device_key.in_(sender_keys),
            )
        )
        existing_relay_keys = set(relay_check.scalars().all())

        for offline_sender, offline_nbrs in payload.relayed_reports.items():
            sender_key = offline_sender[-4:].upper() if len(offline_sender) >= 4 else offline_sender.upper()
            if sender_key not in existing_relay_keys:
                norm_nbrs = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in offline_nbrs]
                relay_sub = SessionSubmission(
                    session_id=target_session_id,
                    student_id=None,
                    device_key=sender_key,
                    reported_neighbors=norm_nbrs,
                )
                db.add(relay_sub)

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
                verif_method = "DIRECT_MUTUAL"
                affinity = 1.00
                bridge = None
                remarks = f"In-flight check-in verified via mutual desk neighbor [{', '.join(verified_neighbors)}]"
                session_obj.total_verified = (session_obj.total_verified or 0) + 1
            elif normalized_neighbors:
                rec_status = "VERIFIED_PRESENT"
                verif_method = "IN_FLIGHT_GRACE"
                affinity = 0.85
                bridge = None
                remarks = f"In-flight check-in verified during grace period (Reported: [{', '.join(normalized_neighbors)}])"
                session_obj.total_verified = (session_obj.total_verified or 0) + 1
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
            logger.info(f"[In-Flight Grace Audit] Generated {rec_status} record for late arrival {student_key} in session {session_obj.id}")

    await db.commit()

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

    # Execute social-graph audit engine
    audit_results = audit_classroom_mesh(combined_reports)

    # Batch load all prior attendance records for this session
    prior_records_res = await db.execute(
        select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id)
    )
    prior_map = {r.ble_device_id: r for r in prior_records_res.scalars().all()}

    # Batch resolve any unmatched student users
    unresolved_keys = [
        item["ble_device_id"].upper()
        for item in audit_results
        if item["ble_device_id"].upper() not in user_map
    ]
    if unresolved_keys:
        conditions = [User.roll_number.ilike(f"%{k}") for k in unresolved_keys]
        matched_res = await db.execute(select(User).where(or_(*conditions)))
        for u in matched_res.scalars().all():
            if u.roll_number:
                for k in unresolved_keys:
                    if u.roll_number.upper().endswith(k):
                        user_map[k] = u.id

    verified_count = 0
    spoofer_count = 0

    for item in audit_results:
        device_id = item["ble_device_id"].upper()

        # Skip devices already flagged for proxy violations
        prior_rec = prior_map.get(device_id)
        if prior_rec and prior_rec.status in (
            "DUPLICATE_FACE_PROXY",
            "CO_LOCATED_SAME_DESK_PROXY",
            "DIVIDED_ATTENTION_PROXY",
            "NO_FACE_DETECTED",
            "SPOOFER_REJECTED",
        ):
            continue

        if item["status"] == "VERIFIED_PRESENT":
            verified_count += 1
        elif item["status"] == "SPOOFER_REJECTED":
            spoofer_count += 1

        resolved_student_id = user_map.get(device_id)

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

    # Count all spoofers and proxy violators on the session
    spoofer_res = await db.execute(
        select(func.count()).select_from(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.status.in_([
                "SPOOFER_REJECTED",
                "DUPLICATE_FACE_PROXY",
                "CO_LOCATED_SAME_DESK_PROXY",
                "DIVIDED_ATTENTION_PROXY",
                "NO_FACE_DETECTED",
            ]),
        )
    )
    session_obj.total_spoofers = spoofer_res.scalar() or 0

    await db.commit()
    await db.refresh(session_obj)

    await _populate_course_metadata(session_obj, db)

    logger.info(f"[Audit Finalized] Session: {session_obj.id}, Verified: {session_obj.total_verified}, Spoofers: {session_obj.total_spoofers}, Total Records: {len(audit_results)}")
    return session_obj


# Retrieve detailed session information including course and student names
@router.get("/sessions/{session_id}", response_model=AttendanceSessionResponse)
async def get_session_details(
    session_id: str,
    db: AsyncSession = Depends(get_db),
    current_user: Optional[User] = Depends(get_optional_user),
):
    session_obj = await _resolve_session(session_id, db)
    if not session_obj:
        raise HTTPException(status_code=404, detail="Attendance session not found")

    await _populate_course_metadata(session_obj, db)

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


# Check if image contains a real human face
@router.post("/lab/face-check", response_model=FaceLabCheckResponse)
async def lab_face_check(payload: FaceLabCheckRequest):
    try:
        clean_b64 = payload.face_image_base64
        if "," in clean_b64:
            clean_b64 = clean_b64.split(",", 1)[1]
        raw_bytes = base64.b64decode(clean_b64)
        has_face, _, remarks = face_registry.engine.extract_face_and_embedding(raw_bytes)
        return FaceLabCheckResponse(has_face=has_face, remarks=remarks)
    except Exception as e:
        return FaceLabCheckResponse(has_face=False, remarks=f"Processing error: {e}")


# Compare two face images using deep facial embeddings
@router.post("/lab/face-compare", response_model=FaceLabCompareResponse)
async def lab_face_compare(payload: FaceLabCompareRequest):
    try:
        clean_a = payload.image_a_base64.split(",", 1)[1] if "," in payload.image_a_base64 else payload.image_a_base64
        clean_b = payload.image_b_base64.split(",", 1)[1] if "," in payload.image_b_base64 else payload.image_b_base64
        bytes_a = base64.b64decode(clean_a)
        bytes_b = base64.b64decode(clean_b)

        has_face_a, has_face_b, similarity, is_duplicate, remarks = face_registry.engine.compare_two_images(bytes_a, bytes_b)
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

