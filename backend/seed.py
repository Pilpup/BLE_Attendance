"""
Demo catalogue seed for the Library module (Phase 4).

The eight standard college textbooks mirror the Android in-memory sample
(LibraryCatalogStore) so the app shows the same catalogue whether it is
running against the live Neon database or in offline fallback mode.

Called once from the app lifespan after the schema sync — a no-op whenever
the catalogue already holds rows, so restarts never duplicate stock.
"""
import logging

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.library import Book

logger = logging.getLogger("uvicorn.error")

SAMPLE_LIBRARY_BOOKS = [
    {
        "title": "Computer Networking: A Top-Down Approach",
        "author": "James F. Kurose, Keith W. Ross",
        "isbn": "9780133594140",
        "category": "CS",
        "total_copies": 5,
        "available_copies": 3,
        "shelf_location": "Shelf 1-A",
    },
    {
        "title": "Introduction to Algorithms",
        "author": "Thomas H. Cormen et al.",
        "isbn": "9780262042829",
        "category": "CS",
        "total_copies": 3,
        "available_copies": 2,
        "shelf_location": "Shelf 1-B",
    },
    {
        "title": "Operating System Concepts",
        "author": "Abraham Silberschatz",
        "isbn": "9781118878762",
        "category": "CS",
        "total_copies": 4,
        "available_copies": 0,
        "shelf_location": "Shelf 1-C",
    },
    {
        "title": "Linear Algebra Done Right",
        "author": "Sheldon Axler",
        "isbn": "9781441987153",
        "category": "MATH",
        "total_copies": 3,
        "available_copies": 1,
        "shelf_location": "Shelf 2-A",
    },
    {
        "title": "Calculus: Early Transcendentals",
        "author": "James Stewart",
        "isbn": "9781285741550",
        "category": "MATH",
        "total_copies": 6,
        "available_copies": 4,
        "shelf_location": "Shelf 2-B",
    },
    {
        "title": "University Physics with Modern Physics",
        "author": "Hugh D. Young, Roger A. Freedman",
        "isbn": "9780321973610",
        "category": "PHYSICS",
        "total_copies": 4,
        "available_copies": 2,
        "shelf_location": "Shelf 3-A",
    },
    {
        "title": "Organic Chemistry",
        "author": "Jonathan Clayden",
        "isbn": "9780199642778",
        "category": "CHEMISTRY",
        "total_copies": 2,
        "available_copies": 1,
        "shelf_location": "Shelf 3-B",
    },
    {
        "title": "Molecular Biology of the Cell",
        "author": "Bruce Alberts et al.",
        "isbn": "9780815344322",
        "category": "BIO",
        "total_copies": 3,
        "available_copies": 0,
        "shelf_location": "Shelf 4-A",
    },
]


async def seed_library_demo_books(db: AsyncSession) -> int:
    """Seeds the sample catalogue once. Returns how many books were inserted."""
    existing = (await db.execute(select(func.count()).select_from(Book))).scalar_one()
    if existing > 0:
        return 0

    db.add_all([Book(**spec) for spec in SAMPLE_LIBRARY_BOOKS])
    await db.commit()
    logger.info(f"Seeded library catalogue with {len(SAMPLE_LIBRARY_BOOKS)} sample books")
    return len(SAMPLE_LIBRARY_BOOKS)
