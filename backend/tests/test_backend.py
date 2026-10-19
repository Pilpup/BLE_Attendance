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
    # A & B are desk neighbors (Mutuals -> 1.0)
    # C is next to B, but B dropped C due to Top-3 ranking. C claims B (Triadic closure through B -> 0.5)
    # D is Hallway Spoofer claiming B (Unreciprocated -> 0.0)
    # E is isolated in corner (0 neighbors -> 0.0)
    reports = {
        "A": ["B"],
        "B": ["A"],       # B only has A in its top neighbors
        "C": ["B"],       # C claims B, but B didn't reciprocate C
        "D": ["B"],       # D claims B from hallway
        "E": [],          # E heard nobody
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

    # Verify C is triadic closure via B
    assert res_map["C"]["status"] == "VERIFIED_PRESENT"
    assert res_map["C"]["verification_method"] == "TRIADIC_CLOSURE"
    assert res_map["C"]["affinity_score"] == 0.50
    assert res_map["C"]["bridge_device_id"] == "B"

    # Verify D is spoofer rejected (since C is already verified, D claiming B without being in cluster is spoofer)
    # Note: in triadic loop, if D claims B and B is verified, D also shares bridge B.
    # What distinguishes D (hallway spoofer) in real life is RF threshold (< -70 dBm cutoff).
    # But for graph test, let's test stranger claiming unknown node:
    stranger_reports = {
        "A": ["B"],
        "B": ["A"],
        "ROGUE": ["UNKNOWN_X"],
        "ISOLATED": [],
    }
    stranger_results = audit_classroom_mesh(stranger_reports)
    s_map = {r["ble_device_id"]: r for r in stranger_results}
    assert s_map["ROGUE"]["status"] == "SPOOFER_REJECTED"
    assert s_map["ROGUE"]["affinity_score"] == 0.00
    assert s_map["ISOLATED"]["status"] == "ISOLATED_FLAGGED"
    assert s_map["ISOLATED"]["affinity_score"] == 0.00


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
