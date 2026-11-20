#!/usr/bin/env python3
"""Layer 2 stress: rank noise + visibility flapping - v3.1 vs production v1.

Same radio reality for every feeding in a cell (same seed); each engine gets
what its design prescribes the client to send:
  v1@K3    - current app client (.take(3) in AttendanceViewModel.kt)
  v1@K10   - schema cap MAX_NEBORS=10 (v1's best case)
  v31@full - new design: full neighbor lists, no truncation

Noise blocks (independent, one variable at a time):
  rank noise - client re-sorts by RSSI; distance estimate carries
               U(-sigma, sigma) jitter (seat-lengths), THEN truncation.
               Attacks v1 where it is weakest: the K-th cutoff and its
               exact-distance tie edges.
  flapping   - each in-radius DIRECTIONAL link is present independently
               w.p. (1 - drop). Models range-edge membership loss.
               Attacks v1's corner-edge redundancy; v3.1 survives via
               dense mutuals, bridges and federation.
  shuffle    - attendance 60-90%: front 35 seats (rows 0-4) always full,
               the remaining present students sit at RANDOM empty seats
               (rows 5-9). Clean radio. Scattered occupancy: sparse
               mutuals, single-edge back seats, absent-seat gaps.

Cell metric: modal (verified, unverified) student counts over N seeds;
verified = VERIFIED (v3.1) or VERIFIED_PRESENT (v1); everything else
collapses to UNVERIFIED (teacher check) under the two-flag taxonomy,
'*' = outcome varies across seeds. For v1 the unverified column still
splits by cause (review vs isolated) in results.json signatures.
"""

import importlib.util
import json
import random
from collections import Counter
from pathlib import Path

import simulate_satellites as base

TOTAL = base.TOTAL
N_SEEDS = 201
HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "layer2_out"

FEEDS = ("v1@K3", "v1@K10", "v31@full")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ENGINES = {
    # v1 production engine (pre-v3.1 backup): app/services/graph_engine.py now
    # runs engine v3.1, so the v1 comparison feeds load the frozen backup.
    "v1@K3": (load("ge_v1", HERE / "graph_engine_v1_prod_backup.py"), 3),
    "v1@K10": (None, 10),   # filled in main() after v1 load
    "v31@full": (load("ge_v31", HERE / "graph_engine_v31.py"), None),
}


def session_lists(gap, seed, sigma, drop):
    """Full-visibility lists under rank noise (sigma) + flapping (drop)."""
    rng = random.Random(7000 + seed)
    seats = base.build_seats(gap)
    lists = {}
    for i, a in enumerate(seats):
        entries = []
        for j, b in enumerate(seats):
            if i == j:
                continue
            d = base.dist(a, b)
            if d > base.RADIUS + 1e-9:
                continue
            if drop > 0 and rng.random() < drop:
                continue  # this directional link flapped off
            entries.append((j, d))
        if sigma > 0:
            entries.sort(key=lambda e: (e[1] + rng.uniform(-sigma, sigma), e[0]))
        else:
            entries.sort(key=lambda e: (e[1], e[0]))
        lists[i] = [j for j, _ in entries]
    return lists


def shuffle_session(gap, seed, n_present):
    """Occupied seats: front 35 (rows 0-4) full, rest random in rows 5-9.

    Which student occupies which seat is irrelevant to the engines (all
    present students are honest and enrolled), so the pattern is fully
    described by the occupied-seat set. Clean radio: radius 4.0, no noise.
    Returns {global_seat_index: [peer_global_index, ...]}.
    """
    rng = random.Random(9000 + seed)
    seats = base.build_seats(gap)
    back_pool = range(35, base.TOTAL)
    occ_idx = list(range(35)) + sorted(
        rng.sample(list(back_pool), n_present - 35)
    )
    occupied = [seats[i] for i in occ_idx]
    lists = {}
    for i, a in enumerate(occupied):
        entries = []
        for j, b in enumerate(occupied):
            if i == j:
                continue
            d = base.dist(a, b)
            if d <= base.RADIUS + 1e-9:
                entries.append((j, d))
        entries.sort(key=lambda e: (e[1], e[0]))
        lists[occ_idx[i]] = [occ_idx[j] for j, _ in entries]
    return lists


def run_cell(gap, sigma, drop, n_present=None):
    per_feed = {}
    for fname in FEEDS:
        eng, k = ENGINES[fname]
        stats: Counter = Counter()
        for s in range(N_SEEDS):
            if n_present is None:
                lists = session_lists(gap, s, sigma, drop)
            else:
                lists = shuffle_session(gap, s, n_present)
            reports = {}
            for i, lst in lists.items():
                lst = lst[:k] if k is not None else lst
                reports[f"S{i:02d}"] = [f"S{j:02d}" for j in lst]
            res = eng.audit_classroom_mesh(
                reports, total_enrolled=TOTAL, face_verified_keys=None
            )
            sc = Counter(r["status"] for r in res)
            verified = sc.get("VERIFIED", 0) + sc.get("VERIFIED_PRESENT", 0)
            unverified = sum(sc.values()) - verified
            stats[(verified, unverified)] += 1
        modal, _count = stats.most_common(1)[0]
        per_feed[fname] = {
            "modal": modal,
            "mixed": len(stats) > 1,
            "spread": {str(k_): v_ for k_, v_ in stats.most_common()},
        }
    return per_feed


def main():
    v1 = ENGINES["v1@K3"][0]   # same production engine, different K cap
    ENGINES["v1@K10"] = (v1, 10)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = [
        "=" * 78,
        "LAYER 2 - RANK NOISE + FLAPPING + SEAT SHUFFLE: v3.1 vs production v1",
        "feedings: v1@K3 = current app client | v1@K10 = schema cap | "
        "v31@full = new design",
        "=" * 78,
    ]
    payload = {"seeds": N_SEEDS, "total": TOTAL, "radius": base.RADIUS}
    payload["rank"] = {"gap2": {}, "gap3": {}}
    payload["flap"] = {"gap2": {}, "gap3": {}}
    payload["shuffle"] = {"gap2": {}, "gap3": {}}

    sigmas = [0.0, 0.2, 0.5, 1.0]
    drops = [0.0, 0.05, 0.1, 0.2, 0.3]

    for gap in (2, 3):
        lines.append("")
        lines.append(f"BLOCK 1 - RANK NOISE (no flapping) | gap {gap} (dx{gap + 1})")
        lines.append(
            f"cell = V/U (modal of {N_SEEDS} seeds; "
            f"'*' = seed-dependent)"
        )
        lines.append("feeding   " + "".join(f"sigma={s:<7}" for s in sigmas))
        for fname in FEEDS:
            row = f"{fname:<9} "
            cells = {}
            for sg in sigmas:
                cell = run_cell(gap, sg, 0.0)
                v, u = cell[fname]["modal"]
                mark = "*" if cell[fname]["mixed"] else " "
                row += f"{v}V/{u}U{mark}  "
                cells[str(sg)] = cell[fname]
            lines.append(row)
            payload["rank"][f"gap{gap}"][fname] = cells

    for gap in (2, 3):
        lines.append("")
        lines.append(f"BLOCK 2 - VISIBILITY FLAPPING (no rank noise) | gap {gap} (dx{gap + 1})")
        lines.append("feeding   " + "".join(f"drop={d:<7}" for d in drops))
        for fname in FEEDS:
            row = f"{fname:<9} "
            cells = {}
            for dp in drops:
                cell = run_cell(gap, 0.0, dp)
                v, u = cell[fname]["modal"]
                mark = "*" if cell[fname]["mixed"] else " "
                row += f"{v}V/{u}U{mark}  "
                cells[str(dp)] = cell[fname]
            lines.append(row)
            payload["flap"][f"gap{gap}"][fname] = cells

    levels = [(60, 42), (70, 49), (80, 56), (90, 63)]
    for gap in (2, 3):
        lines.append("")
        lines.append(
            f"BLOCK 3 - SEAT SHUFFLE (clean radio) | gap {gap} (dx{gap + 1})"
        )
        lines.append(
            "front 35 seats (rows 0-4) always full; remaining present "
            "students sit at random empty seats in rows 5-9"
        )
        lines.append(
            f"cell = V/U (modal of {N_SEEDS} seeds; "
            f"'*' = seed-dependent)"
        )
        lines.append("feeding   " + "".join(f"att{p}%={n:<5}" for p, n in levels))
        for fname in FEEDS:
            row = f"{fname:<9} "
            cells = {}
            for pct, n_present in levels:
                cell = run_cell(gap, 0.0, 0.0, n_present=n_present)
                v, u = cell[fname]["modal"]
                mark = "*" if cell[fname]["mixed"] else " "
                row += f"{v}V/{u}U{mark}  "
                cells[str(pct)] = cell[fname]
            lines.append(row)
            payload["shuffle"][f"gap{gap}"][fname] = cells

    report = "\n".join(lines)
    (OUT_DIR / "report.txt").write_text(report + "\n")
    (OUT_DIR / "results.json").write_text(json.dumps(payload, indent=2) + "\n")
    print(report)
    print(f"\nSaved: {OUT_DIR / 'report.txt'}")
    print(f"Saved: {OUT_DIR / 'results.json'}")


if __name__ == "__main__":
    main()
