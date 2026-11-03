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
from app.schemas.library import (
    BookCreateRequest,
    BookLookupResponse,
    BookResponse,
    BookUpdateRequest,
)
from app.services.book_lookup import lookup_book_by_isbn

router = APIRouter(prefix="/library", tags=["Library"])


# Normalize ISBN string by removing hyphens and converting to uppercase
def _normalize_isbn(raw: str) -> str:
    return raw.strip().replace("-", "").upper()


# Normalize category string to uppercase
def _normalize_category(raw: str) -> str:
    return raw.strip().upper()


# List books in catalogue with optional search, category filter, and pagination
@router.get("/books", response_model=List[BookResponse])
async def list_books(
    search: Optional[str] = Query(None, description="Matches title, author or ISBN"),
    category: Optional[str] = Query(None, description="Exact category, case-insensitive (CS, MATH…)"),
    limit: Optional[int] = Query(None, ge=1, le=200, description="Max books to return"),
    offset: Optional[int] = Query(None, ge=0, description="Number of books to skip"),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    query = select(Book)

    # Filter by search keyword matching title, author, or ISBN
    if search and search.strip():
        pattern = f"%{search.strip()}%"
        query = query.where(
            or_(
                Book.title.ilike(pattern),
                Book.author.ilike(pattern),
                Book.isbn.ilike(pattern),
            )
        )

    # Filter by category
    if category and category.strip():
        query = query.where(func.lower(Book.category) == category.strip().lower())

    query = query.order_by(Book.created_at.desc(), Book.title.asc())

    # Apply pagination offset and limit if provided
    if offset is not None:
        query = query.offset(offset)
    if limit is not None:
        query = query.limit(limit)

    result = await db.execute(query)
    return result.scalars().all()


# Look up book details by ISBN from local catalogue or external APIs
@router.get("/books/lookup/{isbn}", response_model=BookLookupResponse)
async def lookup_book(
    isbn: str,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    clean_isbn = _normalize_isbn(isbn)

    # Check local catalogue first
    existing = await db.execute(select(Book).where(Book.isbn == clean_isbn))
    local_book = existing.scalars().first()
    if local_book:
        return BookLookupResponse(
            title=local_book.title,
            author=local_book.author,
            edition=local_book.edition,
            index=local_book.shelf_location,
            isbn=local_book.isbn,
            category=local_book.category,
            overview=local_book.overview,
            table_of_contents=local_book.table_of_contents,
            source="Local Catalogue",
        )

    # Query external book metadata providers
    lookup_result = await lookup_book_by_isbn(clean_isbn)
    if not lookup_result:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No book metadata found for ISBN {isbn} across Google Books, OpenLibrary, and ISBNdb",
        )

    return lookup_result


# Add new book to library inventory
@router.post("/books", response_model=BookResponse, status_code=status.HTTP_201_CREATED)
async def add_book(
    payload: BookCreateRequest,
    db: AsyncSession = Depends(get_db),
    staff_user: User = Depends(require_staff_or_dev),
):
    normalized_isbn = _normalize_isbn(payload.isbn)
    normalized_category = _normalize_category(payload.category)

    # Initialize book with all copies available on shelf
    new_book = Book(
        title=payload.title.strip(),
        author=payload.author.strip(),
        isbn=normalized_isbn,
        category=normalized_category,
        total_copies=payload.total_copies,
        available_copies=payload.total_copies,
        shelf_location=payload.shelf_location.strip(),
        edition=payload.edition.strip() if payload.edition and payload.edition.strip() else None,
        overview=payload.overview.strip() if payload.overview and payload.overview.strip() else None,
        table_of_contents=payload.table_of_contents.strip() if payload.table_of_contents and payload.table_of_contents.strip() else None,
    )

    # Verify ISBN uniqueness before insert
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


# Update book details and inventory counts
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

    # Validate updated ISBN uniqueness
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

    # Ensure available copies do not exceed total inventory
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


# Delete book from library catalogue
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


# Reserve a copy of a book with row-level locking
@router.post("/books/{book_id}/reserve", response_model=BookResponse)
async def reserve_book(
    book_id: uuid.UUID,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    # Lock row to prevent concurrent over-reservation
    result = await db.execute(select(Book).where(Book.id == book_id).with_for_update())
    book = result.scalars().first()
    if not book:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Book not found")

    # Check copy availability
    if book.available_copies <= 0:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No copies available: this title is fully checked out",
        )

    # Decrement available count and save reservation
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

