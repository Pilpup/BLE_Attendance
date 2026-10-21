"""
Library /api/v1/library route suite (Phase 4).

Runs on an in-memory SQLite database per test (same pattern as test_backend.py):
users register/log in through the real /auth endpoints, with STAFF and
DEVELOPER identities injected straight into the DB — self-registration never
mints those roles, matching production behavior.
"""
import uuid
from uuid import UUID

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings
from app.core.security import hash_password
from app.database import get_db
from app.main import app
from app.models.attendance import Base, User
from app.models.library import Book, BookReservation

LIBRARY_URL = "/api/v1/library/books"


async def _make_test_env():
    """Fresh per-test database + FastAPI dependency override."""
    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    return session_factory


def _book_payload(isbn: str, title: str = "Computer Networks", copies: int = 3) -> dict:
    """Lowercase category on purpose: the API must normalize to 'CS'."""
    return {
        "title": title,
        "author": "Andrew S. Tanenbaum",
        "isbn": isbn,
        "category": "cs",
        "total_copies": copies,
        "shelf_location": "Shelf 9-Z",
    }


async def _login(client: AsyncClient, email: str, password: str = "Password123!") -> dict:
    login = await client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert login.status_code == 200, login.text
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def _register_and_login(
    client: AsyncClient, *, name: str, email: str, role: str, roll_number: str
) -> dict:
    payload = {
        "name": name,
        "email": email,
        "password": "Password123!",
        "role": role,
        "roll_number": roll_number,
        "department": "CSE",
    }
    if role == "TEACHER":
        payload["faculty_code"] = settings.FACULTY_SIGNUP_SECRET
    reg = await client.post("/api/v1/auth/register", json=payload)
    assert reg.status_code == 201, reg.text
    return await _login(client, email)


async def _insert_role_user(session_factory, *, name: str, email: str, role: str, roll_number: str):
    """Seed a STAFF / DEVELOPER identity directly (no self-registration route)."""
    async with session_factory() as session:
        session.add(
            User(
                id=uuid.uuid4(),
                email=email,
                hashed_password=hash_password("Password123!"),
                full_name=name,
                role=role,
                roll_number=roll_number,
                department="ADMIN",
            )
        )
        await session.commit()


async def _seed_books(session_factory, books: list[dict]):
    async with session_factory() as session:
        session.add_all([Book(**spec) for spec in books])
        await session.commit()


# ─────────────────────────────────────────────
# Route surface & seed data
# ─────────────────────────────────────────────
def test_library_routes_registered():
    paths = list(app.openapi()["paths"].keys())
    assert "/api/v1/library/books" in paths
    assert "/api/v1/library/books/{book_id}" in paths
    assert "/api/v1/library/books/{book_id}/reserve" in paths


def test_library_seed_data_integrity():
    from seed import SAMPLE_LIBRARY_BOOKS

    assert len(SAMPLE_LIBRARY_BOOKS) == 8
    isbns = [b["isbn"] for b in SAMPLE_LIBRARY_BOOKS]
    assert len(set(isbns)) == 8
    for spec in SAMPLE_LIBRARY_BOOKS:
        assert spec["title"] and spec["author"]
        assert spec["category"] == spec["category"].upper()
        assert 0 <= spec["available_copies"] <= spec["total_copies"]
        assert spec["shelf_location"]


# ─────────────────────────────────────────────
# Browsing: authentication, search & filters
# ─────────────────────────────────────────────
@pytest.mark.anyio
async def test_library_unauthenticated_browse_rejected():
    await _make_test_env()
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(LIBRARY_URL)
    assert response.status_code == 401


@pytest.mark.anyio
async def test_library_student_search_and_category_filter():
    session_factory = await _make_test_env()
    await _seed_books(
        session_factory,
        [
            dict(
                title="Computer Networking: A Top-Down Approach",
                author="James F. Kurose, Keith W. Ross",
                isbn="9780133594140",
                category="CS",
                total_copies=5,
                available_copies=3,
            ),
            dict(
                title="Introduction to Algorithms",
                author="Thomas H. Cormen et al.",
                isbn="9780262042829",
                category="CS",
                total_copies=3,
                available_copies=2,
            ),
            dict(
                title="Linear Algebra Done Right",
                author="Sheldon Axler",
                isbn="9781441987153",
                category="MATH",
                total_copies=3,
                available_copies=1,
            ),
        ],
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        headers = await _register_and_login(
            client, name="Alice Anderson", email="alice@univ.edu", role="STUDENT", roll_number="22BCSE001"
        )

        everything = await client.get(LIBRARY_URL, headers=headers)
        assert everything.status_code == 200
        assert len(everything.json()) == 3

        by_title = await client.get(LIBRARY_URL, headers=headers, params={"search": "networking"})
        assert by_title.status_code == 200
        assert len(by_title.json()) == 1
        assert by_title.json()[0]["title"].startswith("Computer Networking")

        by_author = await client.get(LIBRARY_URL, headers=headers, params={"search": "KUROSE"})
        assert by_author.status_code == 200
        assert len(by_author.json()) == 1

        by_isbn = await client.get(LIBRARY_URL, headers=headers, params={"search": "9781441987153"})
        assert by_isbn.status_code == 200
        assert len(by_isbn.json()) == 1
        assert by_isbn.json()[0]["category"] == "MATH"

        by_category = await client.get(LIBRARY_URL, headers=headers, params={"category": "MATH"})
        assert by_category.status_code == 200
        assert len(by_category.json()) == 1

        by_category_lcase = await client.get(LIBRARY_URL, headers=headers, params={"category": "cs"})
        assert by_category_lcase.status_code == 200
        assert len(by_category_lcase.json()) == 2

        combined = await client.get(LIBRARY_URL, headers=headers, params={"search": "algorithms", "category": "cs"})
        assert combined.status_code == 200
        assert len(combined.json()) == 1


# ─────────────────────────────────────────────
# RBAC: students can browse but never mutate
# ─────────────────────────────────────────────
@pytest.mark.anyio
async def test_library_student_403_on_inventory_crud():
    session_factory = await _make_test_env()
    await _insert_role_user(
        session_factory, name="Sarah Chen", email="sarah4@univ.edu", role="STAFF", roll_number="LIB004"
    )
    payload = _book_payload("9781119809380")

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        student_headers = await _register_and_login(
            client, name="Bob Ng", email="bob@univ.edu", role="STUDENT", roll_number="22BCSE002"
        )
        staff_headers = await _login(client, "sarah4@univ.edu")
        created = await client.post(LIBRARY_URL, headers=staff_headers, json=payload)
        assert created.status_code == 201
        book_id = created.json()["id"]

        forbidden_add = await client.post(LIBRARY_URL, headers=student_headers, json=payload)
        assert forbidden_add.status_code == 403
        assert "Insufficient permissions" in forbidden_add.json()["detail"]

        forbidden_edit = await client.put(f"{LIBRARY_URL}/{book_id}", headers=student_headers, json={"title": "X"})
        assert forbidden_edit.status_code == 403

        forbidden_delete = await client.delete(f"{LIBRARY_URL}/{book_id}", headers=student_headers)
        assert forbidden_delete.status_code == 403

        # A student may still RESERVE (student/teacher workflow)
        reserve = await client.post(f"{LIBRARY_URL}/{book_id}/reserve", headers=student_headers)
        assert reserve.status_code == 200


# ─────────────────────────────────────────────
# Staff / Developer inventory lifecycle
# ─────────────────────────────────────────────
@pytest.mark.anyio
async def test_library_staff_and_dev_crud_lifecycle():
    session_factory = await _make_test_env()
    await _insert_role_user(
        session_factory, name="Sarah Chen", email="sarah@univ.edu", role="STAFF", roll_number="LIB001"
    )
    await _insert_role_user(
        session_factory, name="Alex Rivera", email="alex@univ.edu", role="DEVELOPER", roll_number="DEV001"
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        staff_headers = await _login(client, "sarah@univ.edu")

        # 1) Staff intake: normalized ISBN/category, every copy starts available
        created = await client.post(
            LIBRARY_URL,
            headers=staff_headers,
            json=_book_payload("710-4-083-00550-3", title="Compilers: Principles, Techniques", copies=3),
        )
        assert created.status_code == 201, created.text
        book = created.json()
        assert book["isbn"] == "7104083005503"
        assert book["category"] == "CS"
        assert book["total_copies"] == 3
        assert book["available_copies"] == 3
        book_id = book["id"]

        # 2) Same ISBN, differently hyphenated — still a duplicate
        duplicate = await client.post(
            LIBRARY_URL,
            headers=staff_headers,
            json=_book_payload("7104083005503", title="Second Intake Should Fail"),
        )
        assert duplicate.status_code == 409

        # 3) Staff update metadata and stock
        updated = await client.put(
            f"{LIBRARY_URL}/{book_id}",
            headers=staff_headers,
            json={"title": "Compilers: Principles, Techniques & Tools", "total_copies": 2, "available_copies": 2},
        )
        assert updated.status_code == 200
        assert updated.json()["title"].endswith("& Tools")
        assert updated.json()["total_copies"] == 2

        # 4) Invariant: available_copies may never exceed total_copies
        bad_both = await client.put(
            f"{LIBRARY_URL}/{book_id}",
            headers=staff_headers,
            json={"total_copies": 1, "available_copies": 2},
        )
        assert bad_both.status_code == 422

        bad_available_only = await client.put(
            f"{LIBRARY_URL}/{book_id}",
            headers=staff_headers,
            json={"available_copies": 3},
        )
        assert bad_available_only.status_code == 422

        # 5) Developer also gets intake rights (single copy to test reserve exhaust)
        dev_login = await _login(client, "alex@univ.edu")
        dev_created = await client.post(
            LIBRARY_URL,
            headers=dev_login,
            json=_book_payload("9780415528402", title="Engineering Mathematics", copies=1),
        )
        assert dev_created.status_code == 201
        dev_book_id = dev_created.json()["id"]

        # 6) Staff reserve works too (all authenticated roles may reserve)
        staff_reserve = await client.post(f"{LIBRARY_URL}/{dev_book_id}/reserve", headers=staff_headers)
        assert staff_reserve.status_code == 200
        assert staff_reserve.json()["available_copies"] == 0

        # 7) Removal + repeated removal (404)
        removed = await client.delete(f"{LIBRARY_URL}/{dev_book_id}", headers=staff_headers)
        assert removed.status_code == 204

        listing = await client.get(LIBRARY_URL, headers=staff_headers)
        assert listing.status_code == 200
        assert dev_book_id not in [b["id"] for b in listing.json()]

        removed_again = await client.delete(f"{LIBRARY_URL}/{dev_book_id}", headers=staff_headers)
        assert removed_again.status_code == 404


# ─────────────────────────────────────────────
# Reserve: atomic decrement + zero-copy conflict
# ─────────────────────────────────────────────
@pytest.mark.anyio
async def test_library_reserve_decrement_and_zero_copy_conflict():
    session_factory = await _make_test_env()
    await _insert_role_user(
        session_factory, name="Sarah Chen", email="sarah2@univ.edu", role="STAFF", roll_number="LIB002"
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        staff_headers = await _login(client, "sarah2@univ.edu")
        created = await client.post(
            LIBRARY_URL,
            headers=staff_headers,
            json=_book_payload("9780136353789", title="Compilers Workbook", copies=2),
        )
        assert created.status_code == 201
        book_id = created.json()["id"]

        student_headers = await _register_and_login(
            client, name="Carl Rex", email="carl@univ.edu", role="STUDENT", roll_number="22BCSE003"
        )

        first = await client.post(f"{LIBRARY_URL}/{book_id}/reserve", headers=student_headers)
        assert first.status_code == 200
        assert first.json()["available_copies"] == 1

        second = await client.post(f"{LIBRARY_URL}/{book_id}/reserve", headers=student_headers)
        assert second.status_code == 200
        assert second.json()["available_copies"] == 0

        last_copy_gone = await client.post(f"{LIBRARY_URL}/{book_id}/reserve", headers=student_headers)
        assert last_copy_gone.status_code == 409
        assert "No copies available" in last_copy_gone.json()["detail"]

        # The stock counter must never go negative, and every hold is logged
        async with session_factory() as session:
            row = (await session.execute(select(Book).where(Book.id == UUID(book_id)))).scalar_one()
            assert row.available_copies == 0
            reservations = (
                (await session.execute(select(BookReservation).where(BookReservation.book_id == UUID(book_id))))
                .scalars().all()
            )
            assert len(reservations) == 2
            assert all(r.status == "ACTIVE" for r in reservations)
            assert all(r.user_id is not None for r in reservations)


# ─────────────────────────────────────────────
# Unknown-book 404s
# ─────────────────────────────────────────────
@pytest.mark.anyio
async def test_library_unknown_book_404s():
    session_factory = await _make_test_env()
    await _insert_role_user(
        session_factory, name="Sarah Chen", email="sarah3@univ.edu", role="STAFF", roll_number="LIB003"
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        staff_headers = await _login(client, "sarah3@univ.edu")
        ghost_id = str(uuid.uuid4())

        put_miss = await client.put(
            f"{LIBRARY_URL}/{ghost_id}", headers=staff_headers, json={"title": "Ghost Edit"}
        )
        assert put_miss.status_code == 404

        delete_miss = await client.delete(f"{LIBRARY_URL}/{ghost_id}", headers=staff_headers)
        assert delete_miss.status_code == 404

        reserve_miss = await client.post(f"{LIBRARY_URL}/{ghost_id}/reserve", headers=staff_headers)
        assert reserve_miss.status_code == 404
