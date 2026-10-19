from datetime import datetime
from typing import List, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


# ─────────────────────────────────────────────
# Course
# ─────────────────────────────────────────────
class CourseCreateRequest(BaseModel):
    course_code: str = Field(..., min_length=2, max_length=20, description="e.g. CS401")
    course_name: str = Field(..., min_length=2, max_length=100, description="e.g. Distributed Systems")


class CourseResponse(BaseModel):
    id: UUID
    course_code: str
    course_name: str
    teacher_id: UUID

    model_config = ConfigDict(from_attributes=True)


# ─────────────────────────────────────────────
# BLE Audit Record (one student's mesh result)
# ─────────────────────────────────────────────
class BLEAuditRecordItem(BaseModel):
    ble_device_id: str = Field(
        ..., min_length=1, max_length=50, description="BLE ID or Roll Number (e.g. 'A4B1' or '22BCSE104')"
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
        None, min_length=1, max_length=50,
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

    model_config = ConfigDict(from_attributes=True)


class RecordOverrideRequest(BaseModel):
    status: str = Field(
        default="VERIFIED_PRESENT",
        description="New attendance status: VERIFIED_PRESENT | ABSENT | ISOLATED_FLAGGED",
    )
    remarks: Optional[str] = Field(
        default="Manual override by instructor",
        description="Reason for manual override",
    )


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


class MeshAuditRunRequest(BaseModel):
    reports: dict[str, List[str]] = Field(
        ...,
        description="Classroom BLE discovery dictionary: {student_ble_id: [top_3_neighbor_ids]}"
    )


class SubmitNeighborsRequest(BaseModel):
    student_id: Optional[str] = Field(
        default=None,
        description="Student roll number or 4-char BLE ID if unauthenticated"
    )
    neighbors: List[str] = Field(
        ...,
        description="Top-3 discovered peer IDs or roll numbers (median RSSI >= -70 dBm)"
    )
    relayed_reports: Optional[dict[str, List[str]]] = Field(
        default=None,
        description="Vouchers collected from offline peers over BLE: {offline_student_id: [nbr1, nbr2, nbr3]}"
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

    model_config = ConfigDict(from_attributes=True)