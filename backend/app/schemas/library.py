from datetime import datetime
from typing import Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


# Request payload for cataloguing a new library book
class BookCreateRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=255)
    author: str = Field(..., min_length=1, max_length=255)
    isbn: str = Field(..., min_length=1, max_length=32)
    category: str = Field(..., min_length=1, max_length=50)
    total_copies: int = Field(default=1, ge=0)
    shelf_location: str = Field(default="Main Hall", min_length=1, max_length=100)
    edition: Optional[str] = Field(None, max_length=100)
    overview: Optional[str] = Field(None, max_length=5000)
    table_of_contents: Optional[str] = Field(None, max_length=20000)

    # Strips whitespace from ISBN
    @field_validator("isbn", mode="before")
    @classmethod
    def clean_isbn(cls, v: str) -> str:
        if isinstance(v, str):
            return v.strip()
        return v


# Request payload for updating library book details and inventory
class BookUpdateRequest(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=255)
    author: Optional[str] = Field(None, min_length=1, max_length=255)
    isbn: Optional[str] = Field(None, min_length=1, max_length=32)
    category: Optional[str] = Field(None, min_length=1, max_length=50)
    total_copies: Optional[int] = Field(None, ge=0)
    available_copies: Optional[int] = Field(None, ge=0)
    shelf_location: Optional[str] = Field(None, min_length=1, max_length=100)
    edition: Optional[str] = Field(None, max_length=100)
    overview: Optional[str] = Field(None, max_length=5000)
    table_of_contents: Optional[str] = Field(None, max_length=20000)

    # Strips whitespace from ISBN
    @field_validator("isbn", mode="before")
    @classmethod
    def clean_isbn(cls, v: Optional[str]) -> Optional[str]:
        if isinstance(v, str):
            return v.strip()
        return v

    # Validates that available copies do not exceed total stock
    @model_validator(mode="after")
    def validate_stock_bounds(self):
        total = self.total_copies
        available = self.available_copies
        if total is not None and available is not None and available > total:
            raise ValueError("available_copies cannot exceed total_copies")
        return self


# Public library book response schema
class BookResponse(BaseModel):
    id: UUID
    title: str
    author: str
    isbn: str
    category: str
    total_copies: int
    available_copies: int
    shelf_location: str
    edition: Optional[str] = None
    overview: Optional[str] = None
    table_of_contents: Optional[str] = None
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


# External book metadata lookup response
class BookLookupResponse(BaseModel):
    title: str
    author: str
    edition: Optional[str] = None
    index: Optional[str] = "Main Hall"
    isbn: str
    category: str
    overview: Optional[str] = None
    table_of_contents: Optional[str] = None
    source: str
