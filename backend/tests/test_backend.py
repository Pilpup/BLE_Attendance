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
    # A and B both hear offline student OFF1 at desk range
    # Hallway spoofer D claims OFF2, but D is unverified
    reports = {
        "A": ["B", "OFF1"],
        "B": ["A", "OFF1"],
        "D": ["OFF2"],
    }
    results = audit_classroom_mesh(reports)
    res_map = {r["ble_device_id"]: r for r in results}

    # OFF1 was witnessed by 2 verified peers (A and B)
    assert "OFF1" in res_map
    assert res_map["OFF1"]["status"] == "VERIFIED_PRESENT"
    assert res_map["OFF1"]["verification_method"] == "PEER_WITNESS"
    assert res_map["OFF1"]["affinity_score"] == 0.85
    assert "OFF1" in res_map

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
    assert "/api/v1/attendance/sessions" in paths
    assert "/api/v1/attendance/sessions/{session_id}/finalize" in paths
    assert "/api/v1/attendance/sessions/{session_id}/audit-and-finalize" in paths
    assert "/api/v1/attendance/sessions/{session_id}" in paths
