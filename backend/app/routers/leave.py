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


# Serializes a LeaveApplication row; student details join in only for the
# department queue view (students already know who they are)
def _serialize_application(
    app_obj: LeaveApplication,
    student: Optional[User],
    can_review: bool,
) -> dict:
    return {
        "id": str(app_obj.id),
        "student_id": str(app_obj.student_id),
        "student_name": student.full_name if student else None,
        "student_roll": student.roll_number if student else None,
        "department": app_obj.department,
        "from_date": app_obj.from_date.isoformat(),
        "to_date": app_obj.to_date.isoformat(),
        "reason": app_obj.reason,
        "status": app_obj.status,
        "review_comment": app_obj.review_comment,
        "reviewed_at": app_obj.reviewed_at.isoformat() if app_obj.reviewed_at else None,
        "created_at": app_obj.created_at.isoformat() if app_obj.created_at else None,
        "can_review": can_review,
    }


# Student submits a leave application: routed by their account's department.
# The range itself IS the duration (explicit date pickers, no text parsing);
# the finalize overlay (attendance marking) consumes approved ranges later.
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

    application = LeaveApplication(
        student_id=current_user.id,
        department=department,
        from_date=payload.from_date,
        to_date=payload.to_date,
        reason=payload.reason,
    )
    db.add(application)
    await db.commit()
    await db.refresh(application)
    return _serialize_application(application, current_user, can_review=False)


# Student's own applications, newest first (submission history + verdicts)
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
    return [
        _serialize_application(app_obj, current_user, can_review=False)
        for app_obj in res.scalars().all()
    ]


# Department queue. TEACHER and HOD of the department both see it (read-only
# for teachers); only the response's can_review flag differs. Verdicts go
# through the review endpoint, which is HOD-gated.
@router.get("/applications", response_model=List[LeaveApplicationResponse])
async def list_department_leave_applications(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_teacher),
):
    department = _normalize_department(current_user.department)
    if not department:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Set your department to view leave applications",
        )
    is_hod = (current_user.role or "").upper() == "HOD"
    res = await db.execute(
        select(LeaveApplication, User)
        .join(User, User.id == LeaveApplication.student_id)
        .where(LeaveApplication.department == department)
        .order_by(LeaveApplication.created_at.desc())
        .limit(200)
    )
    return [
        _serialize_application(app_obj, student, can_review=is_hod)
        for app_obj, student in res.all()
    ]


# HOD verdict. ONLY role='HOD' can approve/reject (teachers of the department
# are view-only per design); the application must belong to the HOD's own
# department and still be PENDING.
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

    # Atomic PENDING→verdict transition: the WHERE status='PENDING' clause
    # closes the read-then-write race where two concurrent HOD reviews both
    # pass the stale PENDING check above and the second commit silently
    # overwrites the first verdict. rowcount==0 ⇒ someone else decided first.
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

    student = (
        await db.execute(select(User).where(User.id == app_obj.student_id))
    ).scalars().first()
    return _serialize_application(app_obj, student, can_review=True)
