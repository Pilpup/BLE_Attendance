import uuid
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.attendance import Base


# Library catalogue entry storing book metadata and physical inventory counts
class Book(Base):
    __tablename__ = "books"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    title: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    author: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    isbn: Mapped[str] = mapped_column(String(32), unique=True, nullable=False)
    category: Mapped[str] = mapped_column(String(50), nullable=False, index=True)
    total_copies: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    available_copies: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    shelf_location: Mapped[str] = mapped_column(String(100), nullable=False, default="Main Hall")
    edition: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    overview: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    table_of_contents: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        CheckConstraint(
            "available_copies >= 0 AND available_copies <= total_copies",
            name="ck_books_copies",
        ),
    )


# Active or fulfilled library book reservation held by a user
class BookReservation(Base):
    __tablename__ = "book_reservations"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    book_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("books.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    status: Mapped[str] = mapped_column(String(30), nullable=False, default="ACTIVE")
    reserved_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        CheckConstraint(
            "status IN ('ACTIVE', 'FULFILLED', 'CANCELLED')",
            name="ck_book_reservations_status",
        ),
    )
