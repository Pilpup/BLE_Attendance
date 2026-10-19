from datetime import datetime
from typing import List, Optional
from uuid import UUID

from pydantic import BaseModel, Field


# ─────────────────────────────────────────────
# Course
# ─────────────────────────────────────────────
class CourseResponse(BaseModel):
    id: UUID
    course_code: str
    course_name: str
    teacher_id: UUID

    class Config:
        from_attributes = True


# ─────────────────────────────────────────────
# BLE Audit Record (one student's mesh result)
# ─────────────────────────────────────────────
class BLEAuditRecordItem(BaseModel):
    ble_device_id: str = Field(
        ..., min_length=4, max_length=4, description="4-char hex ID from Android (e.g. 'A4B1')"
    )
    student_id: Optional[UUID] = Field(None, description="Linked user UUID (optional, set after JWT)")
    status: str = Field(
        ..., description="VERIFIED_PRESENT | SPOOFER_REJECTED | ISOLATED_FLAGGED | ABSENT"
    )
    verification_method: str = Field(
        default="NONE", description="DIRECT_MUTUAL | TRIADIC_CLOSURE | MANUAL_OVERRIDE | NONE"
    )
    affinity_score: float = Field(
        default=0.0, ge=0.0, le=1.0,
        description="1.00 = Direct Mutual, 0.50 = Triadic Bridge, 0.00 = Rogue/Absent"
    )
    bridge_device_id: Optional[str] = Field(
        None, min_length=4, max_length=4,
        description="Bridge node ID for TRIADIC_CLOSURE verifications"
    )
    reported_neighbors: List[str] = Field(
        default_factory=list, description="Top-3 observed peer IDs from BLE discovery"
    )
    remarks: Optional[str] = Field(None, description="Human-readable audit note")


class AttendanceRecordResponse(BLEAuditRecordItem):
    id: UUID
    session_id: UUID
    recorded_at: datetime

    class Config:
        from_attributes = True


# ─────────────────────────────────────────────
# Session
# ─────────────────────────────────────────────
class SessionCreateRequest(BaseModel):
    course_id: Optional[UUID] = Field(
        None, description="Leave null to use the default demo course"
    )
    session_token: str = Field(..., max_length=32, description="Ephemeral token for this class")
    total_enrolled: int = Field(default=0, ge=0)


class SessionFinalizeRequest(BaseModel):
    records: List[BLEAuditRecordItem] = Field(
        ..., description="Full mesh audit results from performMeshAudit() on the teacher's phone"
    )


class AttendanceSessionResponse(BaseModel):
    id: UUID
    course_id: UUID
    teacher_id: UUID
    session_token: str
    status: str
    started_at: datetime
    ended_at: Optional[datetime] = None
    total_enrolled: int
    total_verified: int
    total_spoofers: int
    records: Optional[List[AttendanceRecordResponse]] = None

    class Config:
        from_attributes = True