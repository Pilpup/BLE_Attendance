from __future__ import annotations

import uuid
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import get_current_user, require_staff_or_dev
from app.database import get_db
from app.models.library import Book, BookReservation
from app.models.attendance import User
from app.schemas.library import BookCreateRequest, BookResponse, BookUpdateRequest

router = APIRouter(prefix="/library", tags=["Library"])


def _normalize_isbn(raw: str) -> str:
    """Matches the Android client: trim, drop hyphens, uppercase."""
    return raw.strip().replace("-", "").upper()


def _normalize_category(raw: str) -> str:
    return raw.strip().upper()


# ─────────────────────────────────────────────
# Catalogue browsing — ALL authenticated roles
# ─────────────────────────────────────────────
@router.get("/books", response_model=List[BookResponse])
async def list_books(
    search: Optional[str] = Query(None, description="Matches title, author or ISBN"),
    category: Optional[str] = Query(None, description="Exact category, case-insensitive (CS, MATH…)"),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    query = select(Book)

    if search and search.strip():
        pattern = f"%{search.strip()}%"
        query = query.where(
            or_(
                Book.title.ilike(pattern),
                Book.author.ilike(pattern),
                Book.isbn.ilike(pattern),
            )
        )

    if category and category.strip():
        query = query.where(func.lower(Book.category) == category.strip().lower())

    query = query.order_by(Book.created_at.desc(), Book.title.asc())
    result = await db.execute(query)
    return result.scalars().all()


# ─────────────────────────────────────────────
# Inventory management — STAFF / DEVELOPER only
# ─────────────────────────────────────────────
@router.post("/books", response_model=BookResponse, status_code=status.HTTP_201_CREATED)
async def add_book(
    payload: BookCreateRequest,
    db: AsyncSession = Depends(get_db),
    staff_user: User = Depends(require_staff_or_dev),
):
    normalized_isbn = _normalize_isbn(payload.isbn)
    normalized_category = _normalize_category(payload.category)

    # New intake starts with every copy on the shelf.
    new_book = Book(
        title=payload.title.strip(),
        author=payload.author.strip(),
        isbn=normalized_isbn,
        category=normalized_category,
        total_copies=payload.total_copies,
        available_copies=payload.total_copies,
        shelf_location=payload.shelf_location.strip(),
    )

    existing = await db.execute(select(Book).where(Book.isbn == normalized_isbn))
    if existing.scalars().first():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A book with ISBN {normalized_isbn} is already catalogued",
        )

    db.add(new_book)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A book with ISBN {normalized_isbn} is already catalogued",
        )
    await db.refresh(new_book)
    return new_book


@router.put("/books/{book_id}", response_model=BookResponse)
async def update_book(
    book_id: uuid.UUID,
    payload: BookUpdateRequest,
    db: AsyncSession = Depends(get_db),
    staff_user: User = Depends(require_staff_or_dev),
):
    result = await db.execute(select(Book).where(Book.id == book_id))
    book = result.scalars().first()
    if not book:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Book not found")

    updates = payload.model_dump(exclude_unset=True)
    if "isbn" in updates and updates["isbn"] is not None:
        normalized = _normalize_isbn(updates["isbn"])
        duplicate = await db.execute(
            select(Book).where(Book.isbn == normalized, Book.id != book.id)
        )
        if duplicate.scalars().first():
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"A book with ISBN {normalized} is already catalogued",
            )
        updates["isbn"] = normalized

    if "category" in updates and updates["category"] is not None:
        updates["category"] = _normalize_category(updates["category"])

    for field, value in updates.items():
        setattr(book, field, value)

    # Preserve the stock invariant across partial updates
    # (the schema validator only sees both-fields updates).
    if book.available_copies > book.total_copies:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"available_copies ({book.available_copies}) cannot exceed total_copies ({book.total_copies})",
        )

    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Update conflicts with an existing catalogue entry",
        )
    await db.refresh(book)
    return book


@router.delete("/books/{book_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_book(
    book_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    staff_user: User = Depends(require_staff_or_dev),
):
    result = await db.execute(select(Book).where(Book.id == book_id))
    book = result.scalars().first()
    if not book:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Book not found")

    await db.delete(book)
    await db.commit()
    return None


# ─────────────────────────────────────────────
# Reservation — ALL authenticated roles
# ─────────────────────────────────────────────
@router.post("/books/{book_id}/reserve", response_model=BookResponse)
async def reserve_book(
    book_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    # Row lock: two students tapping Reserve on the last copy at the same
    # millisecond serialize here — the second one sees 0 copies (409),
    # never a negative counter.
    result = await db.execute(select(Book).where(Book.id == book_id).with_for_update())
    book = result.scalars().first()
    if not book:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Book not found")

    if book.available_copies <= 0:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No copies available: this title is fully checked out",
        )

    book.available_copies -= 1
    db.add(
        BookReservation(
            book_id=book.id,
            user_id=current_user.id,
            status="ACTIVE",
        )
    )
    await db.commit()
    await db.refresh(book)
    return book
