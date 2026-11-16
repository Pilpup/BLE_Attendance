#!/usr/bin/env python3
"""Layer 3 adversarial suite for engine v3.1: gang shapes and sizes.

Geometry (same model as the L1 gang test in simulate_v31.py):
  70-seat gap-2 room (build_seats(2)); gang devices stand in a corridor and
  SUBMIT AS ABSENT STUDENTS' roster IDs (submitter count stays == enrolled).
  In-room links (seat-seat) and gang-internal links: radius 4.0, stable.
  Cross links (seat <-> gang): each DIRECTION present independently w.p. p
  when distance <= R_WALL (wall-attenuated hearing range).

Scenarios:
  buddy2      2 corridor devices (x=11.3, y=4/5) submit as S68,S69.
              Pair edge has no third witness (triangle rule) and federation
              needs 4 distinct claimers per side -> should never verify at
              real-wall p. Watch: T(2)=2 is low, so TWO lucky cross mutuals
              to one device can direct-verify it (weak-wall regime only).
  cluster25   25 corridor devices in a line (x=7.3 corridor between the
              block-2 wing and the empty block-3 rows) submit as S45..S69
              (rows 7-9 empty + row-6 tail). Federation floor scales with
              size: req = max(4, ceil(0.3*25)) = 8 claimers per direction.
  split5      5 corridor devices in two far sub-groups (3 @ y1-3, 2 @ y8-9)
              submitting as S65..S69. Sub-groups are 5+ apart (no radio
              link), each too small to federate with the class (needs 4
              claimers per side; pair side has 2, trio side has 3).
  gang_only   Only the 5 gang devices submit (real class absent, ids
              S65..S69). ACCEPTED RESIDUAL (user): largest component = gang
              -> 5 VERIFIED; teacher sees a 5/70 session and uses intuition.
  ghost1      Single ghost device (x=11.3, y=5) submits as S69; 69 real
              students present. Validates the T(1)=1 table entry: a single
              mutual edge is never triangular, so the ghost can only verify
              if TWO seats mutually hear it AND those seats hear each other
              (needs R_WALL >= 2.6 and high p).

Cell = classV/gangV (modal of N_SEEDS; '*' = mixed), like the L1 matrix.
classV = verified among really-present students; gangV = verified among the
spoofed absent IDs.
"""

import argparse
import importlib.util
import json
import math
import random
from collections import Counter
from pathlib import Path

import simulate_satellites as base

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "layer3_out"
N_SEEDS = 200
SEED_BASE = 3000
GANG_X = 11.3        # corridor right of the wall after x=10 (room edge)
CORR_X = 7.3         # corridor between block-2 (x=6) and block-3 (x=9)
R_WALLS = (1.0, 1.4, 1.8, 2.2, 2.6, 3.0)
PS = (0.1, 0.25, 0.5, 1.0)

SCENARIOS = {
    "buddy2": {
        "gang": [(GANG_X, 4), (GANG_X, 5)],
        "absent": (68, 69),
        "blurb": "2 corridor devices submit as absent S68,S69 (68 present)",
    },
    "cluster25": {
        "gang": [(CORR_X, y) for y in range(25)],
        "absent": tuple(range(45, 70)),
        "blurb": "25 corridor devices (x=7.3, y=0..24) submit as absent S45..S69 (45 present)",
    },
    "split5": {
        "gang": [(GANG_X, y) for y in (1, 2, 3, 8, 9)],
        "absent": (65, 66, 67, 68, 69),
        "blurb": "5 corridor devices split 3 @(y1-3) + 2 @(y8-9), submit as absent S65..S69",
    },
    "gang_only": {
        "gang": [(GANG_X, y) for y in (2, 3, 4, 5, 6)],
        "absent": (65, 66, 67, 68, 69),
        "only_gang": True,
        "blurb": "ONLY the 5 gang devices submit (real class absent) - accepted residual",
    },
    "ghost1": {
        "gang": [(GANG_X, 5)],
        "absent": (69,),
        "blurb": "1 ghost device submits as absent S69 (69 present) - T(1)=1 safety check",
    },
}


def load_v31():
    spec = importlib.util.spec_from_file_location(
        "graph_engine_v31", HERE / "graph_engine_v31.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run_cell(engine, spec, r_wall, p, seeds):
    """One (R_WALL, p) cell; returns Counter of (classV, gangV) outcomes."""
    absent = spec["absent"]
    absent_set = set(absent)
    present = [] if spec.get("only_gang") else [
        i for i in range(base.TOTAL) if i not in absent_set
    ]
    n_present = len(present)
    seats = base.build_seats(2)
    pos = [seats[i] for i in present] + list(spec["gang"])

    stats: Counter = Counter()
    for s in range(seeds):
        rng = random.Random(SEED_BASE + s)
        lists = {}
        for i, a in enumerate(pos):
            entries = []
            for j, b in enumerate(pos):
                if i == j:
                    continue
                d = math.hypot(a[0] - b[0], a[1] - b[1])
                cross = (i < n_present) != (j < n_present)
                if cross:
                    if d <= r_wall and rng.random() < p:
                        entries.append((j, d))
                elif d <= base.RADIUS + 1e-9:
                    entries.append((j, d))
            entries.sort(key=lambda e: (e[1], e[0]))
            lists[i] = [j for j, _ in entries]

        def name(idx: int) -> str:
            if idx < n_present:
                return f"S{present[idx]:02d}"
            return f"S{absent[idx - n_present]:02d}"

        reports = {
            name(i): [name(j) for j in lists[i]] for i in range(len(pos))
        }
        res = engine.audit_classroom_mesh(
            reports, total_enrolled=base.TOTAL, face_verified_keys=None
        )
        status = {r["ble_device_id"]: r["status"] for r in res}
        class_v = sum(
            1 for idx in range(n_present)
            if status[name(idx)] == "VERIFIED"
        )
        gang_v = sum(
            1 for g in range(len(spec["gang"]))
            if status[name(n_present + g)] == "VERIFIED"
        )
        stats[(class_v, gang_v)] += 1
    return stats


def scenario_section(engine, sname, spec, lines, payload, r_walls, ps, seeds):
    lines.append("")
    lines.append(f"SCENARIO {sname}: {spec['blurb']}")
    if spec.get("only_gang"):
        stats = run_cell(engine, spec, 0.0, 0.0, seeds)
        (cv, gv), _count = stats.most_common(1)[0]
        lines.append(
            f"  gang-only session: gangV={gv} in {stats[(cv, gv)]}/{seeds} seeds "
            f"(classV={cv} - no real students submit). ACCEPTED residual: "
            "largest component = gang -> teacher sees a tiny session for a "
            "70-student class."
        )
        payload[sname] = {"only": {str(k): v for k, v in stats.items()}}
        return
    lines.append(
        "  cell = classV/gangV (modal; '*' = mixed outcomes), "
        f"{seeds} seeds per cell"
    )
    lines.append("R_WALL  " + "".join(f"p={p:<8}" for p in ps))
    cells = {}
    leaks = {}
    for rw in r_walls:
        row = f"{rw:<6}  "
        lrow = []
        for p in ps:
            stats = run_cell(engine, spec, rw, p, seeds)
            (cv, gv), _count = stats.most_common(1)[0]
            mark = "*" if len(stats) > 1 else " "
            row += f"{cv}/{gv}{mark}      "
            merged = sum(c for k, c in stats.items() if k[1] > 0)
            lrow.append(merged)
            cells[f"R{rw}/p{p}"] = {str(k): v for k, v in stats.items()}
        lines.append(row)
        leaks[f"R{rw}"] = lrow
    lines.append("gang-verified (leak) seeds out of " + str(seeds) + ":")
    lines.append("R_WALL  " + "".join(f"p={p:<8}" for p in ps))
    for rw in r_walls:
        lines.append(
            f"{rw:<6}  " + "".join(f"{v:<10}" for v in leaks[f"R{rw}"])
        )
    payload[sname] = cells
    return


def main():
    ap = argparse.ArgumentParser(description="L3: adversarial gang shapes")
    ap.add_argument("--smoke", action="store_true",
                    help="5 seeds, 2 walls x 2 p per scenario")
    args = ap.parse_args()

    engine = load_v31()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    if args.smoke:
        seeds, r_walls, ps = 5, (1.4, 2.6), (0.25, 1.0)
    else:
        seeds, r_walls, ps = N_SEEDS, R_WALLS, PS

    lines = [
        "=" * 78,
        "LAYER 3 - ADVERSARIAL SUITE: gang shapes and sizes (engine v3.1)",
        "=" * 78,
    ]
    payload: dict = {
        "engine": "v31",
        "total": base.TOTAL,
        "seeds": seeds,
        "radius": base.RADIUS,
        "mode": "smoke" if args.smoke else "full",
    }

    for sname, spec in SCENARIOS.items():
        scenario_section(
            engine, sname, spec, lines, payload, r_walls, ps, seeds
        )

    report = "\n".join(lines)
    suffix = "_smoke" if args.smoke else ""
    (OUT_DIR / f"report{suffix}.txt").write_text(report + "\n")
    (OUT_DIR / f"results{suffix}.json").write_text(
        json.dumps(payload, indent=2) + "\n"
    )
    print(report)
    print(f"\nSaved: {OUT_DIR / f'report{suffix}.txt'}")
    print(f"Saved: {OUT_DIR / f'results{suffix}.json'}")


if __name__ == "__main__":
    main()
