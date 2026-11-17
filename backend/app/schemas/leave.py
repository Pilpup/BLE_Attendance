from datetime import date, datetime
from typing import Literal, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

# Hard cap so a stray submit can't create a semester-long range by accident
MAX_LEAVE_SPAN_DAYS = 90

# Applicant remark limits (the form shows a live counter against these)
MIN_REASON_LENGTH = 3
MAX_REASON_LENGTH = 1000


# Student request to apply for leave: explicit date range + written reason.
# `applicant_reason` is the v2 contract name; `reason` stays accepted for
# compatibility with older clients.
class LeaveCreateRequest(BaseModel):
    from_date: date
    to_date: date
    applicant_reason: Optional[str] = Field(default=None, max_length=MAX_REASON_LENGTH)
    reason: Optional[str] = Field(default=None, max_length=MAX_REASON_LENGTH)

    # Strips surrounding whitespace from whichever reason field was used
    @field_validator("applicant_reason", "reason", mode="before")
    @classmethod
    def clean_reason(cls, v):
        if isinstance(v, str):
            return v.strip()
        return v

    # Range must be well-formed, bounded, and have a valid reason
    @model_validator(mode="after")
    def validate_request(self):
        # Effective reason: v2 field wins, legacy field is the fallback
        reason = self.applicant_reason or self.reason
        if not reason or len(reason) < MIN_REASON_LENGTH:
            raise ValueError(
                f"Reason must be at least {MIN_REASON_LENGTH} characters"
            )
        self.applicant_reason = reason

        if self.to_date < self.from_date:
            raise ValueError("to_date must be on or after from_date")
        span_days = (self.to_date - self.from_date).days
        if span_days > MAX_LEAVE_SPAN_DAYS:
            raise ValueError(f"Leave span cannot exceed {MAX_LEAVE_SPAN_DAYS} days")
        return self


# HOD verdict on a pending application. Feedback is OPTIONAL when approving,
# MANDATORY when rejecting — the applicant must be able to read why.
class LeaveReviewRequest(BaseModel):
    decision: Literal["APPROVED", "REJECTED"]
    comment: Optional[str] = Field(default=None, max_length=500)

    # Strips surrounding whitespace from the optional review comment
    @field_validator("comment", mode="before")
    @classmethod
    def clean_comment(cls, v: Optional[str]) -> Optional[str]:
        if isinstance(v, str):
            stripped = v.strip()
            return stripped or None
        return v

    @model_validator(mode="after")
    def require_feedback_on_rejection(self):
        if self.decision == "REJECTED" and not self.comment:
            raise ValueError("Reviewer feedback is required when rejecting a leave request")
        return self


# One leave application as seen by students and the HOD.
# v2 contract names (user_id / applicant_reason / reviewer_feedback) are
# surfaced alongside the legacy keys for compatibility.
class LeaveApplicationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    # v2 contract name — same value as student_id
    user_id: Optional[UUID] = None
    student_id: UUID
    student_name: Optional[str] = None
    student_roll: Optional[str] = None
    department: str
    from_date: date
    to_date: date
    # v2 contract name — same value as reason
    applicant_reason: Optional[str] = None
    reason: str
    status: str
    # v2 contract name — same value as review_comment
    reviewer_feedback: Optional[str] = None
    review_comment: Optional[str] = None
    reviewer_name: Optional[str] = None
    reviewed_at: Optional[datetime] = None
    created_at: Optional[datetime] = None
    # True only for the department HOD viewing the queue
    can_review: bool = False
