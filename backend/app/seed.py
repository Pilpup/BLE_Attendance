import asyncio
import uuid
from sqlalchemy import select
from app.database import AsyncSessionLocal, engine
from app.models.attendance import Base, User, Course
from app.core.security import hash_password


async def seed():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with AsyncSessionLocal() as db:
        # Check if already seeded
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
            print("✓ Seeded Teacher: teacher@university.edu (password: teacher123)")
        else:
            print("• Teacher already exists")

        # Seed Course
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
            print("✓ Seeded Course: CS401")
        else:
            print("• Course CS401 already exists")

        # Seed Students
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
                print(f"✓ Seeded Student: {name} ({roll}) - {email}")

        await db.commit()
        print("\nDatabase seeding completed successfully.")


if __name__ == "__main__":
    asyncio.run(seed())
