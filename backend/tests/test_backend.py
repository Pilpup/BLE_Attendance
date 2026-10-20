import pytest
import jwt
from app.core.security import hash_password, verify_password, create_access_token
from app.services.graph_engine import audit_classroom_mesh
from app.main import app
from app.config import settings


def test_password_hashing():
    raw_pw = "supersecret123"
    hashed = hash_password(raw_pw)
    assert hashed != raw_pw
    assert verify_password(raw_pw, hashed) is True
    assert verify_password("wrongpassword", hashed) is False


def test_jwt_generation_and_decoding():
    data = {
        "sub": "22BCSE104",
        "name": "Aarav Sharma",
        "role": "STUDENT",
    }
    token = create_access_token(data=data)
    assert isinstance(token, str)

    decoded = jwt.decode(
        token,
        settings.JWT_SECRET_KEY,
        algorithms=[settings.JWT_ALGORITHM],
    )
    assert decoded["sub"] == "22BCSE104"
    assert decoded["name"] == "Aarav Sharma"
    assert decoded["role"] == "STUDENT"
    assert "exp" in decoded


def test_mesh_audit_topology():
    # 5-student scenario:
    # A & B are mutual desk neighbors (1.00)
    # C is adjacent to A & B, claiming both (Triadic closure -> 0.50)
    # D is Hallway Spoofer claiming only B without reciprocation (0.00)
    # E is isolated in corner with 0 neighbors (0.00)
    reports = {
        "A": ["B"],
        "B": ["A"],
        "C": ["A", "B"],
        "D": ["B"],
        "E": [],
    }

    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    # Verify A & B are direct mutuals
    assert res_map["A"]["status"] == "VERIFIED_PRESENT"
    assert res_map["A"]["verification_method"] == "DIRECT_MUTUAL"
    assert res_map["A"]["affinity_score"] == 1.00

    assert res_map["B"]["status"] == "VERIFIED_PRESENT"
    assert res_map["B"]["verification_method"] == "DIRECT_MUTUAL"
    assert res_map["B"]["affinity_score"] == 1.00

    # Verify C is triadic closure via shared bridges
    assert res_map["C"]["status"] == "VERIFIED_PRESENT"
    assert res_map["C"]["verification_method"] == "TRIADIC_CLOSURE"
    assert res_map["C"]["affinity_score"] == 0.50

    # Verify D is spoofer rejected (unreciprocated single claim)
    assert res_map["D"]["status"] == "SPOOFER_REJECTED"
    assert res_map["D"]["affinity_score"] == 0.00

    # Verify E is isolated
    assert res_map["E"]["status"] == "ISOLATED_FLAGGED"
    assert res_map["E"]["affinity_score"] == 0.00


def test_mesh_audit_offline_peer_witness():
    # Online students A and B verify each other
    # A and B both hear offline student OFF1 at desk range (2 witnesses -> Verified)
    # A alone hears offline student OFF_SINGLE (1 witness -> Isolated Flagged for Teacher Override)
    # Hallway spoofer D claims OFF2, but D is unverified (0 verified witnesses -> Ignored)
    reports = {
        "A": ["B", "OFF1", "OFF_SINGLE"],
        "B": ["A", "OFF1"],
        "D": ["OFF2"],
    }
    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    # OFF1 was witnessed by 2 verified peers (A and B) -> Verified Present
    assert "OFF1" in res_map
    assert res_map["OFF1"]["status"] == "VERIFIED_PRESENT"
    assert res_map["OFF1"]["verification_method"] == "PEER_WITNESS"
    assert res_map["OFF1"]["affinity_score"] == 0.85

    # OFF_SINGLE was witnessed by only 1 peer (A) -> Isolated Flagged (Cannot buddy-punch)
    assert "OFF_SINGLE" in res_map
    assert res_map["OFF_SINGLE"]["status"] == "ISOLATED_FLAGGED"
    assert res_map["OFF_SINGLE"]["verification_method"] == "NONE"
    assert res_map["OFF_SINGLE"]["affinity_score"] == 0.00
    assert "Teacher manual override" in res_map["OFF_SINGLE"]["remarks"]

    # OFF2 was only claimed by spoofer D (who is not in verified_set)
    # Therefore OFF2 is NOT verified
    assert "OFF2" not in res_map


def test_fastapi_routes_registered():
    paths = list(app.openapi()["paths"].keys())
    assert "/health" in paths
    assert "/api/v1/auth/register" in paths
    assert "/api/v1/auth/login" in paths
    assert "/api/v1/auth/me" in paths
    assert "/api/v1/attendance/courses" in paths
    assert "/api/v1/attendance/courses/{course_id}/sessions" in paths
    assert "/api/v1/attendance/sessions" in paths
    assert "/api/v1/attendance/sessions/{session_id}/cancel" in paths
    assert "/api/v1/attendance/sessions/{session_id}/finalize" in paths
    assert "/api/v1/attendance/sessions/{session_id}/submit-neighbors" in paths
    assert "/api/v1/attendance/sessions/{session_id}/audit-and-finalize" in paths
    assert "/api/v1/attendance/sessions/{session_id}" in paths
    assert "/api/v1/attendance/records/{record_id}/override" in paths


def test_openapi_schema_definitions():
    schemas = app.openapi()["components"]["schemas"]
    assert "RecordOverrideRequest" in schemas
    assert "AttendanceSessionResponse" in schemas
    assert "AttendanceRecordResponse" in schemas
    assert "CourseCreateRequest" in schemas
    assert "SubmitNeighborsRequest" in schemas


@pytest.mark.anyio
async def test_global_exception_handler_sanitization():
    import json
    from app.main import global_exception_handler
    from starlette.requests import Request

    dummy_request = Request({"type": "http", "method": "GET", "path": "/test"})
    exc = RuntimeError("Secret DB connection credentials / internal server path")

    # In production mode: traceback must be masked
    orig_env = settings.APP_ENV
    try:
        settings.APP_ENV = "production"
        prod_response = await global_exception_handler(dummy_request, exc)
        prod_data = json.loads(prod_response.body.decode())
        assert prod_response.status_code == 500
        assert "traceback" not in prod_data
        assert prod_data["detail"] == "An internal server error occurred."
        assert prod_data["type"] == "InternalServerError"

        # In development mode: traceback is available for local debugging
        settings.APP_ENV = "development"
        dev_response = await global_exception_handler(dummy_request, exc)
        dev_data = json.loads(dev_response.body.decode())
        assert dev_response.status_code == 500
        assert "traceback" in dev_data
        assert "RuntimeError" in dev_data["type"]
    finally:
        settings.APP_ENV = orig_env


def test_session_submission_model_integrity():
    from app.models.attendance import SessionSubmission
    columns = {c.name for c in SessionSubmission.__table__.columns}
    assert "id" in columns
    assert "session_id" in columns
    assert "student_id" in columns
    assert "device_key" in columns
    assert "reported_neighbors" in columns
    assert "created_at" in columns


def test_is_session_stale_naive_and_aware():
    from datetime import datetime, timedelta, timezone
    from app.routers.attendance import _is_session_stale

    assert _is_session_stale(None, minutes=2) is False

    now_utc = datetime.now(timezone.utc)
    recent_aware = now_utc - timedelta(seconds=30)
    assert _is_session_stale(recent_aware, minutes=2) is False

    old_aware = now_utc - timedelta(minutes=3)
    assert _is_session_stale(old_aware, minutes=2) is True

    # Test offset-naive datetimes (SQLite stores naive UTC datetimes)
    recent_naive = now_utc.replace(tzinfo=None) - timedelta(seconds=30)
    assert _is_session_stale(recent_naive, minutes=2) is False

    old_naive = now_utc.replace(tzinfo=None) - timedelta(minutes=3)
    assert _is_session_stale(old_naive, minutes=2) is True


@pytest.mark.anyio
async def test_session_auto_supersede():
    import uuid
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
    from app.database import get_db
    from app.models.attendance import Base, AttendanceSession
    from sqlalchemy import select

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    test_session_factory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with test_session_factory() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Register and login teacher
        t_reg = await client.post(
            "/api/v1/auth/register",
            json={
                "name": "Prof. Auto Supersede",
                "email": "auto.supersede@univ.edu",
                "password": "Password123!",
                "role": "TEACHER",
                "roll_number": "EMP999",
                "department": "CSE",
                "faculty_code": settings.FACULTY_SIGNUP_SECRET,
            },
        )
        assert t_reg.status_code == 201

        t_login = await client.post(
            "/api/v1/auth/login",
            json={"email": "auto.supersede@univ.edu", "password": "Password123!"},
        )
        assert t_login.status_code == 200
        token = t_login.json()["access_token"]
        headers = {"Authorization": f"Bearer {token}"}

        # Create session 1
        s1 = await client.post(
            "/api/v1/attendance/sessions",
            headers=headers,
            json={"session_token": "AAAA", "total_enrolled": 20},
        )
        assert s1.status_code == 201
        s1_id = s1.json()["id"]

        # Directly create session 2 without closing session 1 (simulating teacher closed app and reopened)
        s2 = await client.post(
            "/api/v1/attendance/sessions",
            headers=headers,
            json={"session_token": "BBBB", "total_enrolled": 20},
        )
        # Should succeed (HTTP 201) instead of failing with HTTP 409
        assert s2.status_code == 201
        s2_id = s2.json()["id"]
        assert s1_id != s2_id

        # Check DB state: session 1 must have been superseded (CANCELLED or EXPIRED)
        async with test_session_factory() as s:
            res = await s.execute(select(AttendanceSession).where(AttendanceSession.id == uuid.UUID(s1_id)))
            s1_db = res.scalar_one()
            assert s1_db.status in ("CANCELLED", "EXPIRED")


