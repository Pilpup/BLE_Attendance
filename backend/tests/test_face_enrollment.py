import math
import uuid
from datetime import datetime, timedelta, timezone

import numpy as np
import pytest

from app.services import face_enrollment as fe


@pytest.fixture
async def db_session():
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

    from app.models.attendance import Base

    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        yield session
    await engine.dispose()


@pytest.fixture
async def make_student(db_session):
    from app.models.attendance import User

    async def _make() -> uuid.UUID:
        sid = uuid.uuid4()
        db_session.add(
            User(
                id=sid,
                email=f"face-{sid}@univ.edu",
                hashed_password="x",
                full_name=f"Face Test {sid}",
                role="STUDENT",
                is_active=True,
            )
        )
        await db_session.commit()
        return sid

    return _make


# Deterministic pseudo-embeddings. `identity` pulls the vector toward a fixed
# base direction, giving a known cosine relationship between captures.
def _vec(seed: int, identity: float = 0.0, dim: int = 512) -> np.ndarray:
    rng = np.random.default_rng(seed)
    v = rng.standard_normal(dim).astype(np.float32)
    base = np.zeros(dim, dtype=np.float32)
    base[0] = 1.0
    v = (1.0 - identity) * v + identity * base * 10.0
    n = np.linalg.norm(v)
    return (v / n).reshape(1, dim)


def _base(dim: int = 512) -> np.ndarray:
    v = np.zeros(dim, dtype=np.float32)
    v[0] = 1.0
    return v


def _spread_refs(seed: int, base_weight: float, n: int = 5, dim: int = 512) -> list[dict]:
    rng = np.random.default_rng(seed)
    refs = []
    for _ in range(n):
        noise = rng.standard_normal(dim).astype(np.float32)
        noise /= np.linalg.norm(noise)
        v = math.sqrt(base_weight) * _base(dim) + math.sqrt(1 - base_weight) * noise
        v /= np.linalg.norm(v)
        refs.append({"vec": v.tolist(), "day": "2026-01-01", "quality": 100.0,
                     "yaw": 0.0, "exposure": 120.0, "created_at": "2026-01-01T00:00:00+00:00"})
    return refs


def _set_refs(row, refs) -> None:
    row.embeddings = refs
    row.ref_count = len(refs)
    row.ref_days = 2
    row.distinct_days = ["2026-01-01", "2026-01-02"]


@pytest.mark.anyio
async def test_harvest_stores_metadata_and_caps_refs(db_session, make_student):
    sid = await make_student()
    for i in range(fe.MAX_EXEMPLARS + 2):
        remark = await fe.harvest(
            db_session, sid, _vec(1_000 + i, identity=0.9),
            meta={"face_px": 100.0 + i, "yaw": 0.1, "exposure": 140.0},
        )
        assert remark is not None
    row = await fe.load_enrollment(db_session, sid)
    assert row.ref_count == fe.MAX_EXEMPLARS
    assert len(row.embeddings) == fe.MAX_EXEMPLARS
    for ref in row.embeddings:
        assert set(ref) >= {"vec", "day", "quality", "yaw", "exposure", "created_at"}
        assert len(ref["vec"]) == 512
    assert row.consent_at is not None
    assert row.expires_at is not None


@pytest.mark.anyio
async def test_outlier_capture_is_rejected(db_session, make_student):
    sid = await make_student()
    await fe.harvest(db_session, sid, _vec(10, identity=0.9))
    # A completely different direction (orthogonal identity) must be rejected.
    remark = await fe.harvest(db_session, sid, _vec(99, identity=0.0))
    row = await fe.load_enrollment(db_session, sid)
    assert remark is None
    assert row.ref_count == 1


@pytest.mark.anyio
async def test_adaptive_threshold_tight_vs_wide(db_session, make_student):
    sid_tight = await make_student()
    sid_wide = await make_student()
    from app.models.attendance import FaceEnrollment
    tight = FaceEnrollment(student_id=sid_tight)
    wide = FaceEnrollment(student_id=sid_wide)
    db_session.add_all([tight, wide])

    # Tight refs: pairwise cos ~1 -> gate clamps at the adaptive ceiling.
    _set_refs(tight, [{"vec": (_base() + 0.001 * np.random.default_rng(i).standard_normal(512)).tolist()} for i in range(5)])
    # Wide refs: pairwise cos ~0.54 -> gate between floor and ceiling.
    _set_refs(wide, _spread_refs(seed=7, base_weight=0.55))

    t_tight = fe.adaptive_threshold(tight)
    t_wide = fe.adaptive_threshold(wide)
    assert t_tight == fe.adaptive_ceiling()
    assert fe.T_MIN < t_wide < t_tight
    assert fe.adaptive_ceiling() <= fe.T_MIN + fe.ADAPTIVE_MAX_UPLIFT
    assert fe.T_MIN <= fe.adaptive_threshold(None) <= fe.adaptive_ceiling()


@pytest.mark.anyio
async def test_sigma_floor_prevents_threshold_equals_mu(monkeypatch, db_session, make_student):
    sid = await make_student()
    from app.models.attendance import FaceEnrollment
    row = FaceEnrollment(student_id=sid)
    db_session.add(row)
    _set_refs(row, _spread_refs(seed=3, base_weight=0.60))
    # Disable the placeholder uplift cap so the sigma floor itself is measured.
    monkeypatch.setattr(fe, "ADAPTIVE_MAX_UPLIFT", 1.0)
    monkeypatch.setattr(fe, "T_MAX", 1.0)
    monkeypatch.setattr(fe, "_cos", lambda a, b: 0.60)
    assert fe.adaptive_threshold(row) == pytest.approx(
        0.60 - fe.ADAPTIVE_K * fe.ADAPTIVE_SIGMA_FLOOR
    )


@pytest.mark.anyio
async def test_verify_respects_adaptive_threshold(monkeypatch, db_session, make_student):
    sid = await make_student()
    from app.models.attendance import FaceEnrollment
    row = FaceEnrollment(student_id=sid)
    db_session.add(row)
    _set_refs(row, [{"vec": _base().tolist()} for _ in range(5)])
    monkeypatch.setattr(fe, "adaptive_threshold", lambda row: 0.70)

    # Cosine 0.65 vs the base ref: below gate -> no match.
    noise = _vec(42, identity=0.0).flatten()
    query = 0.65 * _base() + math.sqrt(1 - 0.65 ** 2) * noise
    matched, best, threshold = await fe.verify_1to1(db_session, sid, query.reshape(1, -1))
    assert not matched
    assert threshold == 0.70
    assert best == pytest.approx(0.65, abs=0.02)

    # Cosine 0.80 -> match, and returned score is unchanged.
    noise2 = _vec(43, identity=0.0).flatten()
    query2 = 0.80 * _base() + math.sqrt(1 - 0.80 ** 2) * noise2
    matched2, best2, _ = await fe.verify_1to1(db_session, sid, query2.reshape(1, -1))
    assert matched2
    assert best2 == pytest.approx(0.80, abs=0.02)


@pytest.mark.anyio
async def test_adaptation_gate_never_below_verification():
    assert fe.adaptation_gate(0.70) == 0.70
    assert fe.adaptation_gate(0.45) == fe.ADAPT_STRONG_SIMILARITY


@pytest.mark.anyio
async def test_centroid_updates_once_per_day_with_best_capture(db_session, make_student, monkeypatch):
    sid = await make_student()
    current = {"dt": datetime(2026, 3, 1, 9, 0, tzinfo=timezone.utc)}
    monkeypatch.setattr(fe, "_utc_now", lambda: current["dt"])

    first = _vec(500, identity=0.9)
    await fe.harvest(db_session, sid, first, meta={"face_px": 100.0})
    centroid_after_first = list((await fe.load_enrollment(db_session, sid)).centroid)
    assert centroid_after_first == pytest.approx(first.flatten().tolist(), abs=1e-6)

    # Five more same-day captures, increasing quality. Centroid stays put;
    # pending holds the best-quality capture.
    best = first
    for i in range(5):
        v = _vec(501 + i, identity=0.9)
        await fe.harvest(db_session, sid, v, meta={"face_px": 200.0 + i})
        best = v
    row = await fe.load_enrollment(db_session, sid)
    assert row.centroid == pytest.approx(centroid_after_first, abs=1e-6)
    assert row.pending_vec == pytest.approx(best.flatten().tolist(), abs=1e-6)

    # Next day: exactly one EWMA update, using the previous day's best capture.
    pending_before = list(row.pending_vec)
    current["dt"] = datetime(2026, 3, 2, 9, 0, tzinfo=timezone.utc)
    await fe.harvest(db_session, sid, _vec(600, identity=0.9), meta={"face_px": 50.0})
    row = await fe.load_enrollment(db_session, sid)
    blended = [
        (1 - fe.EWMA_ALPHA) * c + fe.EWMA_ALPHA * p
        for c, p in zip(centroid_after_first, pending_before)
    ]
    norm = math.sqrt(sum(x * x for x in blended))
    expected = [x / norm for x in blended]
    assert row.centroid == pytest.approx(expected, abs=1e-6)
    assert row.centroid_day == "2026-03-02"


@pytest.mark.anyio
async def test_no_picture_cap_six_sessions_per_day_flips_after_two_days(db_session, make_student, monkeypatch):
    sid = await make_student()
    current = {"dt": datetime(2026, 4, 1, 8, 0, tzinfo=timezone.utc)}
    monkeypatch.setattr(fe, "_utc_now", lambda: current["dt"])
    for day in (1, 2):
        current["dt"] = datetime(2026, 4, day, 8, 0, tzinfo=timezone.utc)
        for i in range(6):  # 4-6 classes/day: all six may harvest
            assert await fe.harvest(db_session, sid, _vec(700 + day * 10 + i, identity=0.9)) is not None
    row = await fe.load_enrollment(db_session, sid)
    assert row.ref_count == fe.MAX_EXEMPLARS  # set bounded, admissions not
    assert row.ref_days == 2
    assert fe.is_flip_ready(row)


@pytest.mark.anyio
async def test_head_width_change_resets_references(db_session, make_student):
    sid = await make_student()
    await fe.harvest(db_session, sid, _vec(800, identity=0.9, dim=128))
    await fe.harvest(db_session, sid, _vec(801, identity=0.9, dim=512))
    row = await fe.load_enrollment(db_session, sid)
    assert row.ref_count == 1
    assert len(row.embeddings[0]["vec"]) == 512


@pytest.mark.anyio
async def test_expired_enrollment_is_purged(db_session, make_student):
    sid = await make_student()
    await fe.harvest(db_session, sid, _vec(900, identity=0.9))
    row = await fe.load_enrollment(db_session, sid)
    row.expires_at = datetime.now(timezone.utc) - timedelta(days=1)
    assert await fe.load_enrollment(db_session, sid) is None
