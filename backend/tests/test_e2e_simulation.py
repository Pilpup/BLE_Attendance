import asyncio
import uuid
import pytest
from httpx import AsyncClient, ASGITransport
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

from app.main import app
from app.database import get_db
from app.models.attendance import Base, EmailOTP
from app.config import settings
from sqlalchemy import select


async def _register_and_get_token(client: AsyncClient, session_factory, payload: dict) -> str:
    res = await client.post("/api/v1/auth/register", json=payload)
    assert res.status_code == 200, res.text
    email = payload["email"]
    async with session_factory() as s:
        otp_rec = (await s.execute(select(EmailOTP).where(EmailOTP.email == email, EmailOTP.purpose == "REGISTER"))).scalars().first()
        otp_code = otp_rec.otp_code
    verify_res = await client.post(
        "/api/v1/auth/register/verify-otp",
        json={"email": email, "otp_code": otp_code}
    )
    assert verify_res.status_code == 201, verify_res.text
    return verify_res.json()["access_token"]


@pytest.mark.anyio
async def test_30_student_classroom_mesh_simulation():
    # Setup isolated in-memory test database
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
        # Phase 1: Teacher Registration & Verification
        teacher_token = await _register_and_get_token(
            client,
            test_session_factory,
            {
                "name": "Prof. Vikram Rao",
                "email": "vikram.rao@univ.edu",
                "password": "TeacherPassword123!",
                "role": "TEACHER",
                "roll_number": "EMP101",
                "department": "CSE",
                "faculty_code": settings.FACULTY_SIGNUP_SECRET,
            },
        )
        teacher_headers = {"Authorization": f"Bearer {teacher_token}"}

        # Phase 2: Register 25 Online Students + 1 Spoofer
        online_student_ids = [f"ST{i:02d}" for i in range(1, 26)]
        student_tokens = {}

        for sid in online_student_ids:
            student_tokens[sid] = await _register_and_get_token(
                client,
                test_session_factory,
                {
                    "name": f"Student {sid}",
                    "email": f"{sid.lower()}@univ.edu",
                    "password": "Password123!",
                    "role": "STUDENT",
                    "roll_number": sid,
                    "department": "CSE",
                    "program": "B.Tech",
                    "semester": 6,
                    "section": "A",
                },
            )

        # Register Hallway Spoofer
        spoofer_id = "SPOF"
        spoofer_token = await _register_and_get_token(
            client,
            test_session_factory,
            {
                "name": "Hallway Spoofer",
                "email": "spoofer@univ.edu",
                "password": "Password123!",
                "role": "STUDENT",
                "roll_number": spoofer_id,
                "department": "CSE",
                "program": "B.Tech",
                "semester": 6,
                "section": "A",
            },
        )
        spoofer_headers = {"Authorization": f"Bearer {spoofer_token}"}

        # Phase 3: Teacher starts Attendance Session (salt registered, then broadcast ONLY via BLE)
        session_create_res = await client.post(
            "/api/v1/attendance/sessions",
            headers=teacher_headers,
            json={"session_token": "89F2", "session_salt": "Y7Q9", "total_enrolled": 30},
        )
        assert session_create_res.status_code in [200, 201]
        session_data = session_create_res.json()
        session_id = session_data["id"]
        assert session_data["session_token"] == "89F2"
        assert session_data["status"] == "ACTIVE"

        # Phase 4: Classroom Mesh Simulation
        # Offline students who broadcast BLE relay vouchers:
        # OF01 sits between ST06 and ST07
        # OF02 sits between ST10 and ST11
        # OF03 sits between ST11 and ST12
        # OF04 sits between ST18 and ST19
        offline_vouchers = {
            "OF01": ["ST06", "ST07"],
            "OF02": ["ST10", "ST11"],
            "OF03": ["ST11", "ST12"],
            "OF04": ["ST18", "ST19"],
        }

        # Verify fixed-width packet length constraints (Legacy BLE 24-byte payload ceiling)
        for off_id, nbrs in offline_vouchers.items():
            # Format: [SESS (4)][SELF (4)][N1 (4)][N2 (4)]
            voucher_packet = f"89F2{off_id}" + "".join(nbrs)
            assert len(voucher_packet) == 16
            assert len(voucher_packet.encode("utf-8")) <= 24

        # Model online students submitting reports
        # Desk Clusters:
        # Cluster 1 (ST01-ST05)
        # Cluster 2 (ST06-ST09 with OF01)
        # Cluster 3 (ST10-ST12 with OF02, OF03)
        # Cluster 4 (ST13-ST17)
        # Cluster 5 (ST18-ST21 with OF04)
        # Cluster 6 (ST22-ST25)
        cluster_reports = {
            "ST01": (["ST02", "ST03"], None),
            "ST02": (["ST01", "ST03"], None),
            "ST03": (["ST01", "ST02"], None),
            "ST04": (["ST03", "ST05"], None),
            "ST05": (["ST04"], None),
            "ST06": (["ST07", "OF01"], {"OF01": ["ST06", "ST07"]}),
            "ST07": (["ST06", "OF01"], None),
            "ST08": (["ST07", "ST09"], None),
            "ST09": (["ST08"], None),
            "ST10": (["OF02", "ST11"], {"OF02": ["ST10", "ST11"]}),
            "ST11": (["ST10", "OF02", "OF03"], None),
            "ST12": (["OF03", "ST11"], {"OF03": ["ST11", "ST12"]}),
            "ST13": (["ST14", "ST15"], None),
            "ST14": (["ST13", "ST15"], None),
            "ST15": (["ST13", "ST14"], None),
            "ST16": (["ST15", "ST17"], None),
            "ST17": (["ST16"], None),
            "ST18": (["OF04", "ST19"], {"OF04": ["ST18", "ST19"]}),
            "ST19": (["ST18", "OF04", "ST20"], None),
            "ST20": (["ST19", "ST21"], None),
            "ST21": (["ST20"], None),
            "ST22": (["ST23", "ST24"], None),
            "ST23": (["ST22", "ST24"], None),
            "ST24": (["ST22", "ST23", "ST25"], None),
            "ST25": (["ST24"], None),
        }

        # Online students upload reports ( Beacon salt "Y7Q9" heard in-room from teacher's S: beacon )
        for sid, (nbrs, relayed) in cluster_reports.items():
            headers = {"Authorization": f"Bearer {student_tokens[sid]}"}
            payload = {"neighbors": nbrs, "session_salt": "Y7Q9"}
            if relayed:
                payload["relayed_reports"] = relayed
            sub_res = await client.post(
                "/api/v1/attendance/sessions/89F2/submit-neighbors",
                headers=headers,
                json=payload,
            )
            assert sub_res.status_code == 200

        # Hallway Spoofer uploads an unreciprocated neighbor claim
        # SPOF is inside radio range (hears the beacon salt), so the salt passes;
        # the graph audit still rejects him for the unreciprocated claim.
        spoofer_sub = await client.post(
            "/api/v1/attendance/sessions/89F2/submit-neighbors",
            headers=spoofer_headers,
            json={"neighbors": ["ST01"], "session_salt": "Y7Q9"},
        )
        assert spoofer_sub.status_code == 200

        # Phase 5: Teacher Audits & Finalizes Session
        audit_res = await client.post(
            f"/api/v1/attendance/sessions/{session_id}/audit-and-finalize",
            headers=teacher_headers,
            json={"reports": {}},
        )
        assert audit_res.status_code == 200
        audit_data = audit_res.json()

        assert audit_data["status"] == "COMPLETED"
        assert audit_data["total_verified"] == 29
        assert audit_data["total_spoofers"] == 1

        records = audit_data["records"]
        record_map = {r["ble_device_id"]: r for r in records}

        # Verify all 25 online students are marked present
        for sid in online_student_ids:
            assert sid in record_map
            assert record_map[sid]["status"] == "VERIFIED_PRESENT"
            assert record_map[sid]["affinity_score"] >= 0.50

        # Verify all 4 offline students are marked present via relayed vouchers
        for off_id in ["OF01", "OF02", "OF03", "OF04"]:
            assert off_id in record_map
            assert record_map[off_id]["status"] == "VERIFIED_PRESENT"
            assert record_map[off_id]["affinity_score"] >= 0.50

        # Verify Hallway Spoofer is rejected
        assert "SPOF" in record_map
        assert record_map["SPOF"]["status"] == "SPOOFER_REJECTED"
        assert record_map["SPOF"]["affinity_score"] == 0.00
        assert "Hallway Spoofer" in record_map["SPOF"]["remarks"]

        # Phase 6: Simulate Air Verdict Bursts
        # Teacher sequentially broadcasts: V:<ID>:OK
        verified_ids = [r["ble_device_id"] for r in records if r["status"] == "VERIFIED_PRESENT"]
        assert len(verified_ids) == 29

        for vid in verified_ids:
            verdict_packet = f"V:{vid}:OK"
            assert len(verdict_packet) in [9, 10, 11]
            assert len(verdict_packet.encode("utf-8")) <= 24

        # Offline student receives air verdict
        offline_student_verdict = f"V:OF01:OK"
        parts = offline_student_verdict.split(":")
        assert parts[0] == "V"
        assert parts[1] == "OF01"
        assert parts[2] == "OK"

    # Cleanup dependency overrides
    app.dependency_overrides.clear()
