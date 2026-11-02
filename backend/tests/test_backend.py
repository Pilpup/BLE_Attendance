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
    from app.models.attendance import Base, AttendanceSession, EmailOTP
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
        # Register teacher (initiates OTP verification)
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
        assert t_reg.status_code == 200

        # Query OTP from db to verify registration
        async with test_session_factory() as s:
            otp_record = (await s.execute(select(EmailOTP).where(EmailOTP.email == "auto.supersede@univ.edu"))).scalars().first()
            otp_code = otp_record.otp_code

        verify_reg = await client.post(
            "/api/v1/auth/register/verify-otp",
            json={"email": "auto.supersede@univ.edu", "otp_code": otp_code},
        )
        assert verify_reg.status_code == 201
        token = verify_reg.json()["access_token"]
        headers = {"Authorization": f"Bearer {token}"}

        # Create session 1
        s1 = await client.post(
            "/api/v1/attendance/sessions",
            headers=headers,
            json={"session_token": "AAAA", "session_salt": "1A1A", "total_enrolled": 20},
        )
        assert s1.status_code == 201
        s1_id = s1.json()["id"]

        # Directly create session 2 without closing session 1 (simulating teacher closed app and reopened)
        s2 = await client.post(
            "/api/v1/attendance/sessions",
            headers=headers,
            json={"session_token": "BBBB", "session_salt": "2B2B", "total_enrolled": 20},
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


@pytest.mark.anyio
async def test_submit_neighbors_salt_enforcement():
    """
    Layer 2 anti-remote-submission guard (ProjectSummary §1.1):
    An absent student who learned only the 4-char session token over chat
    cannot stage a check-in — the endpoint rejects missing or wrong beacon salt.
    """
    import uuid
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
    from app.database import get_db
    from app.models.attendance import Base, EmailOTP
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
        # Register and verify teacher
        t_reg = await client.post(
            "/api/v1/auth/register",
            json={
                "name": "Prof. Salt Guard",
                "email": "salt.guard@univ.edu",
                "password": "Password123!",
                "role": "TEACHER",
                "roll_number": "EMP888",
                "department": "CSE",
                "faculty_code": settings.FACULTY_SIGNUP_SECRET,
            },
        )
        assert t_reg.status_code == 200

        async with test_session_factory() as s:
            otp_record = (await s.execute(select(EmailOTP).where(EmailOTP.email == "salt.guard@univ.edu"))).scalars().first()
            otp_code = otp_record.otp_code

        verify_reg = await client.post(
            "/api/v1/auth/register/verify-otp",
            json={"email": "salt.guard@univ.edu", "otp_code": otp_code},
        )
        assert verify_reg.status_code == 201
        headers = {"Authorization": f"Bearer {verify_reg.json()['access_token']}"}

        # Teacher registers the salt at session creation (required)
        s_res = await client.post(
            "/api/v1/attendance/sessions",
            headers=headers,
            json={"session_token": "ABCD", "session_salt": "Y7K9", "total_enrolled": 10},
        )
        assert s_res.status_code == 201
        session_id = s_res.json()["id"]

        submit_url = f"/api/v1/attendance/sessions/{session_id}/submit-neighbors"

        # 1) Missing salt (absent student replaying only the session token from chat)
        missing = await client.post(
            submit_url,
            json={"student_id": "3456", "neighbors": ["1234", "2345"]},
        )
        assert missing.status_code == 403

        # 2) Wrong salt (again: knowledge of the token alone is not enough)
        wrong = await client.post(
            submit_url,
            json={"student_id": "3456", "neighbors": ["1234", "2345"], "session_salt": "WRNG"},
        )
        assert wrong.status_code == 403
        assert "Nice try" in wrong.json()["detail"]

        # 3) Matching salt — in-room students who actually heard the beacon (mutual pair)
        valid_one = await client.post(
            submit_url,
            json={"student_id": "1234", "neighbors": ["5678"], "session_salt": "Y7K9"},
        )
        assert valid_one.status_code == 200
        assert valid_one.json()["status"] == "success"

        valid_two = await client.post(
            submit_url,
            json={"student_id": "5678", "neighbors": ["1234"], "session_salt": "Y7K9"},
        )
        assert valid_two.status_code == 200

        # 4) Teacher session creation itself rejects missing salt (required field)
        no_salt = await client.post(
            "/api/v1/attendance/sessions",
            headers=headers,
            json={"session_token": "WXYZ", "total_enrolled": 10},
        )
        assert no_salt.status_code == 422

        # 5) The salt-rejected attempt must surface on the teacher's audit dashboard
        audit_res = await client.post(
            f"/api/v1/attendance/sessions/{session_id}/audit-and-finalize",
            headers=headers,
            json={"reports": {}},
        )
        assert audit_res.status_code == 200
        audit_data = audit_res.json()

        assert audit_data["status"] == "COMPLETED"
        assert audit_data["total_verified"] == 2
        assert audit_data["total_spoofers"] == 1  # deduplicated: missing + wrong = one flag

        record_map = {r["ble_device_id"]: r for r in audit_data["records"]}
        spoofer_record = record_map["3456"]
        assert spoofer_record["status"] == "SPOOFER_REJECTED"
        assert spoofer_record["verification_method"] == "SALT_REJECTED"
        assert "salt mismatch" in spoofer_record["remarks"]

        assert record_map["1234"]["status"] == "VERIFIED_PRESENT"
        assert record_map["1234"]["verification_method"] == "DIRECT_MUTUAL"
        assert record_map["5678"]["status"] == "VERIFIED_PRESENT"


@pytest.mark.anyio
async def test_expired_otp_purging_and_cleanup():
    """
    Verify expired OTP cleanup behavior:
    1. Expired OTP records are deleted while active OTP records are preserved.
    2. Submitting an expired OTP rejects with HTTP 400 and purges the record immediately.
    """
    import uuid
    from datetime import datetime, timezone, timedelta
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
    from sqlalchemy import select, delete
    from app.database import get_db
    from app.models.attendance import Base, EmailOTP

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    test_session_factory = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with test_session_factory() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    now = datetime.now(timezone.utc)

    # 1. Insert an expired OTP (expired 10 mins ago) and an active OTP
    async with test_session_factory() as s:
        expired_otp = EmailOTP(
            id=uuid.uuid4(),
            email="expired.student@univ.edu",
            otp_code="111111",
            purpose="REGISTER",
            attempts=0,
            expires_at=now - timedelta(minutes=10),
        )
        active_otp = EmailOTP(
            id=uuid.uuid4(),
            email="active.student@univ.edu",
            otp_code="222222",
            purpose="REGISTER",
            attempts=0,
            expires_at=now + timedelta(minutes=5),
        )
        s.add_all([expired_otp, active_otp])
        await s.commit()

    # 2. Run the sweeper query (same logic as purge_expired_otps_worker)
    async with test_session_factory() as s:
        del_stmt = delete(EmailOTP).where(EmailOTP.expires_at < now)
        res = await s.execute(del_stmt)
        await s.commit()
        assert res.rowcount == 1

        # Check remaining records
        all_otps = (await s.execute(select(EmailOTP))).scalars().all()
        assert len(all_otps) == 1
        assert all_otps[0].email == "active.student@univ.edu"

    # 3. Test verification attempt with an expired OTP deletes it on attempt
    async with test_session_factory() as s:
        stale_otp = EmailOTP(
            id=uuid.uuid4(),
            email="stale.verify@univ.edu",
            otp_code="333333",
            purpose="LOGIN",
            attempts=0,
            expires_at=now - timedelta(minutes=1),
        )
        s.add(stale_otp)
        await s.commit()

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        verify_res = await client.post(
            "/api/v1/auth/login/verify-otp",
            json={"email": "stale.verify@univ.edu", "otp_code": "333333"}
        )
        assert verify_res.status_code == 400
        assert "expired" in verify_res.json()["detail"].lower()

        # The expired record must have been deleted
        async with test_session_factory() as s:
            rec = (await s.execute(
                select(EmailOTP).where(EmailOTP.email == "stale.verify@univ.edu")
            )).scalars().first()
            assert rec is None


# Verifies face engine preprocessing and embedding extraction
def test_face_matching_engine():
    import io
    import numpy as np
    from PIL import Image
    from app.services.face_engine import FaceMatchingEngine

    engine = FaceMatchingEngine()

    img = Image.new("RGB", (100, 100), color=(120, 80, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    raw_bytes = buf.getvalue()

    embedding = engine.extract_embedding(raw_bytes)
    assert embedding.shape == (128,)
    norm = np.linalg.norm(embedding)
    assert abs(norm - 1.0) < 1e-4

    sim_self = engine.compute_similarity(embedding, embedding)
    assert abs(sim_self - 1.0) < 1e-4


# Verifies session face deduplication and privacy purging
def test_session_face_registry():
    import base64
    import io
    from PIL import Image
    from httpx import AsyncClient, ASGITransport
    from app.services.face_engine import SessionFaceRegistry

    registry = SessionFaceRegistry()

    img_a = Image.new("RGB", (100, 100), color=(255, 0, 0))
    buf_a = io.BytesIO()
    img_a.save(buf_a, format="JPEG")
    b64_a = base64.b64encode(buf_a.getvalue()).decode("utf-8")

    # First student in session
    res_a = registry.register_and_check("session_101", "STUA", "22BCSE101", b64_a)
    assert res_a.is_duplicate is False

    # Second student with identical face image (proxy attempt)
    res_b = registry.register_and_check("session_101", "STUB", "22BCSE102", b64_a)
    assert res_b.is_duplicate is True
    assert res_b.matched_student_key == "STUA"

    flagged = registry.get_flagged_pairs("session_101")
    assert len(flagged) == 1
    assert flagged[0]["student_a_key"] == "STUA"
    assert flagged[0]["student_b_key"] == "STUB"

    # Purge session data
    registry.clear_session("session_101")
    assert len(registry.get_flagged_pairs("session_101")) == 0


def test_session_face_registry_cluster_interrogation():
    import base64
    import io
    from PIL import Image
    from app.services.face_engine import SessionFaceRegistry

    registry = SessionFaceRegistry()

    # Image A: Red
    img_a = Image.new("RGB", (100, 100), color=(255, 0, 0))
    buf_a = io.BytesIO()
    img_a.save(buf_a, format="JPEG")
    b64_a = base64.b64encode(buf_a.getvalue()).decode("utf-8")

    # Image B: Gray (moderate similarity ~0.58 to Red in synthetic test embedding)
    img_b = Image.new("RGB", (100, 100), color=(150, 150, 150))
    buf_b = io.BytesIO()
    img_b.save(buf_b, format="JPEG")
    b64_b = base64.b64encode(buf_b.getvalue()).decode("utf-8")

    # Image Proxy: Orange (high similarity ~0.93 to Red)
    img_proxy = Image.new("RGB", (100, 100), color=(255, 80, 0))
    buf_proxy = io.BytesIO()
    img_proxy.save(buf_proxy, format="JPEG")
    b64_proxy = base64.b64encode(buf_proxy.getvalue()).decode("utf-8")

    # Student A registers with cluster containing Student B
    res_a = registry.register_and_check("session_202", "STUA", "22BCSE201", b64_a, cluster_peers={"STUB"})
    assert res_a.is_duplicate is False

    # Student C (distant, across the room) registers with image B (moderately similar to A, ~0.58)
    # Since C is NOT in A's cluster and similarity < 0.70, C is NOT falsely flagged
    res_c = registry.register_and_check("session_202", "STUC", "22BCSE203", b64_b, cluster_peers={"STUD"})
    assert res_c.is_duplicate is False

    # Student B (same desk cluster as A) registers with proxy image
    # Since B IS in A's cluster, the sensitive threshold (0.363) is used and B is caught matching A
    res_b = registry.register_and_check("session_202", "STUB", "22BCSE202", b64_proxy, cluster_peers={"STUA"})
    assert res_b.is_duplicate is True
    assert res_b.matched_student_key == "STUA"

    # Purge
    registry.clear_session("session_202")


# Verifies debug session auto-provisioning and duplicate face detection via API endpoint
@pytest.mark.anyio
async def test_debug_session_auto_provision_and_duplicate_rejection():
    import base64
    import io
    from PIL import Image
    from httpx import AsyncClient, ASGITransport

    img = Image.new("RGB", (100, 100), color=(180, 140, 120))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    b64_img = base64.b64encode(buf.getvalue()).decode("utf-8")

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # First student check-in to auto-provisioned debug session AUTO-101
        res1 = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            json={
                "student_id": "STUD1",
                "neighbors": ["PEER-A1", "PEER-B2"],
                "session_salt": "DEMO1",
                "face_image_base64": b64_img,
            },
        )
        assert res1.status_code == 200

        # Second student check-in with the exact same face image (proxy attempt)
        res2 = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            json={
                "student_id": "STUD2",
                "neighbors": ["PEER-A1", "PEER-B2"],
                "session_salt": "DEMO1",
                "face_image_base64": b64_img,
            },
        )
        assert res2.status_code == 403
        assert "Biometric proxy rejected" in res2.json()["detail"]


# Verifies Layer 4: Ultrasonic Proximity Handshake proxy rejection
@pytest.mark.anyio
async def test_ultrasonic_proximity_rejection():
    from httpx import AsyncClient, ASGITransport

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Student submits with ultrasonic proximity detected to another peer on same desk
        res = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            json={
                "student_id": "STUD3",
                "neighbors": ["STUD4"],
                "session_salt": "DEMO1",
                "ultrasonic_proximity_detected": True,
                "co_located_peer_id": "STUD4",
            },
        )
        assert res.status_code == 403
        assert "Ultrasonic proximity rejected" in res.json()["detail"]
        assert "STUD4" in res.json()["detail"]


# Verifies Layer 2: Behaviour Analysis divided attention proxy rejection
@pytest.mark.anyio
async def test_divided_attention_proxy_rejection():
    from httpx import AsyncClient, ASGITransport

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        res = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            json={
                "student_id": "STUD5",
                "neighbors": ["PEER-X"],
                "session_salt": "DEMO1",
                "divided_attention_detected": True,
            },
        )
        assert res.status_code == 403
        assert "Divided attention proxy detected" in res.json()["detail"]





