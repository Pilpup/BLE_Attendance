import uuid
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, JSON, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


# Base declarative class for all SQLAlchemy database models
class Base(DeclarativeBase):
    pass


# User account record for students, teachers, and administrators
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
    # R7: bumped on server-side logout. Every token embeds the account's
    # version at issue time and is rejected on mismatch; the DDL adds the
    # column as NOT NULL DEFAULT 0, and a MISSING claim is treated as v0 so
    # pre-feature tokens keep working after deploy.
    token_version: Mapped[int] = mapped_column(Integer, default=0, nullable=False, server_default="0")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )


# Academic course metadata assigned to instructor
class Course(Base):
    __tablename__ = "courses"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    course_code: Mapped[str] = mapped_column(String(30), unique=True, nullable=False)
    course_name: Mapped[str] = mapped_column(String(200), nullable=False)
    teacher_id: Mapped[Optional[uuid.UUID]] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=True)
    program: Mapped[Optional[str]] = mapped_column(String(30), default="B.Tech", nullable=True)
    department: Mapped[Optional[str]] = mapped_column(String(100), default="Computer Science & Engineering", nullable=True)
    semester: Mapped[Optional[int]] = mapped_column(Integer, default=1, nullable=True)
    category: Mapped[Optional[str]] = mapped_column(String(20), default="DC", nullable=True)
    course_type: Mapped[Optional[str]] = mapped_column(String(20), default="THEORY", nullable=True)
    credits: Mapped[Optional[int]] = mapped_column(Integer, default=4, nullable=True)
    periods_l: Mapped[Optional[int]] = mapped_column(Integer, default=3, nullable=True)
    periods_t: Mapped[Optional[int]] = mapped_column(Integer, default=1, nullable=True)
    periods_p: Mapped[Optional[int]] = mapped_column(Integer, default=0, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        Index("ix_courses_dept_sem", "department", "semester"),
    )

    units: Mapped[list["CourseUnit"]] = relationship(
        back_populates="course", cascade="all, delete-orphan", lazy="selectin"
    )


# Syllabus unit or module for an academic course
class CourseUnit(Base):
    __tablename__ = "course_units"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    course_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("courses.id", ondelete="CASCADE"), index=True, nullable=False
    )
    unit_number: Mapped[int] = mapped_column(Integer, nullable=False)
    unit_title: Mapped[str] = mapped_column(String(255), nullable=False)
    teaching_hours: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        UniqueConstraint("course_id", "unit_number", name="uq_course_unit"),
    )

    course: Mapped["Course"] = relationship(back_populates="units")
    topics: Mapped[list["UnitTopic"]] = relationship(
        back_populates="unit", cascade="all, delete-orphan", lazy="selectin"
    )


# Individual syllabus topic or experiment inside a course unit
class UnitTopic(Base):
    __tablename__ = "unit_topics"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    unit_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("course_units.id", ondelete="CASCADE"), index=True, nullable=False
    )
    topic_order: Mapped[int] = mapped_column(Integer, nullable=False)
    topic_name: Mapped[str] = mapped_column(String, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        UniqueConstraint("unit_id", "topic_order", name="uq_unit_topic"),
    )

    unit: Mapped["CourseUnit"] = relationship(back_populates="topics")


# Attendance session initiated by instructor for class verification
class AttendanceSession(Base):
    __tablename__ = "attendance_sessions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    course_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("courses.id"))
    teacher_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"))
    session_token: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    session_salt: Mapped[Optional[str]] = mapped_column(String(8), nullable=True)
    # Eligibility snapshot ("what class is this session FOR") set once at
    # creation from the teacher's selection or the course row. The student
    # gate compares in-memory at submit — no per-check-in joins.
    eligibility_program: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    eligibility_department: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    eligibility_semester: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String(50), default="ACTIVE")
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    ended_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    total_enrolled: Mapped[int] = mapped_column(default=0)
    total_verified: Mapped[int] = mapped_column(default=0)
    total_spoofers: Mapped[int] = mapped_column(default=0)

    # Composite indexes for instructor session listings and query acceleration
    __table_args__ = (
        Index("ix_attendance_sessions_teacher_started", "teacher_id", "started_at"),
        Index("ix_attendance_sessions_course_started", "course_id", "started_at"),
    )

    # One-to-many relationship linking session to audited student records
    records: Mapped[list["AttendanceRecord"]] = relationship(
        back_populates="session", cascade="all, delete-orphan", lazy="selectin"
    )


# Final audited attendance record for an individual student in a session
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
    reported_neighbors: Mapped[list] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default=list
    )
    remarks: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    recorded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    # Indexes and unique constraints per session
    __table_args__ = (
        Index("ix_attendance_records_session_status", "session_id", "status"),
        Index("ix_attendance_records_session_student", "session_id", "student_id"),
        UniqueConstraint("session_id", "ble_device_id", name="unique_session_ble_device"),
        UniqueConstraint("session_id", "student_id", name="unique_session_student"),
    )

    # Many-to-one relationship linking record back to parent attendance session
    session: Mapped["AttendanceSession"] = relationship(back_populates="records")


# Staging buffer table for client peer reports during active discovery
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


# Short-lived one-time password record for authentication and registration
class EmailOTP(Base):
    __tablename__ = "email_otps"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), index=True, nullable=False)
    otp_code: Mapped[str] = mapped_column(String(10), nullable=False)
    purpose: Mapped[str] = mapped_column(String(30), nullable=False)
    temp_payload: Mapped[Optional[dict]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=True
    )
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
