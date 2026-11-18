import importlib.util
from pathlib import Path

import numpy as np


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "calibrate_face_thresholds.py"
spec = importlib.util.spec_from_file_location("calibrate_face_thresholds", SCRIPT)
cal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cal)


def _synthetic_corpus(n_identities: int = 8):
    rng = np.random.default_rng(42)
    dim = 64
    bases = {f"p{i:02d}": rng.standard_normal(dim).astype(np.float32) for i in range(n_identities)}
    records = []
    for identity, base in bases.items():
        base /= np.linalg.norm(base)
        for day in ("2026-01-01", "2026-01-02"):
            for cond in ("front", "yaw_left", "glasses"):
                noise = rng.standard_normal(dim).astype(np.float32)
                noise /= np.linalg.norm(noise)
                vec = np.sqrt(0.80) * base + np.sqrt(0.20) * noise
                rec = {
                    "identity": identity,
                    "day": day,
                    "condition": cond,
                    "path": f"{identity}/{day}_{cond}.jpg",
                    "vec": vec.tolist(),
                }
                if identity in ("p00", "p01"):
                    rec["lookalike_group"] = "A"
                records.append(rec)
    return records


def test_analyze_produces_measured_constants_with_holdout():
    report = cal.analyze(_synthetic_corpus(), target_far=0.05)
    assert report["corpus"]["identities"] == 8
    assert report["corpus"]["images"] == 48
    assert report["corpus"]["genuine_pairs"] == 24
    assert report["corpus"]["lookalike_pairs"] > 0

    train, holdout = report["split"]["train"], report["split"]["holdout"]
    assert train and holdout
    assert set(train).isdisjoint(holdout)

    constants = report["recommended_constants"]
    assert 0.0 < constants["VERIFY_SIMILARITY"] < 1.0
    assert constants["T_MIN"] == constants["VERIFY_SIMILARITY"]
    assert 0.0 <= constants["ADAPTIVE_MAX_UPLIFT"] <= 0.25
    assert constants["ADAPT_STRONG_SIMILARITY"] > constants["VERIFY_SIMILARITY"]

    validation = report["validation"]
    assert 0.0 <= validation["holdout_frr"] <= 1.0
    assert 0.0 <= validation["holdout_far"] <= 1.0
    assert set(validation["per_condition_frr"]) == {"front", "yaw_left", "glasses"}


def test_manifest_validation_rejects_bad_condition():
    import json
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "manifest.json"
        path.write_text(json.dumps({"images": [{"path": "x.jpg", "identity": "p", "day": "d", "condition": "bad"}]}))
        try:
            cal.load_manifest(path)
        except ValueError as exc:
            assert "unknown condition" in str(exc)
        else:
            raise AssertionError("bad condition accepted")
