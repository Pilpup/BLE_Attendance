import asyncio
import logging
import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import hash_password
from app.database import AsyncSessionLocal, engine
from app.models.attendance import Base, Course, User
from app.models.library import Book

logger = logging.getLogger("uvicorn.error")

# Standard textbook catalog seed data mirroring the mobile offline store
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


# Seeds sample library catalogue once if empty
async def seed_library_demo_books(db: AsyncSession) -> int:
    existing = (await db.execute(select(func.count()).select_from(Book))).scalar_one()
    if existing > 0:
        return 0

    db.add_all([Book(**spec) for spec in SAMPLE_LIBRARY_BOOKS])
    await db.commit()
    logger.info(f"Seeded library catalogue with {len(SAMPLE_LIBRARY_BOOKS)} sample books")
    return len(SAMPLE_LIBRARY_BOOKS)


# Seeds initial faculty and student attendance accounts and demo course
async def seed_attendance_demo_data(db: AsyncSession) -> None:
    t_check = await db.execute(select(User).where(User.email == "teacher@university.edu"))
    teacher = t_check.scalars().first()
    if not teacher:
        teacher = User(
            id=uuid.uuid4(),
            email="teacher@university.edu",
            hashed_password=hash_password("teacher123"),
            full_name="Prof. Alan Turing",
            role="TEACHER",
            department="Computer Science",
        )
        db.add(teacher)
        await db.commit()
        await db.refresh(teacher)

    c_check = await db.execute(select(Course).where(Course.course_code == "CS401"))
    course = c_check.scalars().first()
    if not course:
        course = Course(
            id=uuid.uuid4(),
            course_code="CS401",
            course_name="Distributed Systems & BLE Mesh",
            teacher_id=teacher.id,
        )
        db.add(course)
        await db.commit()

    demo_students = [
        ("alice@university.edu", "student123", "Alice Chen", "22BCSE0101"),
        ("bob@university.edu", "student123", "Bob Smith", "22BCSE0102"),
        ("charlie@university.edu", "student123", "Charlie Davis", "22BCSE0103"),
        ("diana@university.edu", "student123", "Diana Prince", "22BCSE0104"),
    ]

    for email, pwd, name, roll in demo_students:
        s_check = await db.execute(select(User).where(User.email == email))
        if not s_check.scalars().first():
            student = User(
                id=uuid.uuid4(),
                email=email,
                hashed_password=hash_password(pwd),
                full_name=name,
                role="STUDENT",
                roll_number=roll,
                department="Computer Science",
            )
            db.add(student)

    await db.commit()


# Runs unified database table initialization and demo seeding
async def seed() -> None:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with AsyncSessionLocal() as db:
        await seed_attendance_demo_data(db)
        await seed_library_demo_books(db)


if __name__ == "__main__":
    asyncio.run(seed())
