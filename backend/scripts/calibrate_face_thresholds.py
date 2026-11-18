#!/usr/bin/env python3
"""Calibrate F1 face thresholds from a consented, labeled photo corpus.

The corpus is NEVER committed and NEVER persisted by the application. This
script runs offline: it embeds images through the real ArcFace engine, compares
same-person cross-day captures (genuine) against other people's captures
(impostor), and prints recommended constants plus held-out validation metrics.

Corpus layout (outside git, see README section "Face threshold calibration"):

    backend/calibration_corpus/manifest.json
    backend/calibration_corpus/p01/d1_front.jpg
    ...

Run from anywhere:

    python3 backend/scripts/calibrate_face_thresholds.py

Optional: --report backend/calibration_report.json writes numbers only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

CORPUS_DIR = ROOT / "calibration_corpus"
MANIFEST = CORPUS_DIR / "manifest.json"
KNOWN_CONDITIONS = {"front", "yaw_left", "yaw_right", "glasses", "dim", "bright", "other"}


def _cos(a: list[float], b: list[float]) -> float:
    dot = na = nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na <= 1e-12 or nb <= 1e-12:
        return 0.0
    return max(-1.0, min(1.0, dot / math.sqrt(na * nb)))


def load_manifest(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    images = data.get("images")
    if not isinstance(images, list) or not images:
        raise ValueError("manifest.json must contain a non-empty 'images' list")
    for i, img in enumerate(images):
        for key in ("path", "identity", "day"):
            if not img.get(key):
                raise ValueError(f"images[{i}] missing required field '{key}'")
        if img.get("condition", "other") not in KNOWN_CONDITIONS:
            raise ValueError(f"images[{i}] has unknown condition {img.get('condition')!r}")
    return images


def embed_images(images: list[dict], corpus_dir: Path) -> tuple[list[dict], list[str]]:
    """Run every image through the real engine. Images stay in RAM only."""
    from app.services.face_engine import FaceMatchingEngine

    engine = FaceMatchingEngine()
    records: list[dict] = []
    warnings: list[str] = []
    root = corpus_dir.resolve()
    for img in images:
        path = (corpus_dir / img["path"]).resolve()
        if not path.is_relative_to(root):
            raise ValueError(f"image path escapes corpus directory: {img['path']}")
        if not path.is_file():
            warnings.append(f"missing image skipped: {img['path']}")
            continue
        capture = engine.extract_capture(path.read_bytes())
        if not capture.has_face or capture.embedding is None:
            warnings.append(f"no face detected, skipped: {img['path']}")
            continue
        record = dict(img)
        record["vec"] = [float(x) for x in capture.embedding.flatten()]
        records.append(record)
    return records, warnings


def _split_identities(identities: list[str]) -> tuple[list[str], list[str]]:
    """Deterministic 70/30 identity split. Never tune and validate on the same face."""
    ordered = sorted(identities)
    train = [i for i in ordered if int(hashlib.sha1(i.encode()).hexdigest(), 16) % 100 < 70]
    test = [i for i in ordered if i not in train]
    if ordered and not train:
        train, test = ordered[:1], ordered[1:]
    if ordered and not test:
        # Too small a corpus for a real holdout; caller warns.
        test = []
    return train, test


def _roc(genuine: list[float], impostor: list[float]) -> list[dict]:
    if not genuine or not impostor:
        raise ValueError("ROC needs both genuine and impostor scores")
    rows = []
    for step in range(0, 1001):
        t = step / 1000.0
        rows.append({
            "t": t,
            "far": sum(s >= t for s in impostor) / len(impostor),
            "frr": sum(s < t for s in genuine) / len(genuine),
        })
    return rows


def _pick_base_threshold(rows: list[dict], target_far: float) -> tuple[float, dict]:
    feasible = [r for r in rows if r["far"] <= target_far]
    if not feasible:
        best = min(rows, key=lambda r: r["far"])
        return best["t"], {"warning": f"no threshold reaches FAR<={target_far}; lowest FAR={best['far']:.4f}"}
    chosen = min(feasible, key=lambda r: (r["frr"], r["far"]))
    return chosen["t"], {"far": chosen["far"], "frr": chosen["frr"]}


def _percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, math.ceil(p * len(ordered)) - 1))
    return ordered[idx]


def analyze(records: list[dict], target_far: float = 0.001) -> dict:
    """Pure scoring logic — testable without real face images."""
    by_identity: dict[str, list[dict]] = {}
    for r in records:
        by_identity.setdefault(r["identity"], []).append(r)
    if len(by_identity) < 2:
        raise ValueError("need at least 2 identities for impostor scores")

    train_ids, test_ids = _split_identities(list(by_identity))
    genuine: list[float] = []
    impostor: list[float] = []
    impostor_train: list[float] = []
    lookalike: list[float] = []
    for identity, recs in by_identity.items():
        first_day = min(x["day"] for x in recs)
        first = [r for r in recs if r["day"] == first_day]
        later = [r for r in recs if r["day"] != first_day]
        for g in later:
            genuine.append(max(_cos(g["vec"], f["vec"]) for f in first))
        for other, other_recs in by_identity.items():
            if other == identity:
                continue
            for a in recs:
                for b in other_recs:
                    if a["day"] != b["day"]:
                        continue
                    score = _cos(a["vec"], b["vec"])
                    impostor.append(score)
                    if identity in train_ids and other in train_ids:
                        impostor_train.append(score)
                    if a.get("lookalike_group") and a.get("lookalike_group") == b.get("lookalike_group"):
                        lookalike.append(score)

    train_g = [s for s, i in zip(genuine, _identity_of_genuine(records)) if i in train_ids]
    calibration_impostor = impostor_train or impostor
    warnings: list[str] = []
    if not impostor_train:
        warnings.append("too few train identities for isolated impostor scores; used full corpus")
    rows = _roc(train_g, calibration_impostor)
    base, base_info = _pick_base_threshold(rows, target_far)

    # Adaptive gate sweep: k and uplift on top of the calibrated floor.
    best = None
    for k in (0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0):
        for uplift in (0.0, 0.02, 0.05, 0.10, 0.15, 0.25):
            ceiling = min(1.0, base + uplift)
            frr = far = 0
            for identity, recs in by_identity.items():
                if identity not in train_ids:
                    continue
                first = [r for r in recs if r["day"] == min(x["day"] for x in recs)]
                if len(first) < 2:
                    continue
                pairs = [_cos(first[i]["vec"], first[j]["vec"]) for i in range(len(first)) for j in range(i + 1, len(first))]
                mu = sum(pairs) / len(pairs)
                sigma = max(math.sqrt(sum((p - mu) ** 2 for p in pairs) / len(pairs)), 0.03)
                t = max(base, min(ceiling, mu - k * sigma))
                for g in recs:
                    if g["day"] == min(x["day"] for x in recs):
                        continue
                    if max(_cos(g["vec"], f["vec"]) for f in first) < t:
                        frr += 1
                for other, other_recs in by_identity.items():
                    if other == identity:
                        continue
                    for b in other_recs:
                        if max(_cos(b["vec"], f["vec"]) for f in first) >= t:
                            far += 1
            n_impostor_train = sum(
                len(recs) * sum(len(by_identity[o]) for o in train_ids if o != identity)
                for identity, recs in by_identity.items()
                if identity in train_ids
            )
            frr_rate = frr / max(1, len(train_g))
            far_rate = far / max(1, n_impostor_train)
            if far_rate <= target_far:
                candidate = (frr_rate, far_rate, k, uplift)
                if best is None or candidate < best:
                    best = candidate
    if best is None:
        best = (1.0, 0.0, 2.0, 0.05)

    genuine_floor = _percentile(genuine, 0.01)
    impostor_ceiling = _percentile(impostor, 0.999)
    if genuine_floor <= impostor_ceiling:
        warnings.append("genuine/impostor score distributions overlap at the tails; thresholds cannot separate them")
    recommended = {
        "VERIFY_SIMILARITY": round(base, 3),
        "T_MIN": round(base, 3),
        "OUTLIER_MIN_SIMILARITY": round(min(genuine_floor, impostor_ceiling), 3),
        "ADAPT_STRONG_SIMILARITY": round(_percentile(genuine, 0.90), 3),
        "ADAPTIVE_K": best[2],
        "ADAPTIVE_MAX_UPLIFT": best[3],
        "ADAPTIVE_SIGMA_FLOOR": 0.03,
        "T_MAX": 1.0,
    }

    # Held-out validation with the recommended parameters.
    val_frr = val_far = 0
    per_condition: dict[str, dict[str, float]] = {}
    for identity, recs in by_identity.items():
        if identity not in test_ids:
            continue
        first = [r for r in recs if r["day"] == min(x["day"] for x in recs)]
        pairs = [_cos(first[i]["vec"], first[j]["vec"]) for i in range(len(first)) for j in range(i + 1, len(first))]
        mu = sum(pairs) / len(pairs) if pairs else base
        sigma = max(math.sqrt(sum((p - mu) ** 2 for p in pairs) / len(pairs)) if pairs else 0.03, 0.03)
        t = max(base, min(min(1.0, base + best[3]), mu - best[2] * sigma))
        for g in recs:
            if g["day"] == min(x["day"] for x in recs):
                continue
            score = max(_cos(g["vec"], f["vec"]) for f in first)
            cond = g.get("condition", "other")
            slot = per_condition.setdefault(cond, {"n": 0, "reject": 0})
            slot["n"] += 1
            if score < t:
                val_frr += 1
                slot["reject"] += 1
        for other, other_recs in by_identity.items():
            if other == identity:
                continue
            for b in other_recs:
                if max(_cos(b["vec"], f["vec"]) for f in first) >= t:
                    val_far += 1

    n_val_g = sum(1 for i in test_ids for r in by_identity[i] if r["day"] != min(x["day"] for x in by_identity[i]))
    n_val_i = sum(1 for i in test_ids for r in by_identity[i]) * (len(by_identity) - 1)
    return {
        "target_far": target_far,
        "corpus": {
            "identities": len(by_identity),
            "images": len(records),
            "genuine_pairs": len(genuine),
            "impostor_pairs": len(impostor),
            "lookalike_pairs": len(lookalike),
        },
        "split": {"train": train_ids, "holdout": test_ids},
        "base_threshold": base_info,
        "recommended_constants": recommended,
        "validation": {
            "holdout_far": round(val_far / max(1, n_val_i), 5),
            "holdout_frr": round(val_frr / max(1, n_val_g), 5),
            "per_condition_frr": {
                k: round(v["reject"] / max(1, v["n"]), 5) for k, v in per_condition.items()
            },
            "lookalike_impostor_max": round(max(lookalike) if lookalike else 0.0, 3),
        },
        "warnings": warnings,
    }


def _identity_of_genuine(records: list[dict]) -> list[str]:
    """Return one identity label per genuine score, in analyze()'s order."""
    by_identity: dict[str, list[dict]] = {}
    for r in records:
        by_identity.setdefault(r["identity"], []).append(r)
    labels = []
    for identity, recs in by_identity.items():
        first_day = min(x["day"] for x in recs)
        first = [r for r in recs if r["day"] == first_day]
        for g in recs:
            if g["day"] != first_day:
                labels.append(identity)
        del first
    return labels


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--corpus-dir", type=Path, default=CORPUS_DIR)
    parser.add_argument("--target-far", type=float, default=0.001)
    parser.add_argument("--report", type=Path, help="write the numbers-only report to this JSON path")
    args = parser.parse_args()

    if not args.manifest.is_file():
        print(f"No corpus yet: {args.manifest}\nCollect consented photos first (see README).", file=sys.stderr)
        return 2

    images = load_manifest(args.manifest)
    records, warnings = embed_images(images, args.corpus_dir)
    if len({r["identity"] for r in records}) < 2:
        print("Need at least 2 identities with detected faces.", file=sys.stderr)
        return 2
    report = analyze(records, target_far=args.target_far)
    report["warnings"] = warnings + report["warnings"]
    if not report["split"]["holdout"]:
        report["warnings"].append("corpus too small for a real holdout; collect more identities")
    if not report["corpus"]["lookalike_pairs"]:
        report["warnings"].append("no lookalike pairs — FAR for the exact F1 failure mode is unmeasured")
    text = json.dumps(report, indent=2)
    print(text)
    if args.report:
        args.report.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
