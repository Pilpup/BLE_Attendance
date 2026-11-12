from datetime import date, datetime
from typing import Literal, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

# Hard cap so a stray submit can't create a semester-long range by accident
MAX_LEAVE_SPAN_DAYS = 90


# Student request to apply for leave: explicit date range + written reason
class LeaveCreateRequest(BaseModel):
    from_date: date
    to_date: date
    reason: str = Field(min_length=3, max_length=2000)

    # Strips surrounding whitespace from the written reason
    @field_validator("reason", mode="before")
    @classmethod
    def clean_reason(cls, v: str) -> str:
        if isinstance(v, str):
            return v.strip()
        return v

    # The range must be well-formed and bounded
    @model_validator(mode="after")
    def validate_range(self):
        if self.to_date < self.from_date:
            raise ValueError("to_date must be on or after from_date")
        span_days = (self.to_date - self.from_date).days
        if span_days > MAX_LEAVE_SPAN_DAYS:
            raise ValueError(f"Leave span cannot exceed {MAX_LEAVE_SPAN_DAYS} days")
        return self


# HOD verdict on a pending application
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


# One leave application as seen by students, department teachers, and the HOD
class LeaveApplicationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    student_id: UUID
    student_name: Optional[str] = None
    student_roll: Optional[str] = None
    department: str
    from_date: date
    to_date: date
    reason: str
    status: str
    review_comment: Optional[str] = None
    reviewed_at: Optional[datetime] = None
    created_at: Optional[datetime] = None
    # True only for the department HOD viewing the queue — the app shows
    # Approve/Reject controls only when this flag is set
    can_review: bool = False
