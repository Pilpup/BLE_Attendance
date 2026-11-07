from datetime import datetime
from typing import Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


# Course creation payload
class CourseCreateRequest(BaseModel):
    course_code: str = Field(..., min_length=2, max_length=20, description="e.g. CS401")
    course_name: str = Field(..., min_length=2, max_length=100, description="e.g. Distributed Systems")


# Public course response
class CourseResponse(BaseModel):
    id: UUID
    course_code: str
    course_name: str
    teacher_id: UUID

    model_config = ConfigDict(from_attributes=True)


# BLE discovery audit record for a single student node
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
    reported_neighbors: list[str] = Field(
        default_factory=list, description="Top-3 observed peer IDs from BLE discovery"
    )
    remarks: Optional[str] = Field(None, description="Human-readable audit note")
    roll_number: Optional[str] = None
    student_name: Optional[str] = None


# Persisted attendance record response including session metadata
class AttendanceRecordResponse(BLEAuditRecordItem):
    id: UUID
    session_id: UUID
    recorded_at: datetime

    model_config = ConfigDict(from_attributes=True)


# Canonical status enums for request validation (docs of record: BLEAuditRecordItem / RecordOverrideRequest docstrings)
_ALLOWED_OVERRIDE_STATUSES = {"VERIFIED_PRESENT", "ABSENT", "ISOLATED_FLAGGED"}
_ALLOWED_FINALIZE_STATUSES = {"VERIFIED_PRESENT", "ABSENT", "ISOLATED_FLAGGED", "SPOOFER_REJECTED"}


# Manual attendance status override request by instructor
class RecordOverrideRequest(BaseModel):
    status: str = Field(
        default="VERIFIED_PRESENT",
        description="New attendance status: VERIFIED_PRESENT | ABSENT | ISOLATED_FLAGGED",
    )
    remarks: Optional[str] = Field(
        default="Manual override by instructor",
        description="Reason for manual override",
    )

    @field_validator("status", mode="before")
    def normalize_status(cls, v):
        # Strict whitelist: an unknown status on the override route would write
        # an unclassifiable row the teacher dashboard cannot render.
        normalized = str(v if v is not None else "").strip().upper()
        if normalized not in _ALLOWED_OVERRIDE_STATUSES:
            raise ValueError("status must be one of: VERIFIED_PRESENT | ABSENT | ISOLATED_FLAGGED")
        return normalized


# Session creation request payload
class SessionCreateRequest(BaseModel):
    course_id: Optional[str] = Field(
        None, description="Course UUID or synthetic code. Leave null to use the default demo course"
    )
    course_code: Optional[str] = Field(None, max_length=50)
    course_name: Optional[str] = Field(None, max_length=200)
    session_token: str = Field(..., max_length=32, description="Ephemeral token for this class")
    session_salt: str = Field(
        ...,
        min_length=4,
        max_length=8,
        description="4-char salt distributed ONLY via the teacher's BLE beacon (S:<ID>:<SALT>)",
    )
    total_enrolled: int = Field(default=0, ge=0)


# Session finalization request containing complete mesh audit records
class SessionFinalizeRequest(BaseModel):
    records: list[BLEAuditRecordItem] = Field(
        ..., description="Full mesh audit results from performMeshAudit() on the teacher's phone"
    )

    @field_validator("records", mode="before")
    def validate_record_statuses(cls, records):
        # Strict whitelist on the OVERWRITE route so the teacher's finalize
        # payload can never stamp an unclassifiable status onto records.
        # (Kept off BLEAuditRecordItem: it is the from_attributes base of
        # AttendanceSessionResponse and would 500 sessions containing
        # violation rows produced by other defense layers.)
        if not isinstance(records, list):
            return records
        cleaned: list = []
        for item in records:
            if isinstance(item, dict) and "status" in item:
                normalized = str(item.get("status") or "").strip().upper()
                if normalized not in _ALLOWED_FINALIZE_STATUSES:
                    raise ValueError("record status must be one of: VERIFIED_PRESENT | ABSENT | ISOLATED_FLAGGED | SPOOFER_REJECTED")
                item = {**item, "status": normalized}
            cleaned.append(item)
        return cleaned


# Teacher trigger payload to run server-side mesh audit
class MeshAuditRunRequest(BaseModel):
    reports: dict[str, list[str]] = Field(
        default_factory=dict,
        description="Classroom BLE discovery dictionary: {student_ble_id: [top_3_neighbor_ids]}"
    )


# Student check-in payload with BLE neighbors, biometric and telemetry proofs
class SubmitNeighborsRequest(BaseModel):
    student_id: Optional[str] = Field(
        default=None,
        description="Student roll number or 4-char BLE ID if unauthenticated"
    )
    neighbors: list[str] = Field(
        ...,
        description="Top-3 discovered peer IDs or roll numbers (median RSSI >= -82 dBm)"
    )
    close_neighbors: Optional[list[str]] = Field(
        default=None,
        description="Peers detected in close proximity cluster (RSSI >= -65 dBm)"
    )
    session_salt: Optional[str] = Field(
        default=None,
        description="Salt captured from the teacher's in-room beacon; submissions without the matching salt are rejected"
    )
    face_image_base64: Optional[str] = Field(
        default=None,
        description="Base64-encoded cropped front-camera face image captured during check-in"
    )
    ultrasonic_proximity_detected: Optional[bool] = Field(
        default=None,
        description="Whether an ultrasonic handshake (<25cm) was detected with a neighbor device"
    )
    co_located_peer_id: Optional[str] = Field(
        default=None,
        description="The peer student ID detected within ultrasonic range on the same desk"
    )

    # Strips whitespace from student identifier
    @field_validator("student_id", mode="before")
    @classmethod
    def clean_student_id(cls, v: Optional[str]) -> Optional[str]:
        if isinstance(v, str):
            return v.strip()
        return v

    # Strips whitespace from session salt
    @field_validator("session_salt", mode="before")
    @classmethod
    def clean_salt(cls, v: Optional[str]) -> Optional[str]:
        if isinstance(v, str):
            return v.strip()
        return v


# Active or completed attendance session response with audit totals
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
    records: Optional[list[AttendanceRecordResponse]] = None
    course_code: Optional[str] = None
    course_name: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


# Developer lab test request for face detection validation
class FaceLabCheckRequest(BaseModel):
    face_image_base64: str


# Developer lab test response for face detection verification
class FaceLabCheckResponse(BaseModel):
    has_face: bool
    remarks: str


# Developer lab test request comparing two facial images
class FaceLabCompareRequest(BaseModel):
    image_a_base64: str
    image_b_base64: str


# Developer lab test response with facial similarity metrics
class FaceLabCompareResponse(BaseModel):
    has_face_a: bool
    has_face_b: bool
    similarity_score: float
    is_duplicate: bool
    remarks: str
