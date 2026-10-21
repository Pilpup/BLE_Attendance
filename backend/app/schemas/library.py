from datetime import datetime
from typing import Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


# ─────────────────────────────────────────────
# Book catalogue (wire format matches the Android @SerializedName tags:
# total_copies, available_copies, shelf_location)
# ─────────────────────────────────────────────
class BookCreateRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=255)
    author: str = Field(..., min_length=1, max_length=255)
    isbn: str = Field(..., min_length=1, max_length=32)
    category: str = Field(..., min_length=1, max_length=50)
    total_copies: int = Field(default=1, ge=0)
    shelf_location: str = Field(default="Main Hall", min_length=1, max_length=100)


class BookUpdateRequest(BaseModel):
    title: Optional[str] = Field(None, min_length=1, max_length=255)
    author: Optional[str] = Field(None, min_length=1, max_length=255)
    isbn: Optional[str] = Field(None, min_length=1, max_length=32)
    category: Optional[str] = Field(None, min_length=1, max_length=50)
    total_copies: Optional[int] = Field(None, ge=0)
    available_copies: Optional[int] = Field(None, ge=0)
    shelf_location: Optional[str] = Field(None, min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_stock_bounds(self):
        total = self.total_copies
        available = self.available_copies
        if total is not None and available is not None and available > total:
            raise ValueError("available_copies cannot exceed total_copies")
        return self


class BookResponse(BaseModel):
    id: UUID
    title: str
    author: str
    isbn: str
    category: str
    total_copies: int
    available_copies: int
    shelf_location: str
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)
