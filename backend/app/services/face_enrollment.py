from __future__ import annotations

import logging
import math
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.attendance import FaceEnrollment

logger = logging.getLogger("uvicorn.error")

# ---------------------------------------------------------------------------
# F1 passive enrollment (docs/future_plans.md, F1 §2-§4).
#
# References are forged ONLY from captures that passed the face check on their
# own merit (auto-verified, non-duplicate). Teacher overrides of face-flagged
# submissions are NEVER ingested here — that channel also clears suspect
# (proxy) captures, so folding one in would poison the student's template.
#
# All similarity thresholds below are ArcFace-space placeholders and are
# UNCALIBRATED until the KAZE #2 corpus measurement lands (F1 §7: no corpus,
# no claim). They are deliberately conservative on the false-flag side.
# ---------------------------------------------------------------------------

# Poisoning backstop: a capture matching nothing the student already has is
# rejected before it can enter the template. Independent of the adaptive gate.
OUTLIER_MIN_SIMILARITY = 0.25

# Per-student flip: enough quality references spanning enough distinct days
# before 1:1 replaces the open-set fallback path. No cap on pictures — every
# class session (4-6/day) may harvest; only the stored exemplar set is bounded.
MIN_REFS_FOR_FLIP = 5
MIN_DAYS_FOR_FLIP = 2
MAX_EXEMPLARS = 5

# Bounded adaptation: ONE centroid update per UTC day (alpha applied to the
# best-quality capture of the previous day), not per capture. At 6 classes/day
# a per-capture alpha would move ~half the centroid every day.
EWMA_ALPHA = 0.10

# Global verify floor, also the lower clamp for the per-student adaptive gate.
VERIFY_SIMILARITY = 0.45
T_MIN = VERIFY_SIMILARITY
T_MAX = 0.80
# A student's refs are curated (high quality); live frames are noisier, so raw
# mu-based gates can over-tighten a student whose refs are all near-identical.
# Until the corpus calibrates the absolute gate, adaptive adjustment may only
# lift the global floor by this much. Raise the calibrated VERIFY_SIMILARITY
# later — the cap follows it automatically.
ADAPTIVE_MAX_UPLIFT = 0.05
# T_student = clamp(mu - k*sigma, T_MIN, min(T_MAX, T_MIN + ADAPTIVE_MAX_UPLIFT))
# over the 10 pairwise cosines among the student's own references. sigma has a
# floor so a set of near-identical refs cannot drive the threshold up to mu.
ADAPTIVE_K = 2.0
ADAPTIVE_SIGMA_FLOOR = 0.03


def adaptive_ceiling() -> float:
    return min(T_MAX, T_MIN + ADAPTIVE_MAX_UPLIFT)

# Only captures matching this strongly fold back into the template. The route
# uses max(T_student, ADAPT_STRONG_SIMILARITY) so a capture that fails that
# student's verification can never be ingested.
ADAPT_STRONG_SIMILARITY = 0.60


def adaptation_gate(threshold: float) -> float:
    return max(threshold, ADAPT_STRONG_SIMILARITY)

ENROLLMENT_TTL_DAYS = 180


def _cos(a: list[float], b: list[float]) -> float:
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na <= 1e-12 or nb <= 1e-12:
        return 0.0
    return max(-1.0, min(1.0, dot / math.sqrt(na * nb)))


def _vec(embedding) -> list[float]:
    return [float(x) for x in embedding.flatten()]


def _ref_vec(ref) -> list[float]:
    if isinstance(ref, dict):
        return [float(x) for x in ref.get("vec", [])]
    # Legacy dev rows stored bare vectors; tolerate them.
    return _vec(ref) if hasattr(ref, "flatten") else [float(x) for x in ref]


def _ref_quality(ref) -> float:
    return float(ref.get("quality", 0.0)) if isinstance(ref, dict) else 0.0


def _make_ref(vec: list[float], meta: Optional[dict], day: str) -> dict:
    meta = meta or {}
    return {
        "vec": vec,
        "day": day,
        # Face bounding-box area is the cheap quality proxy; blur/yaw/exposure
        # are recorded for eviction tie-breaks and future corpus diagnostics.
        "quality": float(meta.get("face_px", 0.0) or 0.0),
        "yaw": float(meta.get("yaw", 0.0) or 0.0),
        "exposure": float(meta.get("exposure", 0.0) or 0.0),
        "created_at": datetime.now(timezone.utc).isoformat(),
    }


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


# Loads the enrollment row, lazily expiring stale ones. Returns None when the
# student has no live enrollment (expired rows are wiped on sight).
async def load_enrollment(db: AsyncSession, student_id: uuid.UUID) -> Optional[FaceEnrollment]:
    res = await db.execute(select(FaceEnrollment).where(FaceEnrollment.student_id == student_id))
    row = res.scalars().first()
    if row is None:
        return None
    # SQLite drops tzinfo on round-trip; normalize before comparing.
    expires = row.expires_at
    if expires is not None:
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
        if expires <= _utc_now():
            await db.delete(row)
            await db.flush()
            return None
    return row


def is_flip_ready(row: Optional[FaceEnrollment]) -> bool:
    return (
        row is not None
        and (row.ref_count or 0) >= MIN_REFS_FOR_FLIP
        and (row.ref_days or 0) >= MIN_DAYS_FOR_FLIP
    )


# Per-student adaptive verification gate from the student's own references.
# Wide spread (yaw/glasses days) lowers the gate; a tight cluster raises it.
def adaptive_threshold(row: Optional[FaceEnrollment]) -> float:
    if row is None:
        return T_MIN
    vecs = [_ref_vec(r) for r in (row.embeddings or [])]
    pairs: list[float] = []
    for i in range(len(vecs)):
        for j in range(i + 1, len(vecs)):
            if len(vecs[i]) == len(vecs[j]) and vecs[i]:
                pairs.append(_cos(vecs[i], vecs[j]))
    if len(pairs) < 2:
        return T_MIN
    mu = sum(pairs) / len(pairs)
    var = sum((p - mu) ** 2 for p in pairs) / len(pairs)
    sigma = max(math.sqrt(var), ADAPTIVE_SIGMA_FLOOR)
    return max(T_MIN, min(adaptive_ceiling(), mu - ADAPTIVE_K * sigma))


# Forges/extends a student's reference set from one trusted capture. Every
# eligible class-session capture reaches this function; no per-day admission
# cap. Returns a short remark for logs; never raises on gate rejection.
async def harvest(
    db: AsyncSession,
    student_id: uuid.UUID,
    embedding,
    meta: Optional[dict] = None,
) -> Optional[str]:
    try:
        new_vec = _vec(embedding)
        if not new_vec or any(not math.isfinite(x) for x in new_vec):
            return None

        row = await load_enrollment(db, student_id)
        if row is None:
            row = FaceEnrollment(student_id=student_id)
            db.add(row)

        refs = list(row.embeddings or [])
        # Normalize legacy bare-vector rows into metadata records.
        refs = [r if isinstance(r, dict) else _make_ref(_ref_vec(r), None, "") for r in refs]
        # Head swap mid-term invalidates stored vectors — restart cleanly.
        if refs and len(_ref_vec(refs[0])) != len(new_vec):
            logger.warning(
                f"[FaceEnrollment] Embedding width changed for {student_id} "
                f"({len(_ref_vec(refs[0]))} -> {len(new_vec)}); resetting references"
            )
            refs = []
            row.centroid = None
            row.centroid_day = None
            row.pending_vec = None
            row.pending_quality = None
            row.distinct_days = []

        # Poisoning second line: a capture that matches no existing reference
        # at all is not this student — reject it before it can drift anything.
        if refs:
            best = max(_cos(new_vec, _ref_vec(r)) for r in refs)
            if best < OUTLIER_MIN_SIMILARITY:
                logger.info(
                    f"[FaceEnrollment] Outlier capture rejected for {student_id} "
                    f"(best sim {best:.3f} < {OUTLIER_MIN_SIMILARITY})"
                )
                return None

        day = _utc_now().date().isoformat()
        days = list(row.distinct_days or [])
        if day not in days:
            days.append(day)

        quality = float((meta or {}).get("face_px", 0.0) or 0.0)
        refs.append(_make_ref(new_vec, meta, day))

        # Exemplar cap: evict the most redundant reference (highest average
        # similarity to the rest). Ties go to lower quality, then older.
        if len(refs) > MAX_EXEMPLARS:
            def redundancy(i: int) -> float:
                others = [_ref_vec(refs[j]) for j in range(len(refs)) if j != i]
                return sum(_cos(_ref_vec(refs[i]), o) for o in others) / max(1, len(others))

            most_redundant = max(
                range(len(refs)),
                key=lambda i: (redundancy(i), -_ref_quality(refs[i]), -i),
            )
            refs.pop(most_redundant)

        # One bounded EWMA update per UTC day. The best-quality capture of a
        # day is held in pending_* and folded in on the first capture of the
        # NEXT day, so 4-6 classes/day cannot drift half the template daily.
        if not row.centroid or row.centroid_day is None:
            row.centroid = list(new_vec)
            row.centroid_day = day
            row.pending_vec = None
            row.pending_quality = None
        elif row.centroid_day != day:
            pending = row.pending_vec
            if pending and len(pending) == len(row.centroid):
                blended = [
                    (1.0 - EWMA_ALPHA) * c + EWMA_ALPHA * p
                    for c, p in zip(row.centroid, pending)
                ]
                norm = math.sqrt(sum(x * x for x in blended))
                row.centroid = [x / norm for x in blended] if norm > 1e-12 else list(new_vec)
            row.centroid_day = day
            row.pending_vec = list(new_vec)
            row.pending_quality = quality
        elif row.pending_quality is None or quality > row.pending_quality:
            row.pending_vec = list(new_vec)
            row.pending_quality = quality

        row.embeddings = refs
        row.distinct_days = days[-60:]
        row.ref_count = len(refs)
        row.ref_days = len(days)
        if row.consent_at is None:
            row.consent_at = _utc_now()
        row.expires_at = _utc_now() + timedelta(days=ENROLLMENT_TTL_DAYS)
        row.updated_at = _utc_now()
        await db.flush()
        return f"enrolled refs={len(refs)} days={len(days)}"
    except Exception as e:
        logger.error(f"[FaceEnrollment] Harvest failed for {student_id}: {e}")
        return None


# 1:1 closed-set verification: best-of-N cosine against the student's own
# exemplars, gated by that student's adaptive threshold.
# Returns (matched, best_similarity, threshold). No row / not flipped -> no
# match, so the caller keeps the open-set fallback path.
async def verify_1to1(
    db: AsyncSession, student_id: uuid.UUID, embedding
) -> tuple[bool, float, float]:
    row = await load_enrollment(db, student_id)
    if not is_flip_ready(row):
        return False, 0.0, T_MIN
    vec = _vec(embedding)
    best = 0.0
    for ref in row.embeddings or []:
        r = _ref_vec(ref)
        if len(r) == len(vec) and r:
            best = max(best, _cos(vec, r))
    threshold = adaptive_threshold(row)
    return best >= threshold, best, threshold
