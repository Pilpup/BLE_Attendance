from datetime import datetime
from typing import Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


# Unit topic schemas
class UnitTopicCreate(BaseModel):
    topic_order: int = Field(..., ge=1, description="Order index of the topic within the unit")
    topic_name: str = Field(..., min_length=1, description="Detailed topic description / experiment title")


class UnitTopicResponse(BaseModel):
    id: UUID
    unit_id: UUID
    topic_order: int
    topic_name: str
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


# Course unit schemas
class CourseUnitCreate(BaseModel):
    unit_number: int = Field(..., ge=1, description="Unit number (1, 2, 3, etc.)")
    unit_title: str = Field(..., min_length=1, max_length=255, description="Title of the unit")
    teaching_hours: Optional[int] = Field(None, ge=0, description="Allocated lecture/lab hours")
    topics: Optional[list[UnitTopicCreate]] = Field(default_factory=list, description="Initial list of topics")


class CourseUnitResponse(BaseModel):
    id: UUID
    course_id: UUID
    unit_number: int
    unit_title: str
    teaching_hours: Optional[int] = None
    created_at: datetime
    topics: list[UnitTopicResponse] = Field(default_factory=list)

    model_config = ConfigDict(from_attributes=True)


# Course creation payload
class CourseCreateRequest(BaseModel):
    course_code: str = Field(..., min_length=2, max_length=30, description="e.g. CST 301")
    course_name: str = Field(..., min_length=2, max_length=200, description="e.g. Cryptography & Network Security")
    program: Optional[str] = Field("B.Tech", description="Academic program e.g. B.Tech")
    department: Optional[str] = Field("Computer Science & Engineering", description="Department name")
    semester: Optional[int] = Field(1, ge=1, le=10, description="Semester number")
    category: Optional[str] = Field("DC", description="DC, DLC, DE, OE, MC, etc.")
    course_type: Optional[str] = Field("THEORY", description="THEORY, LAB, PROJECT, AUDIT")
    credits: Optional[int] = Field(4, ge=0, le=30, description="Course credits")
    periods_l: Optional[int] = Field(3, ge=0, description="Lecture hours/week")
    periods_t: Optional[int] = Field(1, ge=0, description="Tutorial hours/week")
    periods_p: Optional[int] = Field(0, ge=0, description="Practical hours/week")


# Public course response
class CourseResponse(BaseModel):
    id: UUID
    course_code: str
    course_name: str
    teacher_id: Optional[UUID] = None
    program: Optional[str] = "B.Tech"
    department: Optional[str] = "Computer Science & Engineering"
    semester: Optional[int] = 1
    category: Optional[str] = "DC"
    course_type: Optional[str] = "THEORY"
    credits: Optional[int] = 4
    periods_l: Optional[int] = 3
    periods_t: Optional[int] = 1
    periods_p: Optional[int] = 0
    created_at: Optional[datetime] = None
    units: list[CourseUnitResponse] = Field(default_factory=list)

    model_config = ConfigDict(from_attributes=True)


# BLE discovery audit record for a single student node
class BLEAuditRecordItem(BaseModel):
    ble_device_id: str = Field(
        ..., min_length=1, max_length=50, description="BLE ID or Roll Number (e.g. 'A4B1' or '22BCSE104')"
    )
    student_id: Optional[UUID] = Field(None, description="Linked user UUID (optional, set after JWT)")
    status: str = Field(
        ..., description="VERIFIED_PRESENT | SPOOFER_REJECTED | ISOLATED_FLAGGED | FLAGGED_WITNESSED | ABSENT"
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
    game_score: Optional[int] = Field(
        None, description="Chimp cognitive challenge score (client-attested, gated at submit)"
    )
    roll_number: Optional[str] = None
    student_name: Optional[str] = None


# Persisted attendance record response including session metadata
class AttendanceRecordResponse(BLEAuditRecordItem):
    id: UUID
    session_id: UUID
    recorded_at: datetime

    model_config = ConfigDict(from_attributes=True)


# Canonical status enums for request validation (docs of record: BLEAuditRecordItem / RecordOverrideRequest docstrings)
_ALLOWED_OVERRIDE_STATUSES = {"VERIFIED_PRESENT", "ABSENT", "ISOLATED_FLAGGED", "LEAVE"}
_ALLOWED_FINALIZE_STATUSES = {"VERIFIED_PRESENT", "ABSENT", "ISOLATED_FLAGGED", "SPOOFER_REJECTED"}


# Manual attendance status override request by instructor
class RecordOverrideRequest(BaseModel):
    status: str = Field(
        default="VERIFIED_PRESENT",
        description="New attendance status: VERIFIED_PRESENT | ABSENT | ISOLATED_FLAGGED | LEAVE",
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
            raise ValueError("status must be one of: VERIFIED_PRESENT | ABSENT | ISOLATED_FLAGGED | LEAVE")
        return normalized


# Session creation request payload
class SessionCreateRequest(BaseModel):
    course_id: Optional[str] = Field(
        None, description="Course UUID or synthetic code. Leave null to use the default demo course"
    )
    course_code: Optional[str] = Field(None, max_length=50)
    course_name: Optional[str] = Field(None, max_length=200)
    session_token: str = Field(..., max_length=32, description="Ephemeral token for this class")
    # H5: server-generated; client-supplied value is ignored.
    session_salt: Optional[str] = Field(
        default=None,
        max_length=64,
        description="Ignored on create (server generates the canonical salt).",
    )
    total_enrolled: int = Field(default=0, ge=0)
    # Teacher-declared class identity for the eligibility gate. Preferred over
    # the course-row snapshot so a freshly auto-created course cannot bind the
    # session to the wrong semester.
    course_program: Optional[str] = Field(None, max_length=30)
    course_semester: Optional[int] = Field(None, ge=1, le=10)


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
    # [ULTRASONIC-DISABLED 2026-11] Layer 4 fields removed from the intake
    # contract. Old APKs that still send these JSON keys are unaffected
    # (pydantic ignores unknown extras). To restore, uncomment both fields and
    # the app-side call sites.
    # ultrasonic_proximity_detected: Optional[bool] = Field(
    #     default=None,
    #     description="Whether an ultrasonic handshake (<25cm) was detected with a neighbor device"
    # )
    # co_located_peer_id: Optional[str] = Field(
    #     default=None,
    #     description="The peer student ID detected within ultrasonic range on the same desk"
    # )
    challenge_report: Optional[dict] = Field(
        default=None,
        description="Client-computed challenge report (e.g. chimp_score). Used for challenge integrity checks."
    )
    game_score: Optional[int] = Field(
        default=None,
        description="Chimp memory challenge score; <15 is rejected as unverified."
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
    # H5: canonical salt (server-generated, high entropy) — teacher keeps it
    # to derive/refresh wire tokens. Wire token = current BLE beacon salt.
    session_salt: Optional[str] = None
    session_wire_salt: Optional[str] = None
    status: str
    started_at: datetime
    ended_at: Optional[datetime] = None
    total_enrolled: int
    total_verified: int
    total_unverified: int
    records: Optional[list[AttendanceRecordResponse]] = None
    course_code: Optional[str] = None
    course_name: Optional[str] = None
    eligibility_program: Optional[str] = None
    eligibility_department: Optional[str] = None
    eligibility_semester: Optional[int] = None
    # Roster diagnostic (session create only): the roll numbers whose active
    # STUDENT accounts were counted into total_enrolled. Not persisted.
    enrolled_roll_numbers: Optional[list[str]] = None

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
