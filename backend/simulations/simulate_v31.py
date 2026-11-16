#!/usr/bin/env python3
"""v3.1 validation harness: 50%-even thresholds, floor-4 federation,
triangle corroboration, and the outside-gang adversarial test.

Imports geometry helpers from simulate_satellites (no copies). Engine:
graph_engine_v31.py. New files only - does not modify the shared harness.

Gang model (the user's Case A / B / C):
  70-seat gap-2 layout (blocks at x 0-1, 4-6, 9-10). Students S65..S69 are
  ABSENT (their seats are empty); 5 gang devices stand in the corridor at
  x = 11.3, y = 2..6 and SUBMIT AS S65..S69 (roster-ID spoofing: submitter
  count stays == enrolled).
  - In-room links (seat-seat): radius 4.0, stable (full lists).
  - Gang-gang links: radius 4.0, stable (co-located in open corridor).
  - Cross-wall links (seat <-> gang): POTENTIAL when Euclidean distance
    <= R_WALL (wall-attenuated range; wall between x=10 (room edge) and the
    corridor, so the nearest seat is 1.3 away). Each DIRECTION of each
    potential link is present independently with probability p_cross per
    session - modeling rare, asymmetric hearing.

Cases:
  A  R_WALL < 1.3: gang invisible. Expect gang rejected at every p.
  C  R_WALL low, p low: rare asymmetric hearing. Floor-4 federation and
     triangle corroboration must reject the gang (target gangV = 0).
  B  R_WALL high, p high: sustained mutual hearing - merge is radio physics
     (accepted residual; face layer / client RSSI floor / roster checks are
     the defenses there).
"""

import argparse
import importlib.util
import json
import math
import random
from collections import Counter
from pathlib import Path

import simulate_satellites as base

TOTAL = base.TOTAL          # 70
N_RANDOM = base.N_RANDOM    # 201
HERE = Path(__file__).resolve().parent

GANG_X = 11.3               # corridor, right of the wall after x=10 (room edge)
GANG_YS = (2, 3, 4, 5, 6)   # 5 devices, one seat-row apart
MIN_CROSS_DIST = GANG_X - 10.0  # 1.3: nearest seat (x=10) to gang


def load_v31():
    spec = importlib.util.spec_from_file_location(
        "graph_engine_v31", HERE / "graph_engine_v31.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run_gang_cell(engine, r_wall, p_cross, seeds):
    """Run one (R_WALL, p) cell; returns Counter of (classV, gangV) outcomes."""
    stats: Counter = Counter()
    for s in range(seeds):
        rng = random.Random(1000 + s)
        seats_all = base.build_seats(2)
        n_gang = len(GANG_YS)
        present = seats_all[: len(seats_all) - n_gang]  # S00..S64 in room
        n_present = len(present)
        gang = [(GANG_X, y) for y in GANG_YS]
        pos = present + gang
        # Absent students S65..S69 have no device in the room; gang device
        # at pos index 65+g submits as S(65+g). id(i) = S{i} for all i.

        lists: dict[int, list[int]] = {}
        for i, a in enumerate(pos):
            entries = []
            for j, b in enumerate(pos):
                if i == j:
                    continue
                d = math.hypot(a[0] - b[0], a[1] - b[1])
                cross = (i < n_present) != (j < n_present)
                if cross:
                    if d <= r_wall and rng.random() < p_cross:
                        entries.append((j, d))
                elif d <= base.RADIUS + 1e-9:
                    entries.append((j, d))
            entries.sort(key=lambda e: (e[1], e[0]))
            lists[i] = [j for j, _ in entries]

        reports = {f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in range(len(pos))}

        res = engine.audit_classroom_mesh(
            reports, total_enrolled=TOTAL, face_verified_keys=None
        )
        status = {r["ble_device_id"]: r["status"] for r in res}
        class_v = sum(
            1 for i in range(n_present)
            if status[f"S{i:02d}"] == "VERIFIED"
        )
        gang_v = sum(
            1 for g in range(n_present, n_present + n_gang)
            if status[f"S{g:02d}"] == "VERIFIED"
        )
        stats[(class_v, gang_v)] += 1
    return stats


def gang_section(engine, report_lines, json_payload):
    r_walls = (1.0, 1.4, 1.8, 2.2, 2.6, 3.0)
    ps = (0.1, 0.25, 0.5, 1.0)
    report_lines.append("")
    report_lines.append(
        f"OUTSIDE-GANG TEST: 5 corridor devices at x={GANG_X}, y={list(GANG_YS)} "
        f"submitting as absent students S65..S69"
    )
    report_lines.append(
        "in-room radius 4.0 stable | cross-wall links: dist<=R_WALL, each "
        f"direction present independently w.p. p | {N_RANDOM} seeds per cell"
    )
    report_lines.append("cell = classV/gangV (modal; '*' = mixed outcomes)")
    report_lines.append("R_WALL  " + "".join(f"p={p:<8}" for p in ps))
    matrix = {}
    for rw in r_walls:
        row = f"{rw:<6}  "
        for p in ps:
            stats = run_gang_cell(engine, rw, p, N_RANDOM)
            (cv, gv), _count = stats.most_common(1)[0]
            mark = "*" if len(stats) > 1 else " "
            row += f"{cv}/{gv}{mark}      "
            matrix[f"R{rw}/p{p}"] = {str(k): v for k, v in stats.items()}
        report_lines.append(row)
    report_lines.append("")
    report_lines.append(
        "classV=65 always: gang activity never breaks class verification."
    )
    report_lines.append(
        "gangV=0: gang rejected - not part of the largest component's "
        "room -> 5x UNVERIFIED (teacher check)."
    )
    report_lines.append(
        "gangV=5: gang merged - Case B radio physics (mutual range); "
        "face layer / client RSSI floor / roster checks are the defenses."
    )
    json_payload["gang_matrix"] = matrix
    return report_lines, json_payload


# --- Local two-flag sections -------------------------------------------------
# The shared simulate_satellites.full_section / radsweep_section / run_cell
# hard-code the old statuses (VERIFIED_PRESENT etc.), so v31 runs self-
# contained copies here that count the two-flag VERIFIED/UNVERIFIED taxonomy.
# Geometry helpers (build_seats, visible_peers, nearest_k,
# mutual_components) still come from the shared module - read-only use.

VERIFIED = "VERIFIED"
UNVERIFIED = "UNVERIFIED"


def _run_cell(engine, vis):
    """Local run_cell for FULL lists, counting two-flag statuses."""
    configs = [("rowmajor", None)] + [
        (f"seed{s}", random.Random(s)) for s in range(N_RANDOM)
    ]
    signatures: Counter = Counter()
    verified: Counter = Counter()
    detailed = None
    for name, rng in configs:
        lists = base.nearest_k(vis, None, rng, full=True)
        comps = base.mutual_components(lists)
        reports = {f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in lists}
        results = engine.audit_classroom_mesh(
            reports, total_enrolled=TOTAL, face_verified_keys=None
        )
        status_counts = Counter(r["status"] for r in results)
        signatures[
            (tuple(sorted(len(c) for c in comps)), tuple(sorted(status_counts.items())))
        ] += 1
        verified[status_counts.get(VERIFIED, 0)] += 1
        if name == "rowmajor":
            detailed = {
                "lists": {f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in lists},
                "comps": [sorted(c) for c in comps],
                "engine": results,
            }
    return signatures, verified, detailed


def _full_section(engine, gaps, report_lines, json_payload):
    report_lines.append("")
    report_lines.append(
        f"FULL-LIST mode (v3 client: report ALL visible peers) - "
        f"radius {base.RADIUS}, {1 + N_RANDOM} seeds per gap | "
        f"two-flag taxonomy: VERIFIED / UNVERIFIED"
    )
    cells = {}
    for gap in gaps:
        seats = base.build_seats(gap)
        vis = base.visible_peers(seats, base.RADIUS)
        signatures, verified, detailed = _run_cell(engine, vis)
        v = verified.get(TOTAL, 0)
        lists = detailed["lists"]
        avg = sum(len(x) for x in lists.values()) / len(lists)
        modal_sig, _ = signatures.most_common(1)[0]
        modal_status = dict(modal_sig[1])
        ver_students = modal_status.get(VERIFIED, 0)
        report_lines.append("")
        report_lines.append(
            f"--- gap {gap} (dx{gap + 1}) --- avg full-list size {avg:.1f} peers"
        )
        for sig, count in signatures.most_common():
            report_lines.append(
                f"  {count}x  component sizes {list(sig[0])}  statuses {dict(sig[1])}"
            )
        report_lines.append(
            f"  VERDICT: {v}/{1 + N_RANDOM} seeds form one anchored room; "
            f"students auto-verified {ver_students}/{TOTAL}, "
            f"UNVERIFIED for teacher check {TOTAL - ver_students}"
        )
        cells[f"gap{gap}"] = {
            "avg_list_size": round(avg, 2),
            "verified": dict(verified),
            "signatures": {str(s): c for s, c in signatures.most_common()},
        }
    json_payload["full_cells"] = cells
    return report_lines, json_payload


def _radsweep_section(engine, gaps, report_lines, json_payload):
    report_lines.append("")
    report_lines.append(
        f"RADIUS x GAP matrix - FULL neighbor lists (no truncation), "
        f"{1 + N_RANDOM} tie-break seeds per cell"
    )
    report_lines.append(
        "cell = V/U students: V = auto-verified, "
        "U = UNVERIFIED for teacher check"
    )
    report_lines.append("radius   " + "".join(f"gap{g}(dx{g + 1})  " for g in gaps))
    matrix = {}
    for r in (4.0, 5.0, 6.0, 7.0):
        row = f"R={r:<5}  "
        for gap in gaps:
            seats = base.build_seats(gap)
            vis = base.visible_peers(seats, r)
            signatures, verified, _ = _run_cell(engine, vis)
            v = verified.get(TOTAL, 0)
            modal_sig, _ = signatures.most_common(1)[0]
            ver_students = dict(modal_sig[1]).get(VERIFIED, 0)
            row += f"{ver_students}V/{TOTAL - ver_students}U    "
            matrix[f"R{r}/gap{gap}"] = {
                "verified": dict(verified),
                "signatures": {str(s): c for s, c in signatures.most_common()},
            }
        report_lines.append(row)
    report_lines.append("")
    report_lines.append(
        "Rule: a cell fully auto-verifies when radius >= aisle dx (gap + 1); "
        "below that the engine marks EVERYONE UNVERIFIED for the teacher "
        "instead of guessing - no one is ever auto-labeled 'outside classroom'."
    )
    json_payload["radsweep"] = matrix
    return report_lines, json_payload


def main():
    ap = argparse.ArgumentParser(
        description="v3.1 validation: 50%-even thresholds, floor-4 federation, "
        "triangle corroboration, outside-gang test"
    )
    ap.add_argument("--gang", action="store_true",
                    help="outside-gang Case A/B/C matrix")
    ap.add_argument("--radsweep", action="store_true",
                    help="radius x gap full-list matrix")
    args = ap.parse_args()

    engine = load_v31()
    if args.gang:
        mode, out_name = "gang", "v31_gang"
    elif args.radsweep:
        mode, out_name = "radsweep", "v31_radsweep"
    else:
        mode, out_name = "sweep", "v31_sweep"
    out_dir = HERE / "layer1_out" / out_name
    out_dir.mkdir(parents=True, exist_ok=True)

    report_lines = [
        "=" * 78,
        "V3.1 VALIDATION - 50%-even threshold, floor-4 federation, "
        "triangle corroboration, two-flag VERIFIED/UNVERIFIED",
        "=" * 78,
    ]
    json_payload: dict = {
        "engine": "v31",
        "total": TOTAL,
        "seeds": N_RANDOM,
        "radius": base.RADIUS,
        "mode": mode,
    }

    if mode == "gang":
        report_lines, json_payload = gang_section(
            engine, report_lines, json_payload
        )
    elif mode == "radsweep":
        report_lines, json_payload = _radsweep_section(
            engine, [2, 3, 4, 5], report_lines, json_payload
        )
    else:
        report_lines, json_payload = _full_section(
            engine, [2, 3, 4, 5], report_lines, json_payload
        )

    report = "\n".join(report_lines)
    (out_dir / "report.txt").write_text(report + "\n")
    (out_dir / "results.json").write_text(
        json.dumps(json_payload, indent=2) + "\n"
    )
    print(report)
    print(f"\nSaved: {out_dir / 'report.txt'}")
    print(f"Saved: {out_dir / 'results.json'}")


if __name__ == "__main__":
    main()
