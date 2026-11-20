#!/usr/bin/env python3
"""Layer 5 - geometry/size/attendance/noise sweep for engine v3.1.

Question: does the locked v3.1 rule set hold when EVERY structural knob
varies - room size (2..200 seats), block arrangement, aisle gap (2-3),
attendance (60-90%), occupancy model, and the L2 radio noise stack?

Dimensions
  patterns   block layouts: [2,3,2] ".. ... ..", [3,2,3] "... .. ...",
             [2,2,2] ".. .. ..", [3,3] "... ...", [2,2] ".. .."
  gap        empty seat-lengths between blocks: 2 and 3 (aisle pitch
             gap+1, same convention as simulate_satellites.build_seats)
  size       seats per room: 2, 6, 12, 25, 50, 100, 200 (row-major grid,
             last row partial so any count is reachable)
  attendance present fraction of seats: 0.60, 0.75, 0.90
  occupancy  front   - L2 model generalized: front half of seats always
                       full, remaining present students at random back
                       seats (eager front-sitters, assigned rear)
             scatter - all occupied seats uniformly random (full shuffle)
  noise      exact L2 stack: membership by TRUE distance, each
             directional link flapped off independently w.p. drop, then
             list ORDER jittered by d + U(-sigma, sigma) (rank noise)
             clean  (0.0, 0.0)
             flap.1 (0.0, 0.1)   flap.3 (0.0, 0.3)
             bad    (0.5, 0.3)   - weird RSSI + heavy flapping

Metrics per cell (modal over seeds, submitters only):
  V = VERIFIED, U = UNVERIFIED (two-flag taxonomy; every non-verified
  outcome collapses to U for teacher manual check - no "outside
  classroom" label exists). min_v = worst-seed verified count.
  mixed = seed-dependent. u_max = worst-seed unverified count.

Engine: graph_engine_v31 (frozen), total_enrolled = seat count.
"""

import json
import math
import random
import time
from collections import Counter
from pathlib import Path

import simulate_satellites as base

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "layer5_out"

RADIUS = base.RADIUS
ENGINE = base.load_engine("v31")

PATTERNS = {
    "2-3-2": [2, 3, 2],
    "3-2-3": [3, 2, 3],
    "2-2-2": [2, 2, 2],
    "3-3": [3, 3],
    "2-2": [2, 2],
}
GAPS = (2, 3)
SIZES = (2, 6, 12, 25, 50, 100, 200)
ATTENDANCE = (0.6, 0.75, 0.9)
OCC_MODES = ("front", "scatter")
NOISE = {
    "clean": (0.0, 0.0),
    "flap.1": (0.0, 0.1),
    "flap.3": (0.0, 0.3),
    "bad": (0.5, 0.3),
}
SEEDS = 25
SEED_BASE = 50000


def build_grid(blocks, gap, n_seats):
    """Row-major seat grid for arbitrary block widths; last row partial.
    Same convention as simulate_satellites.build_seats: within-block
    pitch 1, aisle pitch gap+1, row pitch 1, front = low y."""
    xs = []
    x = 0
    for w in blocks:
        xs.extend(range(x, x + w))
        x += w + gap
    seats = [(x_, y) for y in range(math.ceil(n_seats / len(xs))) for x_ in xs]
    return seats[:n_seats]


def pick_occupied(rng, n_seats, n_present, mode):
    if mode == "scatter":
        return sorted(rng.sample(range(n_seats), n_present))
    front = (n_seats + 1) // 2          # L2: front 35 of 70
    if n_present <= front:
        return list(range(n_present))   # deterministic front block
    back = list(range(front, n_seats))
    rng.shuffle(back)
    return list(range(front)) + sorted(back[: n_present - front])


def build_lists(rng, occ, sigma, drop):
    """L2 noise stack verbatim: membership by true distance <= RADIUS,
    per-direction flapping, list order jittered by U(-sigma, sigma)."""
    n = len(occ)
    lists = {}
    for i in range(n):
        a = occ[i]
        entries = []
        for j in range(n):
            if i == j:
                continue
            d = math.hypot(occ[j][0] - a[0], occ[j][1] - a[1])
            if d > RADIUS + 1e-9:
                continue
            if drop > 0 and rng.random() < drop:
                continue
            entries.append((j, d))
        if sigma > 0:
            entries.sort(key=lambda e: (e[1] + rng.uniform(-sigma, sigma), e[0]))
        else:
            entries.sort(key=lambda e: (e[1], e[0]))
        lists[i] = [j for j, _ in entries]
    return lists


def run_cell(blocks, gap, n_seats, att, occ_mode, sigma, drop):
    seats = build_grid(blocks, gap, n_seats)
    n_present = max(1, round(att * n_seats))
    stats: Counter = Counter()
    min_v = None
    u_max = 0
    for s in range(SEEDS):
        rng = random.Random(SEED_BASE + s)
        occ_idx = pick_occupied(rng, n_seats, n_present, occ_mode)
        occ = [seats[i] for i in occ_idx]
        lists = build_lists(rng, occ, sigma, drop)
        reports = {
            f"S{i:03d}": [f"S{j:03d}" for j in lists[i]] for i in range(n_present)
        }
        res = ENGINE.audit_classroom_mesh(
            reports, total_enrolled=n_seats, face_verified_keys=None
        )
        status = {r["ble_device_id"]: r["status"] for r in res}
        v = u = 0
        for i in range(n_present):
            if status[f"S{i:03d}"] == "VERIFIED":
                v += 1
            else:
                u += 1
        stats[(v, u)] += 1
        min_v = v if min_v is None else min(min_v, v)
        u_max = max(u_max, u)
    modal, _ = stats.most_common(1)[0]
    return {
        "present": n_present,
        "modal": list(modal),
        "mixed": len(stats) > 1,
        "min_v": min_v,
        "u_max": u_max,
    }


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = [
        "=" * 78,
        "LAYER 5 - GEOMETRY/SIZE/ATTENDANCE/NOISE SWEEP (engine v3.1)",
        "cell = modal V/R/X over submitters (V verified, R review, "
        "X isolated=SLANDER); '*' = seed-dependent; mV = worst-seed V",
        f"{SEEDS} seeds/cell, {len(PATTERNS)}x{len(GAPS)}x{len(SIZES)}x"
        f"{len(ATTENDANCE)}x{len(OCC_MODES)}x{len(NOISE)} = "
        f"{len(PATTERNS) * len(GAPS) * len(SIZES) * len(ATTENDANCE) * len(OCC_MODES) * len(NOISE)} cells",
        "=" * 78,
    ]
    payload = {"seeds": SEEDS, "radius": RADIUS, "engine": "v31",
               "sizes": SIZES, "attendance": ATTENDANCE}
    t0 = time.time()
    loads = []

    for nname, (sigma, drop) in NOISE.items():
        lines.append("")
        lines.append(f"NOISE = {nname} (sigma={sigma}, drop={drop})")
        for pname, blocks in PATTERNS.items():
            for gap in GAPS:
                for occ in OCC_MODES:
                    head = (f"{pname} gap{gap} {occ}  "
                            f"(cols {sum(blocks)}, aisle pitch {gap + 1})")
                    lines.append("")
                    lines.append(head)
                    lines.append("seats  " + "".join(
                        f"att={a:<11}" for a in ATTENDANCE))
                    for n_seats in SIZES:
                        row = f"{n_seats:<5} "
                        cells = {}
                        for att in ATTENDANCE:
                            st = run_cell(blocks, gap, n_seats, att,
                                          occ, sigma, drop)
                            cells[str(att)] = st
                            v, u = st["modal"]
                            mark = "*" if st["mixed"] else " "
                            row += (f"{v}V/{u}U{mark} "
                                    f"m{st['min_v']:<4}")
                            if u > 0 and st["present"] >= 3:
                                loads.append(
                                    (nname, pname, gap, occ, n_seats,
                                     att, st))
                        payload.setdefault(nname, {}).setdefault(
                            f"{pname}/gap{gap}/{occ}", {})[str(n_seats)] = cells
                        lines.append(row)

    lines.append("")
    lines.append("=" * 78)
    lines.append("UNVERIFIED-LOAD AUDIT (U on submitters, present >= 3):")
    per_noise: Counter = Counter()
    worst: dict = {}
    for nname, pname, gap, occ, n_seats, att, st in loads:
        per_noise[nname] += 1
        cur = worst.get(nname)
        if cur is None or st["modal"][1] > cur[1]:
            worst[nname] = (f"{pname} gap{gap} {occ} {n_seats}seats "
                            f"att{att}: modal {st['modal']}", st["modal"][1])
    if loads:
        for nname in NOISE:
            lines.append(
                f"  {nname}: {per_noise.get(nname, 0)} cells send in-room "
                f"students to teacher check; worst: {worst.get(nname, ('-', 0))[0]}"
            )
    else:
        lines.append("  none - every in-room student auto-verified in all cells")
    lines.append("")
    lines.append("All U outcomes are soft (teacher manual check, no "
                 "'outside classroom' auto-verdict). Structural floors: a "
                 "single submitter cannot verify (no mutual possible); "
                 "exactly 2 present verify via the two-phone pair rule "
                 "(mutual A<->B, rule 8).")
    lines.append(f"runtime {time.time() - t0:.0f}s")

    report = "\n".join(lines)
    (OUT_DIR / "report.txt").write_text(report + "\n")
    (OUT_DIR / "results.json").write_text(json.dumps(payload, indent=2) + "\n")
    print(report)
    print(f"\nSaved: {OUT_DIR / 'report.txt'}")
    print(f"Saved: {OUT_DIR / 'results.json'}")


if __name__ == "__main__":
    main()
