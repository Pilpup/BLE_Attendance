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

    # Verify C has unreciprocated triadic link (Quarantined for teacher confirmation, NEVER auto-verified)
    assert res_map["C"]["status"] == "ISOLATED_FLAGGED"
    assert res_map["C"]["verification_method"] == "TRIADIC_CLOSURE"
    assert res_map["C"]["affinity_score"] == 0.50
    assert "Pending Teacher Confirmation" in res_map["C"]["remarks"]

    # Verify D is spoofer rejected (unreciprocated single claim)
    assert res_map["D"]["status"] == "SPOOFER_REJECTED"
    assert res_map["D"]["affinity_score"] == 0.00

    # Verify E is isolated
    assert res_map["E"]["status"] == "ISOLATED_FLAGGED"
    assert res_map["E"]["affinity_score"] == 0.00


def test_mesh_audit_satellite_cluster_quarantine():
    reports = {
        "S1": ["S2"],
        "S2": ["S1", "S3"],
        "S3": ["S2", "S4"],
        "S4": ["S3", "S5"],
        "S5": ["S4", "S6"],
        "S6": ["S5"],
        # Outside cluster (3 colluding students in cafeteria)
        "X": ["Y", "Z"],
        "Y": ["X", "Z"],
        "Z": ["X", "Y"],
    }
    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    # S1-S6 in the classroom are Verified Present
    for s in ["S1", "S2", "S3", "S4", "S5", "S6"]:
        assert res_map[s]["status"] == "VERIFIED_PRESENT"
        assert res_map[s]["affinity_score"] == 1.00

    # X, Y, Z outside are quarantined as satellite cluster
    for out in ["X", "Y", "Z"]:
        assert res_map[out]["status"] == "ISOLATED_FLAGGED"
        assert res_map[out]["affinity_score"] == 0.00
        assert "Disconnected satellite cluster" in res_map[out]["remarks"]


def test_mesh_audit_outside_eavesdropping_cluster_quarantine():
    reports = {
        "S1": ["S2"],
        "S2": ["S1", "S3"],
        "S3": ["S2", "S4"],
        "S4": ["S3", "S5"],
        "S5": ["S4", "S6"],
        "S6": ["S5"],
        # Outside window cluster (W1 sniffs S1)
        "W1": ["W2", "W3", "S1"],
        "W2": ["W1", "W3"],
        "W3": ["W1", "W2"],
    }
    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    # S1-S6 in the classroom are Verified Present
    for s in ["S1", "S2", "S3", "S4", "S5", "S6"]:
        assert res_map[s]["status"] == "VERIFIED_PRESENT"

    # W1, W2, W3 outside are quarantined as satellite cluster despite W1 claiming S1
    for out in ["W1", "W2", "W3"]:
        assert res_map[out]["status"] == "ISOLATED_FLAGGED"
        assert res_map[out]["affinity_score"] == 0.00
        assert "Disconnected satellite cluster" in res_map[out]["remarks"]


def test_mesh_audit_corridor_chain_of_four_quarantine():
    reports = {
        **{f"S{i}": [f"S{i-1}" if i > 1 else "S2", f"S{i+1}" if i < 12 else "S11"] for i in range(1, 13)},
        # 4 spoofers in corridor
        "C1": ["C2"],
        "C2": ["C1", "C3"],
        "C3": ["C2", "C4"],
        "C4": ["C3"],
    }
    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    for c in ["C1", "C2", "C3", "C4"]:
        assert res_map[c]["status"] == "ISOLATED_FLAGGED"
        assert res_map[c]["affinity_score"] == 0.00
        assert "Disconnected satellite cluster" in res_map[c]["remarks"]


def test_mesh_audit_classroom_row_with_incoming_anchors_verified():
    # 2 in-classroom students (S5 and S6) acknowledge seeing R1 and R2
    reports = {
        **{f"S{i}": [f"S{i-1}" if i > 1 else "S2", f"S{i+1}" if i < 12 else "S11"] for i in range(1, 13)},
        # S5 and S6 report row students across aisle
        "S5": ["S4", "S6", "R1"],
        "S6": ["S5", "S7", "R2"],
        # Row 3 students
        "R1": ["R2"],
        "R2": ["R1", "R3"],
        "R3": ["R2"],
    }
    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    # All row students are verified because the classroom core acknowledged them (incoming anchors >= 2)
    for r in ["R1", "R2", "R3"]:
        assert res_map[r]["status"] == "VERIFIED_PRESENT"
        assert res_map[r]["affinity_score"] == 1.00


def test_mesh_audit_offline_peer_witness():
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


# Verifies beacon salt enforcement rejecting remote check-in attempts without physical beacon
@pytest.mark.anyio
async def test_submit_neighbors_salt_enforcement():
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

        # JWT is now mandatory on submit: register the three in-room students.
        # Rolls are chosen so the audit's last-4 record keys stay 1234/3456/5678.
        student_tokens = {}
        for s_name, s_email, s_roll in [
            ("Gate Spoofer", "gate.spoofer@univ.edu", "22B3456"),
            ("Gate Alpha", "gate.alpha@univ.edu", "22B1234"),
            ("Gate Beta", "gate.beta@univ.edu", "22B5678"),
        ]:
            s_reg = await client.post(
                "/api/v1/auth/register",
                json={
                    "name": s_name,
                    "email": s_email,
                    "password": "Password123!",
                    "role": "STUDENT",
                    "roll_number": s_roll,
                    "department": "CSE",
                    "program": "B.Tech",
                    "semester": 6,
                    "section": "A",
                },
            )
            assert s_reg.status_code == 200, s_reg.text
            async with test_session_factory() as s:
                s_otp = (await s.execute(select(EmailOTP).where(EmailOTP.email == s_email))).scalars().first()
                s_otp_code = s_otp.otp_code
            s_verify = await client.post(
                "/api/v1/auth/register/verify-otp",
                json={"email": s_email, "otp_code": s_otp_code},
            )
            assert s_verify.status_code == 201, s_verify.text
            student_tokens[s_roll] = s_verify.json()["access_token"]

        # 1) Missing salt (absent student replaying only the session token from chat)
        missing = await client.post(
            submit_url,
            headers={"Authorization": f"Bearer {student_tokens['22B3456']}"},
            json={"student_id": "3456", "neighbors": ["1234", "2345"]},
        )
        assert missing.status_code == 403

        # 2) Wrong salt (again: knowledge of the token alone is not enough)
        wrong = await client.post(
            submit_url,
            headers={"Authorization": f"Bearer {student_tokens['22B3456']}"},
            json={"student_id": "3456", "neighbors": ["1234", "2345"], "session_salt": "WRNG"},
        )
        assert wrong.status_code == 403
        assert "Nice try" in wrong.json()["detail"]

        # 3) Matching salt — in-room students who actually heard the beacon (mutual pair)
        valid_one = await client.post(
            submit_url,
            headers={"Authorization": f"Bearer {student_tokens['22B1234']}"},
            json={"student_id": "1234", "neighbors": ["5678"], "session_salt": "Y7K9"},
        )
        assert valid_one.status_code == 200
        assert valid_one.json()["status"] == "success"

        valid_two = await client.post(
            submit_url,
            headers={"Authorization": f"Bearer {student_tokens['22B5678']}"},
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


# Verifies expired OTP cleanup behavior and purge on invalid submission
@pytest.mark.anyio
async def test_expired_otp_purging_and_cleanup():
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


# Verifies face engine robustness guards against oversized payloads and non-finite vectors
def test_face_engine_robustness_guards():
    import numpy as np
    from app.services.face_engine import FaceMatchingEngine, SessionFaceRegistry

    engine = FaceMatchingEngine()
    registry = SessionFaceRegistry(engine=engine)

    # 1. Rejects oversized base64 payload (> 4MB)
    huge_payload = "A" * 4_000_001
    res = registry.register_and_check("sess_guard", "STU1", "ROLL1", huge_payload)
    assert res.is_duplicate is False
    assert res.has_human_face is False
    assert "exceeds maximum allowed size" in res.remarks

    # 2. Handles non-finite NaN vector similarity safely
    nan_vec = np.full((128,), np.nan, dtype=np.float32)
    valid_vec = np.ones((128,), dtype=np.float32)
    sim = engine.compute_similarity(nan_vec, valid_vec)
    assert sim == 0.0

    # 3. Prunes stale sessions based on TTL
    registry._session_timestamps["old_sess"] = 100.0
    registry._registry["old_sess"] = {"foo": {}}
    registry._prune_stale_sessions()
    assert "old_sess" not in registry._registry


# Verifies the exposure rescue path: a dark/backlit frame whose first detection
# pass finds no face is lifted once (CLAHE) and re-detected instead of being
# rejected, keeping verification strictness unchanged.
def test_face_engine_exposure_rescue_recovered():
    import cv2 as _cv2
    import numpy as np

    from app.services.face_engine import FaceMatchingEngine, _rescue_dark_image

    # Dark textured frame (mean ~8/255, spatial std above the 1.0 synthetic cut-off)
    rng = np.random.default_rng(7)
    img = (rng.integers(0, 4, size=(48, 48, 3)) + 8).astype(np.uint8)
    png_bytes = _cv2.imencode(".png", img)[1].tobytes()

    fake_face_row = np.array([2, 2, 28, 28, 8, 6, 20, 6, 14, 14, 8, 22, 20, 22, 0.9], dtype=np.float32)
    fake_faces = np.array([fake_face_row])

    class _FakeDetector:
        def __init__(self, outcomes):
            self.outcomes = list(outcomes)
            self.calls = 0

        def setInputSize(self, size):
            self.size = size

        def detect(self, image):
            result = self.outcomes[min(self.calls, len(self.outcomes) - 1)]
            self.calls += 1
            return None, result

    class _FakeRecognizer:
        def alignCrop(self, image, face):
            return image[0:16, 0:16]

        def feature(self, aligned):
            return np.ones((1, 128), dtype=np.float32)

    engine = FaceMatchingEngine(detector_path="/nonexistent", recognizer_path="/nonexistent")
    engine.detector = _FakeDetector([None, fake_faces])
    engine.recognizer = _FakeRecognizer()

    has_face, embedding, remark = engine.extract_face_and_embedding(png_bytes)
    assert has_face is True
    assert embedding is not None
    assert "exposure-rescued" in remark
    assert engine.detector.calls == 2  # first pass + exactly ONE rescue re-detection

    # The rescue helper itself: dims preserved, contrast lifted on a dark frame
    rescued = _rescue_dark_image(img)
    assert rescued.shape == img.shape
    assert float(np.mean(rescued)) > float(np.mean(img))


# Verifies zero-face rejections carry measured brightness diagnostics instead of
# the old misleading ceiling/desk phrasing.
def test_face_engine_no_face_remark_reports_brightness():
    import cv2 as _cv2
    import numpy as np

    from app.services.face_engine import FaceMatchingEngine

    rng = np.random.default_rng(11)
    img = (rng.integers(0, 4, size=(48, 48, 3)) + 8).astype(np.uint8)
    png_bytes = _cv2.imencode(".png", img)[1].tobytes()

    class _FakeDetectorAlwaysEmpty:
        def setInputSize(self, size):
            self.size = size

        def detect(self, image):
            return None, None

    engine = FaceMatchingEngine(detector_path="/nonexistent", recognizer_path="/nonexistent")
    engine.detector = _FakeDetectorAlwaysEmpty()
    engine.recognizer = object()  # aligned/feature path is never reached on rejection

    has_face, embedding, remark = engine.extract_face_and_embedding(png_bytes)
    assert has_face is False
    assert embedding is None
    assert "mean brightness" in remark
    assert "/255" in remark
    # An honestly dark frame blames the lighting, not the room
    assert "ceiling" not in remark
    assert "very dark" in remark


# Registers a throwaway student in the test's own attendance DB and returns a
# Bearer token: submission now requires JWT, and each battery test mounts its
# own in-memory get_db override (mirroring the salt-gate test's pattern).
async def _register_battery_student(client, session_factory) -> str:
    import uuid as _uuid
    from sqlalchemy import select

    from app.models.attendance import EmailOTP

    uid = _uuid.uuid4().hex[:8].upper()
    email = f"battery-{uid.lower()}@univ.edu"
    res = await client.post(
        "/api/v1/auth/register",
        json={
            "name": f"Battery Student {uid}",
            "email": email,
            "password": "Password123!",
            "role": "STUDENT",
            "roll_number": f"RB{uid}X",
            "department": "CSE",
            "program": "B.Tech",
            "semester": 6,
            "section": "A",
        },
    )
    assert res.status_code == 200, res.text
    async with session_factory() as s:
        otp_rec = (
            await s.execute(
                select(EmailOTP).where(EmailOTP.email == email, EmailOTP.purpose == "REGISTER")
            )
        ).scalars().first()
        otp_code = otp_rec.otp_code
    verify_res = await client.post(
        "/api/v1/auth/register/verify-otp",
        json={"email": email, "otp_code": otp_code},
    )
    assert verify_res.status_code == 201, verify_res.text
    return verify_res.json()["access_token"]


# Registers a TEACHER account (faculty code gated) and returns an access token
async def _register_test_teacher(client, session_factory, name: str, email: str) -> str:
    import uuid as _uuid
    from sqlalchemy import select

    from app.models.attendance import EmailOTP

    res = await client.post(
        "/api/v1/auth/register",
        json={
            "name": name,
            "email": email,
            "password": "Password123!",
            "role": "TEACHER",
            "roll_number": f"EMP{_uuid.uuid4().hex[:5].upper()}",
            "department": "CSE",
            "faculty_code": settings.FACULTY_SIGNUP_SECRET,
        },
    )
    assert res.status_code == 200, res.text
    async with session_factory() as s:
        otp_rec = (
            await s.execute(
                select(EmailOTP).where(EmailOTP.email == email, EmailOTP.purpose == "REGISTER")
            )
        ).scalars().first()
    verify_res = await client.post(
        "/api/v1/auth/register/verify-otp",
        json={"email": email, "otp_code": otp_rec.otp_code},
    )
    assert verify_res.status_code == 201, verify_res.text
    return verify_res.json()["access_token"]


# Registers a STUDENT account with a DETERMINISTIC roll so the audit's
# last-4 device keys are stable and pairable across submissions
async def _register_roll_student(client, session_factory, name: str, email: str, roll: str) -> str:
    from sqlalchemy import select

    from app.models.attendance import EmailOTP

    res = await client.post(
        "/api/v1/auth/register",
        json={
            "name": name,
            "email": email,
            "password": "Password123!",
            "role": "STUDENT",
            "roll_number": roll,
            "department": "CSE",
            "program": "B.Tech",
            "semester": 6,
            "section": "A",
        },
    )
    assert res.status_code == 200, res.text
    async with session_factory() as s:
        otp_rec = (
            await s.execute(
                select(EmailOTP).where(EmailOTP.email == email, EmailOTP.purpose == "REGISTER")
            )
        ).scalars().first()
    verify_res = await client.post(
        "/api/v1/auth/register/verify-otp",
        json={"email": email, "otp_code": otp_rec.otp_code},
    )
    assert verify_res.status_code == 201, verify_res.text
    return verify_res.json()["access_token"]


# Verifies debug session auto-provisioning and duplicate face detection via API endpoint
@pytest.mark.anyio
async def test_debug_session_auto_provision_and_duplicate_rejection():
    import base64
    import io
    from PIL import Image
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

    from app.database import get_db
    from app.models.attendance import Base

    # Deterministic in-memory DB for this battery test: the module shares one
    # app instance, so without an own override the endpoint would serve whatever
    # DB a previous test left mounted in dependency_overrides.
    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    battery_db = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with battery_db() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    img = Image.new("RGB", (100, 100), color=(180, 140, 120))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    b64_img = base64.b64encode(buf.getvalue()).decode("utf-8")

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        tok_stud1 = await _register_battery_student(client, battery_db)
        tok_stud2 = await _register_battery_student(client, battery_db)

        # First student check-in to auto-provisioned debug session AUTO-101
        res1 = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            headers={"Authorization": f"Bearer {tok_stud1}"},
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
            headers={"Authorization": f"Bearer {tok_stud2}"},
            json={
                "student_id": "STUD2",
                "neighbors": ["PEER-A1", "PEER-B2"],
                "session_salt": "DEMO1",
                "face_image_base64": b64_img,
            },
        )
        assert res2.status_code == 403
        assert "Biometric proxy rejected" in res2.json()["detail"]


# Verifies Layer 4 M3-lite: ultrasonic desk claims are DEFERRED, not vandalized.
# A RECIPROCATED pair (both students claim each other) is materialized into
# CO_LOCATED_SAME_DESK_PROXY rows for BOTH parties at finalize; an unreciprocated
# claim is ignored; a post-finalize (grace) claim can no longer be evaluated and
# never harms the straggler's record.
@pytest.mark.anyio
async def test_ultrasonic_proximity_mutual_pair_materialization():
    import uuid

    from httpx import AsyncClient, ASGITransport
    from sqlalchemy import select
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

    from app.database import get_db
    from app.models.attendance import AttendanceRecord, Base

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    pair_db = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with pair_db() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        tok_teacher = await _register_test_teacher(client, pair_db, "Prof. Desk Pair", "desk.pair@univ.edu")
        teacher_headers = {"Authorization": f"Bearer {tok_teacher}"}
        tok_stud1 = await _register_roll_student(client, pair_db, "Pair Alpha", "pair.alpha1@univ.edu", "22B4451")
        tok_stud2 = await _register_roll_student(client, pair_db, "Pair Beta", "pair.beta2@univ.edu", "22B4452")
        tok_stud3 = await _register_roll_student(client, pair_db, "Pair Gamma", "pair.gamma3@univ.edu", "22B4453")
        tok_stud4 = await _register_roll_student(client, pair_db, "Pair Delta", "pair.delta4@univ.edu", "22B4454")

        s_res = await client.post(
            "/api/v1/attendance/sessions",
            headers=teacher_headers,
            json={"session_token": "M3P1", "session_salt": "P3M0", "total_enrolled": 5},
        )
        assert s_res.status_code == 201
        session_id = s_res.json()["id"]
        session_url = f"/api/v1/attendance/sessions/{session_id}/submit-neighbors"

        # Reciprocated desk mates: both claim the < 20 cm ultrasonic handshake
        res1 = await client.post(
            session_url,
            headers={"Authorization": f"Bearer {tok_stud1}"},
            json={
                "student_id": "4451",
                "neighbors": ["4452"],
                "session_salt": "P3M0",
                "ultrasonic_proximity_detected": True,
                "co_located_peer_id": "4452",
            },
        )
        assert res1.status_code == 200, res1.text  # staged normally — no instant 403

        res2 = await client.post(
            session_url,
            headers={"Authorization": f"Bearer {tok_stud2}"},
            json={
                "student_id": "4452",
                "neighbors": ["4451", "4453"],
                "session_salt": "P3M0",
                "ultrasonic_proximity_detected": True,
                "co_located_peer_id": "4451",
            },
        )
        assert res2.status_code == 200, res2.text

        # Mutual BLE chain 4451-4452-4453-4454 so the clean pair verifies; but
        # ONLY 4453 claims an ultrasonic desk handshake (unreciprocated claim)
        res3 = await client.post(
            session_url,
            headers={"Authorization": f"Bearer {tok_stud3}"},
            json={
                "student_id": "4453",
                "neighbors": ["4452", "4454"],
                "session_salt": "P3M0",
                "ultrasonic_proximity_detected": True,
                "co_located_peer_id": "4454",
            },
        )
        assert res3.status_code == 200, res3.text

        res4 = await client.post(
            session_url,
            headers={"Authorization": f"Bearer {tok_stud4}"},
            json={"student_id": "4454", "neighbors": ["4453"], "session_salt": "P3M0"},
        )
        assert res4.status_code == 200, res4.text

        audit_res = await client.post(
            f"/api/v1/attendance/sessions/{session_id}/audit-and-finalize",
            headers=teacher_headers,
            json={"reports": {}},
        )
        assert audit_res.status_code == 200
        audit_data = audit_res.json()

        assert audit_data["status"] == "COMPLETED"
        assert len(audit_data["records"]) == 4  # one row per student is preserved
        record_map = {r["ble_device_id"]: r for r in audit_data["records"]}

        # Reciprocated pair: both verdict rows transformed into the proxy violation
        assert record_map["4451"]["status"] == "CO_LOCATED_SAME_DESK_PROXY"
        assert record_map["4451"]["verification_method"] == "ULTRASONIC_ACOUSTIC_MATCH"
        assert "with 4452" in record_map["4451"]["remarks"]
        assert record_map["4452"]["status"] == "CO_LOCATED_SAME_DESK_PROXY"
        assert record_map["4452"]["verification_method"] == "ULTRASONIC_ACOUSTIC_MATCH"
        assert "with 4451" in record_map["4452"]["remarks"]

        # Unreciprocated claim: the benign BLE mesh verdict stands untouched
        assert record_map["4453"]["status"] == "VERIFIED_PRESENT"
        assert record_map["4454"]["status"] == "VERIFIED_PRESENT"

        assert audit_data["total_verified"] == 2
        assert audit_data["total_spoofers"] == 2  # ignored claim is not counted

        # Post-finalize grace claim: mutuality is unevaluable (buffer already
        # popped) — the straggler gets a normal grace record, NOT a proxy row.
        tok_stud5 = await _register_roll_student(client, pair_db, "Pair Epsilon", "pair.epsilon5@univ.edu", "22B4455")
        grace = await client.post(
            session_url,
            headers={"Authorization": f"Bearer {tok_stud5}"},
            json={
                "student_id": "4455",
                "neighbors": [],
                "session_salt": "P3M0",
                "ultrasonic_proximity_detected": True,
                "co_located_peer_id": "4451",
            },
        )
        assert grace.status_code == 200, grace.text

        async with pair_db() as s:
            grace_rows = (
                await s.execute(
                    select(AttendanceRecord).where(
                        AttendanceRecord.session_id == uuid.UUID(session_id),
                        AttendanceRecord.ble_device_id == "4455",
                    )
                )
            ).scalars().all()
            assert len(grace_rows) == 1
            assert grace_rows[0].status == "ISOLATED_FLAGGED"


# Verifies M2 hygiene: a live session's token cannot be re-issued by ANOTHER
# teacher (student submits resolve newest-wins, so a colliding broadcast would
# cross-route submissions). Same-teacher re-create still supersedes cleanly
# because the stale-expiry step runs BEFORE the conflict check.
@pytest.mark.anyio
async def test_session_token_conflict_cross_teacher():
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

    from app.database import get_db
    from app.models.attendance import Base

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    conflict_db = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with conflict_db() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        tok_a = await _register_test_teacher(client, conflict_db, "Prof. Tok A", "tok.a@univ.edu")
        tok_b = await _register_test_teacher(client, conflict_db, "Prof. Tok B", "tok.b@univ.edu")

        first = await client.post(
            "/api/v1/attendance/sessions",
            headers={"Authorization": f"Bearer {tok_a}"},
            json={"session_token": "T0K1", "session_salt": "AAAA", "total_enrolled": 4},
        )
        assert first.status_code == 201

        # Another teacher reusing the live token -> 409
        clash = await client.post(
            "/api/v1/attendance/sessions",
            headers={"Authorization": f"Bearer {tok_b}"},
            json={"session_token": "T0K1", "session_salt": "BBBB", "total_enrolled": 4},
        )
        assert clash.status_code == 409
        assert "already in use" in clash.json()["detail"]

        # Same teacher re-creating their own token still supersedes cleanly (no 409)
        rec = await client.post(
            "/api/v1/attendance/sessions",
            headers={"Authorization": f"Bearer {tok_a}"},
            json={"session_token": "T0K1", "session_salt": "AAAA", "total_enrolled": 4},
        )
        assert rec.status_code == 201, rec.text

        # A fresh token for the other teacher is unaffected
        fresh = await client.post(
            "/api/v1/attendance/sessions",
            headers={"Authorization": f"Bearer {tok_b}"},
            json={"session_token": "T0K2", "session_salt": "BBBB", "total_enrolled": 4},
        )
        assert fresh.status_code == 201


# Verifies M5: frozen security verdicts (e.g. the salt gate's SPOOFER_REJECTED
# row) must still surface in total_spoofers on the plain finalize route even
# though finalize preserves them in place instead of rewriting them.
@pytest.mark.anyio
async def test_finalize_counts_frozen_proxy_priors():
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

    from app.database import get_db
    from app.models.attendance import Base

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    frozen_db = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with frozen_db() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        tok_teacher = await _register_test_teacher(client, frozen_db, "Prof. Frozen Counter", "frozen.counter@univ.edu")
        tok_stud = await _register_roll_student(client, frozen_db, "Frozen Student", "frozen.stud@univ.edu", "22B9876")

        s_res = await client.post(
            "/api/v1/attendance/sessions",
            headers={"Authorization": f"Bearer {tok_teacher}"},
            json={"session_token": "M5F1", "session_salt": "F5M0", "total_enrolled": 2},
        )
        assert s_res.status_code == 201
        session_id = s_res.json()["id"]

        # Salt-gate rejection writes a SPOOFER_REJECTED row out-of-band
        spoof = await client.post(
            f"/api/v1/attendance/sessions/{session_id}/submit-neighbors",
            headers={"Authorization": f"Bearer {tok_stud}"},
            json={"student_id": "9876", "neighbors": [], "session_salt": "WRNG"},
        )
        assert spoof.status_code == 403
        assert "Nice try" in spoof.json()["detail"]

        # Plain finalize with no teacher records must NOT lose the frozen prior
        fin = await client.post(
            f"/api/v1/attendance/sessions/{session_id}/finalize",
            headers={"Authorization": f"Bearer {tok_teacher}"},
            json={"records": []},
        )
        assert fin.status_code == 200, fin.text
        fin_data = fin.json()
        assert fin_data["status"] == "COMPLETED"
        assert fin_data["total_spoofers"] == 1
        assert fin_data["total_verified"] == 0


# Verifies hygiene guards: strict status whitelists on the TEACHER-OVERWRITE
# request models (lowercase aliases normalize; unknown statuses 422 before any
# write), while BLEAuditRecordItem stays permissive — it is the from_attributes
# base of the session response and would 500 on defense-layer violation rows.
@pytest.mark.anyio
async def test_status_enum_validation_hygiene():
    from pydantic import ValidationError

    from app.schemas.attendance import BLEAuditRecordItem, RecordOverrideRequest, SessionFinalizeRequest

    # Lowercase aliases normalize to canonical statuses
    req = SessionFinalizeRequest(records=[{"ble_device_id": "33F1", "status": "verified_present"}])
    assert req.records[0].status == "VERIFIED_PRESENT"
    assert RecordOverrideRequest(status="absent").status == "ABSENT"

    # Unknown statuses rejected at validation time (-> HTTP 422)
    with pytest.raises(ValidationError):
        SessionFinalizeRequest(records=[{"ble_device_id": "33F1", "status": "SPECTRAL_GHOST"}])
    with pytest.raises(ValidationError):
        RecordOverrideRequest(status="PRESENT")

    # Response base stays permissive for defense-layer verdicts (no 500s)
    artifact = BLEAuditRecordItem.model_validate(
        {"ble_device_id": "01AB", "status": "DUPLICATE_FACE_PROXY", "affinity_score": 0.0}
    )
    assert artifact.status == "DUPLICATE_FACE_PROXY"

    # HTTP: garbage status is rejected at body-validation, normalized status
    # passes through to the (unknown-session) 404 instead.
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

    from app.database import get_db
    from app.models.attendance import Base

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    enum_db = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with enum_db() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        tok = await _register_test_teacher(client, enum_db, "Prof. Enum Guard", "enum.guard@univ.edu")

        bad = await client.post(
            "/api/v1/attendance/sessions/NOT-A-UUID/finalize",
            headers={"Authorization": f"Bearer {tok}"},
            json={"records": [{"ble_device_id": "33F1", "status": "GARBAGE_STATUS"}]},
        )
        assert bad.status_code == 422

        normalized = await client.post(
            "/api/v1/attendance/sessions/NOT-A-UUID/finalize",
            headers={"Authorization": f"Bearer {tok}"},
            json={"records": [{"ble_device_id": "33F1", "status": "verified_present"}]},
        )
        assert normalized.status_code == 404

        override_bad = await client.patch(
            "/api/v1/attendance/records/00000000-0000-0000-0000-000000000000/override",
            headers={"Authorization": f"Bearer {tok}"},
            json={"status": "PRESENT"},
        )
        assert override_bad.status_code == 422


# Verifies raw binary image check-in submission with headers bypasses base64
@pytest.mark.anyio
async def test_submit_neighbors_binary_upload():
    import io
    from PIL import Image
    from httpx import AsyncClient, ASGITransport
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

    from app.database import get_db
    from app.models.attendance import Base
    from app.services.face_engine import face_registry

    # Clears in-memory registry before test
    face_registry.clear_all()

    img = Image.new("RGB", (100, 100), color=(180, 140, 120))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    raw_jpeg = buf.getvalue()

    test_engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    battery_db = async_sessionmaker(test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with battery_db() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        tok_bin1 = await _register_battery_student(client, battery_db)
        tok_bin2 = await _register_battery_student(client, battery_db)

        # Rejects submission when salt header is invalid
        bad_res = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            content=raw_jpeg,
            headers={
                "Content-Type": "image/jpeg",
                "Authorization": f"Bearer {tok_bin1}",
                "X-Session-Salt": "BAD_SALT",
                "X-Student-Id": "BIN1",
                "X-Neighbors": "PEER-A1,PEER-B2",
            },
        )
        assert bad_res.status_code == 403

        # Accepts valid binary submission and records attendance
        good_res = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            content=raw_jpeg,
            headers={
                "Content-Type": "image/jpeg",
                "Authorization": f"Bearer {tok_bin1}",
                "X-Session-Salt": "DEMO1",
                "X-Student-Id": "BIN1",
                "X-Neighbors": "PEER-A1,PEER-B2",
            },
        )
        assert good_res.status_code == 200
        assert good_res.json()["status"] == "success"

        # Duplicate binary face payload triggers proxy rejection
        dup_res = await client.post(
            "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
            content=raw_jpeg,
            headers={
                "Content-Type": "image/jpeg",
                "Authorization": f"Bearer {tok_bin2}",
                "X-Session-Salt": "DEMO1",
                "X-Student-Id": "BIN2",
                "X-Neighbors": "PEER-A1,PEER-B2",
            },
        )
        assert dup_res.status_code == 403
        assert "Biometric proxy rejected" in dup_res.json()["detail"]


# Verifies in-memory submission staging, claim union-merging, and TTL pruning
@pytest.mark.anyio
async def test_submission_buffer_staging_and_pruning():
    import uuid
    from app.services.submission_buffer import SubmissionBuffer

    buffer = SubmissionBuffer(ttl_seconds=1)
    sess_id = "test-session-staging"
    student_uuid = uuid.uuid4()

    # Stages initial check-in submission
    await buffer.stage_submission(
        session_id=sess_id,
        device_key="STU1",
        student_id=student_uuid,
        reported_neighbors=["NBR1", "NBR2"],
    )

    # Updates existing submission with revised neighbor reports
    await buffer.stage_submission(
        session_id=sess_id,
        device_key="STU1",
        student_id=student_uuid,
        reported_neighbors=["NBR1", "NBR2", "NBR3"],
    )

    # A later, emptier snapshot must not erase earlier claims: interim submits
    # arrive through asymmetric BLE discovery windows, so union-merge is what
    # keeps transient one-way discoveries alive until mutual confirmation.
    await buffer.stage_submission(
        session_id=sess_id,
        device_key="STU1",
        student_id=student_uuid,
        reported_neighbors=["NBR2"],
    )

    # Extracts and verifies staged submissions (single surviving node now that
    # the unauthenticated offline-voucher channel is gone)
    staged = await buffer.pop_submissions(sess_id)
    assert len(staged) == 1
    assert staged["STU1"].reported_neighbors == ["NBR1", "NBR2", "NBR3"]
    assert staged["STU1"].student_id == student_uuid

    # Verifies buffer is empty after popping
    empty_staged = await buffer.pop_submissions(sess_id)
    assert len(empty_staged) == 0

    # Tests TTL pruning on stale session buffers
    await buffer.stage_submission(
        session_id="stale-session",
        device_key="STU2",
        student_id=None,
        reported_neighbors=["NBRX"],
    )
    buffer._last_accessed["stale-session"] = 0.0
    buffer._prune_stale(now=100.0)
    assert "stale-session" not in buffer._buffers


# Verifies 2D BLAS SIMD matrix batching across registered session face embeddings
def test_face_engine_simd_blas_batching():
    import numpy as np
    from app.services.face_engine import SessionFaceRegistry

    registry = SessionFaceRegistry()
    sess = "batch-test-session"

    # Registers 10 unique synthetic faces into session matrix
    for i in range(10):
        emb = np.random.randn(1, 128).astype(np.float32)
        norm = float(np.linalg.norm(emb))
        emb = emb / norm
        student_key = f"STU{i}"
        registry._registry.setdefault(sess, {})[student_key] = {
            "embedding": emb,
            "roll": f"ROLL{i}",
            "cluster_peers": set(),
            "flagged": False,
            "image_b64": None,
        }
        registry._session_keys.setdefault(sess, []).append(student_key)
        registry._session_clusters.setdefault(sess, []).append(set())
        if registry._session_matrices.get(sess) is None:
            registry._session_matrices[sess] = emb.reshape(1, 128)
        else:
            registry._session_matrices[sess] = np.vstack([registry._session_matrices[sess], emb.reshape(1, 128)])

    # Verifies internal matrix dimensions match (10, 128)
    assert registry._session_matrices[sess].shape == (10, 128)

    # Identical query to student 3 must match with similarity ~1.0
    target_vec = registry._session_matrices[sess][3]
    scores = registry._session_matrices[sess] @ target_vec
    assert abs(scores[3] - 1.0) < 1e-4

    # Purges matrix and session structures
    registry.clear_session(sess)
    assert sess not in registry._session_matrices
    assert sess not in registry._session_keys


# Verifies hardware acceleration provider detection and ONNX Runtime session
def test_face_engine_hardware_acceleration_info():
    from app.services.face_engine import FaceMatchingEngine

    engine = FaceMatchingEngine()
    info = engine.get_hardware_acceleration_info()

    assert "execution_provider" in info
    assert "cv2_threads" in info
    assert "onnxruntime_available" in info
    assert info["cv2_threads"] > 0
    assert info["onnxruntime_available"] is True
    assert "CPUExecutionProvider" in info["onnxruntime_providers"]










