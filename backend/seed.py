import asyncio

from app.seed import (
    SAMPLE_LIBRARY_BOOKS,
    seed,
    seed_attendance_demo_data,
    seed_library_demo_books,
)

# Re-exports seed functionality for root-level script execution and test compatibility
__all__ = [
    "SAMPLE_LIBRARY_BOOKS",
    "seed",
    "seed_attendance_demo_data",
    "seed_library_demo_books",
]

if __name__ == "__main__":
    asyncio.run(seed())
