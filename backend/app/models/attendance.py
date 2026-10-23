import uuid
from datetime import datetime, timezone
from typing import List, Optional

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, JSON, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255), nullable=False)
    full_name: Mapped[str] = mapped_column(String(120), nullable=False)
    roll_number: Mapped[Optional[str]] = mapped_column(String(50), unique=True, nullable=True, index=True)
    role: Mapped[str] = mapped_column(String(50), default="STUDENT")
    department: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)
    program: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)
    semester: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    section: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )


class Course(Base):
    __tablename__ = "courses"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    course_code: Mapped[str] = mapped_column(String(30), unique=True, nullable=False)
    course_name: Mapped[str] = mapped_column(String(200), nullable=False)
    teacher_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"))


class AttendanceSession(Base):
    __tablename__ = "attendance_sessions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    course_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("courses.id"))
    teacher_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"))
    session_token: Mapped[str] = mapped_column(String(32), nullable=False)
    # Layer 2 anti-remote-submission salt, distributed only via the teacher's BLE beacon.
    # Nullable in DB (pre-salt legacy rows); enforced as required at the API layer.
    session_salt: Mapped[Optional[str]] = mapped_column(String(8), nullable=True)
    status: Mapped[str] = mapped_column(String(50), default="ACTIVE")
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    ended_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    total_enrolled: Mapped[int] = mapped_column(default=0)
    total_verified: Mapped[int] = mapped_column(default=0)
    total_spoofers: Mapped[int] = mapped_column(default=0)

    # Relationship: one session → many records
    records: Mapped[List["AttendanceRecord"]] = relationship(
        back_populates="session", cascade="all, delete-orphan", lazy="selectin"
    )


class AttendanceRecord(Base):
    __tablename__ = "attendance_records"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("attendance_sessions.id")
    )
    student_id: Mapped[Optional[uuid.UUID]] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=True
    )
    ble_device_id: Mapped[str] = mapped_column(String(50), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(50), default="ABSENT")
    verification_method: Mapped[str] = mapped_column(String(50), default="NONE")
    affinity_score: Mapped[float] = mapped_column(Numeric(3, 2), default=0.00)
    bridge_device_id: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)
    reported_neighbors: Mapped[list] = mapped_column(JSONB, default=list)
    remarks: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    recorded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    # Relationship: many records → one session
    session: Mapped["AttendanceSession"] = relationship(back_populates="records")


class SessionSubmission(Base):
    __tablename__ = "session_submissions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("attendance_sessions.id", ondelete="CASCADE"), index=True, nullable=False
    )
    student_id: Mapped[Optional[uuid.UUID]] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    device_key: Mapped[str] = mapped_column(String(50), nullable=False)
    reported_neighbors: Mapped[list] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default=list
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        UniqueConstraint("session_id", "device_key", name="uq_session_submission_device"),
    )


class EmailOTP(Base):
    __tablename__ = "email_otps"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    otp_code: Mapped[str] = mapped_column(String(10), nullable=False)
    purpose: Mapped[str] = mapped_column(String(30), nullable=False)  # "REGISTER" or "LOGIN"
    temp_payload: Mapped[Optional[dict]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=True
    )
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

