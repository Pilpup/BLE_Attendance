from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import require_student, require_teacher
from app.database import get_db
from app.models.attendance import LeaveApplication, User
from app.schemas.leave import (
    LeaveApplicationResponse,
    LeaveCreateRequest,
    LeaveReviewRequest,
)

router = APIRouter(prefix="/leave", tags=["Leave"])


# Departments are stored uppercased so queue matching (student -> HOD) is exact
def _normalize_department(raw: Optional[str]) -> str:
    return (raw or "").strip().upper()


# Serializes a LeaveApplication row. The v2 contract names (user_id /
# applicant_reason / reviewer_feedback) mirror the legacy columns for
# compatibility.
def _serialize_application(
    app_obj: LeaveApplication,
    student: Optional[User],
    can_review: bool,
    reviewer: Optional[User] = None,
) -> dict:
    return {
        "id": str(app_obj.id),
        "user_id": str(app_obj.student_id),
        "student_id": str(app_obj.student_id),
        "student_name": student.full_name if student else None,
        "student_roll": student.roll_number if student else None,
        "department": app_obj.department,
        "from_date": app_obj.from_date.isoformat(),
        "to_date": app_obj.to_date.isoformat(),
        "applicant_reason": app_obj.reason,
        "reason": app_obj.reason,
        "status": app_obj.status,
        "reviewer_feedback": app_obj.review_comment,
        "review_comment": app_obj.review_comment,
        "reviewer_name": reviewer.full_name if reviewer else None,
        "reviewed_at": app_obj.reviewed_at.isoformat() if app_obj.reviewed_at else None,
        "created_at": app_obj.created_at.isoformat() if app_obj.created_at else None,
        "can_review": can_review,
    }


async def _get_user(db: AsyncSession, user_id) -> Optional[User]:
    return (
        await db.execute(select(User).where(User.id == user_id))
    ).scalars().first()


# Student submits a leave application: routed by their account's department.
# The range itself IS the duration (whole calendar days, inclusive).
#
# Overlap prevention: a new request may not intersect [from, to] of one of the
# student's own PENDING or APPROVED requests.
@router.post("/applications", response_model=LeaveApplicationResponse, status_code=201)
async def submit_leave_application(
    payload: LeaveCreateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_student),
):
    department = _normalize_department(current_user.department)
    if not department:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Your account has no department set — ask an ADMIN to set it before applying for leave",
        )

    overlapping = await db.execute(
        select(LeaveApplication)
        .where(
            LeaveApplication.student_id == current_user.id,
            LeaveApplication.status.in_(["PENDING", "APPROVED"]),
            LeaveApplication.from_date <= payload.to_date,
            LeaveApplication.to_date >= payload.from_date,
        )
        .limit(1)
    )
    clash = overlapping.scalars().first()
    if clash:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "You already have a {status} leave from {from_date} to {to_date} that "
                "overlaps these dates".format(
                    status=clash.status.lower(),
                    from_date=clash.from_date.isoformat(),
                    to_date=clash.to_date.isoformat(),
                )
            ),
        )

    application = LeaveApplication(
        student_id=current_user.id,
        department=department,
        from_date=payload.from_date,
        to_date=payload.to_date,
        reason=payload.applicant_reason or payload.reason or "",
    )
    db.add(application)
    await db.commit()
    await db.refresh(application)
    return _serialize_application(application, current_user, can_review=False)


# Student's own applications, newest first (submission history + verdicts).
# The reviewer joins in so the tracker can show WHO decided.
@router.get("/applications/mine", response_model=List[LeaveApplicationResponse])
async def list_my_leave_applications(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_student),
):
    res = await db.execute(
        select(LeaveApplication)
        .where(LeaveApplication.student_id == current_user.id)
        .order_by(LeaveApplication.created_at.desc())
        .limit(200)
    )
    applications = res.scalars().all()

    # One batched lookup for every reviewer involved (avoids N+1)
    reviewer_ids = {a.reviewed_by for a in applications if a.reviewed_by}
    reviewers: dict = {}
    if reviewer_ids:
        res_reviewers = await db.execute(select(User).where(User.id.in_(reviewer_ids)))
        reviewers = {u.id: u for u in res_reviewers.scalars().all()}

    return [
        _serialize_application(
            app_obj,
            current_user,
            can_review=False,
            reviewer=reviewers.get(app_obj.reviewed_by),
        )
        for app_obj in applications
    ]


# Department queue for the HOD only. All leave applications are reviewed by the
# department HOD (not by teachers).
@router.get("/applications", response_model=List[LeaveApplicationResponse])
async def list_department_leave_applications(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    if (current_user.role or "").upper() != "HOD":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the HOD can view leave applications",
        )
    department = _normalize_department(current_user.department)
    if not department:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Set your department to view leave applications",
        )
    res = await db.execute(
        select(LeaveApplication, User)
        .join(User, User.id == LeaveApplication.student_id)
        .where(LeaveApplication.department == department)
        .order_by(LeaveApplication.created_at.desc())
        .limit(200)
    )
    return [
        _serialize_application(app_obj, student, can_review=True)
        for app_obj, student in res.all()
    ]


# Student withdrawal: allowed ONLY while the request is still PENDING. The
# atomic status guard closes the race with a concurrent verdict.
@router.post(
    "/applications/{application_id}/cancel",
    response_model=LeaveApplicationResponse,
)
async def cancel_leave_application(
    application_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_student),
):
    app_obj = (
        await db.execute(select(LeaveApplication).where(LeaveApplication.id == application_id))
    ).scalars().first()
    if not app_obj:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Leave application not found",
        )
    if app_obj.student_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only cancel your own leave applications",
        )

    cancel_result = await db.execute(
        update(LeaveApplication)
        .where(LeaveApplication.id == application_id, LeaveApplication.status == "PENDING")
        .values(status="CANCELLED")
    )
    if cancel_result.rowcount == 0:
        await db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only pending applications can be cancelled",
        )
    await db.commit()
    await db.refresh(app_obj)
    return _serialize_application(app_obj, current_user, can_review=False)


# HOD verdict. ONLY role='HOD' can approve/reject; the application must belong
# to the HOD's own department and still be PENDING. Feedback is optional on
# APPROVED and mandatory on REJECTED (enforced in LeaveReviewRequest).
@router.post(
    "/applications/{application_id}/review",
    response_model=LeaveApplicationResponse,
)
async def review_leave_application(
    application_id: uuid.UUID,
    payload: LeaveReviewRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    if (current_user.role or "").upper() != "HOD":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the HOD can approve or reject leave applications",
        )
    hod_department = _normalize_department(current_user.department)
    if not hod_department:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Set your department to review leave applications",
        )

    app_obj = (
        await db.execute(select(LeaveApplication).where(LeaveApplication.id == application_id))
    ).scalars().first()
    if not app_obj:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Leave application not found",
        )
    if app_obj.department != hod_department:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This application belongs to another department",
        )
    if app_obj.status != "PENDING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Application already {app_obj.status}",
        )

    # Atomic PENDING→verdict transition: rowcount==0 ⇒ someone else decided first.
    verdict_result = await db.execute(
        update(LeaveApplication)
        .where(LeaveApplication.id == application_id, LeaveApplication.status == "PENDING")
        .values(
            status=payload.decision,
            reviewed_by=current_user.id,
            reviewed_at=datetime.now(timezone.utc),
            review_comment=payload.comment,
        )
    )
    if verdict_result.rowcount == 0:
        await db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Application already decided by another reviewer",
        )
    await db.commit()
    await db.refresh(app_obj)

    student = await _get_user(db, app_obj.student_id)
    return _serialize_application(app_obj, student, can_review=True, reviewer=current_user)
