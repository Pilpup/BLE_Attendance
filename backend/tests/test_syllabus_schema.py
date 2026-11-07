# Tests for semester-wise syllabus tables, models, and endpoints
import uuid
import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.security import hash_password
from app.database import get_db
from app.main import app
from app.models.attendance import Base, Course, CourseUnit, UnitTopic, User

_current_session_factory = None


async def _make_test_env():
    global _current_session_factory
    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    _current_session_factory = session_factory
    return session_factory


async def _create_teacher(session_factory) -> tuple[User, dict]:
    teacher_id = uuid.uuid4()
    async with session_factory() as session:
        teacher = User(
            id=teacher_id,
            email=f"prof_{teacher_id.hex[:6]}@college.edu",
            hashed_password=hash_password("Password123!"),
            full_name="Prof. Alan Turing",
            role="TEACHER",
            is_active=True,
        )
        session.add(teacher)
        await session.commit()
    
    # Generate token
    from app.core.security import create_access_token
    token = create_access_token({"sub": str(teacher_id), "role": "TEACHER"})
    return teacher, {"Authorization": f"Bearer {token}"}


@pytest.mark.anyio
async def test_course_creation_with_academic_metadata():
    session_factory = await _make_test_env()
    teacher, headers = await _create_teacher(session_factory)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        payload = {
            "course_code": "CST 301",
            "course_name": "Cryptography & Network Security",
            "program": "B.Tech",
            "department": "Computer Science & Engineering (Cyber Security)",
            "semester": 5,
            "category": "DC",
            "course_type": "THEORY",
            "credits": 4,
            "periods_l": 3,
            "periods_t": 1,
            "periods_p": 0,
        }
        resp = await client.post("/api/v1/attendance/courses", json=payload, headers=headers)
        assert resp.status_code == 201, resp.text
        data = resp.json()
        assert data["course_code"] == "CST 301"
        assert data["course_name"] == "Cryptography & Network Security"
        assert data["semester"] == 5
        assert data["category"] == "DC"
        assert data["credits"] == 4
        assert data["periods_l"] == 3
        assert data["periods_t"] == 1
        assert data["periods_p"] == 0
        assert data["teacher_id"] == str(teacher.id)


@pytest.mark.anyio
async def test_course_filtering_by_semester_and_department():
    session_factory = await _make_test_env()
    teacher, headers = await _create_teacher(session_factory)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Create courses across multiple semesters
        courses_to_create = [
            {"course_code": "CST 201", "course_name": "Data Structures", "semester": 3, "department": "CSE"},
            {"course_code": "CST 202", "course_name": "Digital Electronics", "semester": 3, "department": "ECE"},
            {"course_code": "CST 301", "course_name": "Network Security", "semester": 5, "department": "CSE"},
        ]
        for c in courses_to_create:
            res = await client.post("/api/v1/attendance/courses", json=c, headers=headers)
            assert res.status_code == 201, res.text

        # Filter by semester=3
        res3 = await client.get("/api/v1/attendance/courses?semester=3")
        assert res3.status_code == 200
        codes = [item["course_code"] for item in res3.json()]
        assert "CST 201" in codes
        assert "CST 202" in codes
        assert "CST 301" not in codes

        # Filter by semester=5
        res5 = await client.get("/api/v1/attendance/courses?semester=5")
        assert res5.status_code == 200
        codes5 = [item["course_code"] for item in res5.json()]
        assert "CST 301" in codes5
        assert "CST 201" not in codes5

        # Filter by department=ECE
        res_ece = await client.get("/api/v1/attendance/courses?department=ECE")
        assert res_ece.status_code == 200
        codes_ece = [item["course_code"] for item in res_ece.json()]
        assert "CST 202" in codes_ece
        assert "CST 201" not in codes_ece


@pytest.mark.anyio
async def test_course_units_and_topics_hierarchy():
    session_factory = await _make_test_env()
    teacher, headers = await _create_teacher(session_factory)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Create course
        c_resp = await client.post(
            "/api/v1/attendance/courses",
            json={"course_code": "CST 302", "course_name": "Compiler Design", "semester": 6},
            headers=headers,
        )
        assert c_resp.status_code == 201
        course_id = c_resp.json()["id"]

        # Add Unit 1 with topics
        unit1_payload = {
            "unit_number": 1,
            "unit_title": "Introduction to Compilers & Lexical Analysis",
            "teaching_hours": 8,
            "topics": [
                {"topic_order": 1, "topic_name": "Phases of a compiler, Compiler writing tools"},
                {"topic_order": 2, "topic_name": "Regular expressions, Finite automata, Lex/Flex"},
            ],
        }
        u1_resp = await client.post(
            f"/api/v1/attendance/courses/{course_id}/units",
            json=unit1_payload,
            headers=headers,
        )
        assert u1_resp.status_code == 201, u1_resp.text
        unit1_data = u1_resp.json()
        assert unit1_data["unit_number"] == 1
        assert len(unit1_data["topics"]) == 2
        unit1_id = unit1_data["id"]

        # Add single topic to Unit 1
        t3_resp = await client.post(
            f"/api/v1/attendance/courses/{course_id}/units/{unit1_id}/topics",
            json={"topic_order": 3, "topic_name": "Design of lexical analyzer generator"},
            headers=headers,
        )
        assert t3_resp.status_code == 201, t3_resp.text
        assert t3_resp.json()["topic_order"] == 3

        # Add Unit 2
        unit2_payload = {
            "unit_number": 2,
            "unit_title": "Syntax Analysis",
            "teaching_hours": 10,
            "topics": [
                {"topic_order": 1, "topic_name": "Context-free grammars, Top-down and bottom-up parsing"},
            ],
        }
        u2_resp = await client.post(
            f"/api/v1/attendance/courses/{course_id}/units",
            json=unit2_payload,
            headers=headers,
        )
        assert u2_resp.status_code == 201, u2_resp.text

        # Fetch full syllabus
        syl_resp = await client.get(f"/api/v1/attendance/courses/{course_id}/syllabus")
        assert syl_resp.status_code == 200
        syllabus = syl_resp.json()
        assert len(syllabus) == 2
        assert syllabus[0]["unit_number"] == 1
        assert len(syllabus[0]["topics"]) == 3
        assert syllabus[1]["unit_number"] == 2
        assert len(syllabus[1]["topics"]) == 1

        # Check unique constraint on unit_number per course
        dup_u_resp = await client.post(
            f"/api/v1/attendance/courses/{course_id}/units",
            json={"unit_number": 1, "unit_title": "Duplicate Unit 1"},
            headers=headers,
        )
        assert dup_u_resp.status_code == 400


@pytest.mark.anyio
async def test_cascade_delete_course_removes_units_and_topics():
    session_factory = await _make_test_env()
    teacher, headers = await _create_teacher(session_factory)

    course_id = uuid.uuid4()
    unit_id = uuid.uuid4()
    topic_id = uuid.uuid4()

    async with session_factory() as session:
        course = Course(
            id=course_id,
            course_code="CST 401",
            course_name="Cloud Security",
            teacher_id=teacher.id,
            semester=7,
        )
        session.add(course)
        await session.flush()

        unit = CourseUnit(
            id=unit_id,
            course_id=course_id,
            unit_number=1,
            unit_title="Cloud Architecture",
        )
        session.add(unit)
        await session.flush()

        topic = UnitTopic(
            id=topic_id,
            unit_id=unit_id,
            topic_order=1,
            topic_name="SaaS, PaaS, IaaS Security",
        )
        session.add(topic)
        await session.commit()

    # Verify they exist
    async with session_factory() as session:
        u_check = (await session.execute(select(CourseUnit).where(CourseUnit.id == unit_id))).scalars().first()
        t_check = (await session.execute(select(UnitTopic).where(UnitTopic.id == topic_id))).scalars().first()
        assert u_check is not None
        assert t_check is not None

        # Delete course
        c_to_del = (await session.execute(select(Course).where(Course.id == course_id))).scalars().first()
        await session.delete(c_to_del)
        await session.commit()

    # Verify cascade deletion
    async with session_factory() as session:
        u_post = (await session.execute(select(CourseUnit).where(CourseUnit.id == unit_id))).scalars().first()
        t_post = (await session.execute(select(UnitTopic).where(UnitTopic.id == topic_id))).scalars().first()
        assert u_post is None
        assert t_post is None
