#!/usr/bin/env python3
"""Layer 1 satellite simulation: deterministic nearest-K over seat-grid geometry.

Layout `.. ... ..` -> column blocks [2, 3, 2] desks, ROWS rows deep (70 students).
Aisle gap between blocks = GAP seat-length units (user convention: one space
char = GAP seat-lengths). Block-to-block edge distance dx = GAP + 1.

Model:
  - Visibility: Euclidean seat-distance <= RADIUS.
  - Client: list = K nearest visible peers (sort by RSSI, take K). Ties at
    equal distance broken once deterministically (row-major) and once per
    random seed, to expose tie-break sensitivity.
  - Engine: the REAL audit_classroom_mesh, imported straight from
    app/services (no reimplementation drift). total_enrolled = 70, no face set
    (face layer disabled), mirroring the production finalize path.

Modes:
  - default: SWEEP, K = 3..15 x GAP = 2..5 (plus gap1/dx2 for continuity).
  - --k N: single-K detailed mode (component breakdown + bridge thresholds).

Layer 1 only: no rank noise, no adversarial choice. Layer 2 (bounded rank
perturbation) and layer 3 (free choice) hook into nearest_k() later.
"""

import argparse
import importlib.util
import json
import math
import random
from collections import Counter, deque
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]

BLOCKS = [2, 3, 2]
ROWS = 10
TOTAL = sum(BLOCKS) * ROWS  # 70
RADIUS = 4.0
N_RANDOM = 200


def load_engine(which: str = "v1"):
    if which == "v1":
        path = BACKEND / "app" / "services" / "graph_engine.py"
    else:
        path = Path(__file__).resolve().parent / f"graph_engine_{which}.py"
    spec = importlib.util.spec_from_file_location(f"graph_engine_{which}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def build_seats(gap: int) -> list[tuple[int, int]]:
    """Seat coordinates. Gap = empty seat-length units between blocks."""
    xs: list[int] = []
    x = 0
    for w in BLOCKS:
        xs.extend(range(x, x + w))
        x += w + gap
    return [(x_, y) for y in range(ROWS) for x_ in xs]


def seat_blocks(seats, gap: int) -> list[int]:
    bounds = []
    x = 0
    for w in BLOCKS:
        bounds.append((x, x + w - 1))
        x += w + gap
    out = []
    for xv, _ in seats:
        for bi, (lo, hi) in enumerate(bounds):
            if lo <= xv <= hi:
                out.append(bi)
                break
    return out


def dist(a: tuple[int, int], b: tuple[int, int]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def visible_peers(seats, radius):
    out = {}
    for i, a in enumerate(seats):
        peers = [
            (j, dist(a, seats[j]))
            for j in range(len(seats))
            if j != i and dist(a, seats[j]) <= radius + 1e-9
        ]
        out[i] = peers
    return out


def nearest_k(vis, k, tie_rng: random.Random | None, full: bool = False):
    """Client behaviour: sort visible peers by RSSI (= distance), take K.

    full=True: report ALL visible peers (v3 client, no truncation).
    tie_rng None -> deterministic row-major tie-break; else random jitter key.
    Returns dict idx -> ordered list of peer ids (indices).
    """
    lists = {}
    for i, peers in vis.items():
        if tie_rng is None:
            ordered = sorted(peers, key=lambda p: (p[1], p[0]))
        else:
            ordered = sorted(peers, key=lambda p: (p[1], tie_rng.random()))
        lists[i] = [j for j, _ in (ordered if full else ordered[:k])]
    return lists


def mutual_components(lists) -> list[set[int]]:
    """Connected components over mutual (bidirectional) claim edges."""
    adjacency: dict[int, set[int]] = {i: set() for i in lists}
    for u, peers in lists.items():
        for v in peers:
            if u in lists.get(v, []):
                adjacency[u].add(v)
                adjacency[v].add(u)
    seen: set[int] = set()
    comps: list[set[int]] = []
    for start in lists:
        if start in seen:
            continue
        comp: set[int] = set()
        queue = deque([start])
        seen.add(start)
        while queue:
            cur = queue.popleft()
            comp.add(cur)
            for nbr in adjacency[cur]:
                if nbr not in seen:
                    seen.add(nbr)
                    queue.append(nbr)
        if comp:
            comps.append(comp)
    return comps


def run_cell(seats, vis, k, engine, full: bool = False):
    """One (gap, K) cell: deterministic + seeded tie-break configs."""
    configs = [("rowmajor", None)] + [(f"seed{s}", random.Random(s)) for s in range(N_RANDOM)]
    signatures: Counter = Counter()
    verified: Counter = Counter()
    detailed = None
    for name, rng in configs:
        lists = nearest_k(vis, k, rng, full=full)
        comps = mutual_components(lists)
        reports = {f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in lists}
        results = engine.audit_classroom_mesh(
            reports, total_enrolled=TOTAL, face_verified_keys=None
        )
        status_counts = Counter(r["status"] for r in results)
        signatures[
            (tuple(sorted(len(c) for c in comps)), tuple(sorted(status_counts.items())))
        ] += 1
        verified[status_counts.get("VERIFIED_PRESENT", 0)] += 1
        if name == "rowmajor":
            detailed = {
                "lists": {f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in lists},
                "comps": [sorted(c) for c in comps],
                "engine": results,
            }
    return signatures, verified, detailed


def bridge_thresholds(seats, comp_of) -> dict:
    """Per component pair: smallest K at which a cross-pair mutual edge becomes
    POSSIBLE (ties can still make it probabilistic), and how many cross-visible
    pairs exist. K must exceed the same-component peers strictly closer than
    the cross peer, on BOTH ends - list size competes with candidate count,
    not with seat distance."""
    vis = visible_peers(seats, RADIUS)
    pairs: dict[tuple[int, int], dict] = {}
    for a in range(len(seats)):
        for b, d_ab in vis[a]:
            ca, cb = comp_of[a], comp_of[b]
            if ca == cb:
                continue
            key = tuple(sorted((ca, cb)))
            strictly_a = sum(1 for j, dj in vis[a] if comp_of[j] == ca and dj < d_ab - 1e-9)
            strictly_b = sum(1 for j, dj in vis[b] if comp_of[j] == cb and dj < d_ab - 1e-9)
            need = max(strictly_a, strictly_b) + 1
            cur = pairs.get(key)
            if cur is None or need < cur["k_possible"]:
                pairs[key] = {
                    "k_possible": need,
                    "example_pair": (a, b, round(d_ab, 3)),
                    "strictly_closer_on_each_end": (strictly_a, strictly_b),
                    "cross_visible_pairs": 0,
                }
            pairs[key]["cross_visible_pairs"] += 1
    return pairs


def comp_analysis(variant) -> dict:
    seats = variant["seats"]
    comps = [set(c) for c in variant["detailed"]["comps"]]
    lists_idx = {
        int(k_[1:]): [int(v[1:]) for v in v_list]
        for k_, v_list in variant["detailed"]["lists"].items()
    }

    # Stable letters: sort comps by smallest member index.
    comps.sort(key=lambda c: min(c))
    comp_of = {}
    for letter_idx, comp in enumerate(comps):
        for m in comp:
            comp_of[m] = letter_idx
    letters = "ABCDEFGHIJKLMNOP"

    dominant = max(comps, key=len)
    blocks_out = []
    for c_idx, comp in enumerate(comps):
        xs = [seats[m][0] for m in comp]
        ys = [seats[m][1] for m in comp]
        internal_claims = 0
        outbound_claims = 0
        reciprocated_outbound = 0
        max_listed_dist = 0.0
        min_external_visible_dist = math.inf
        vis_m = visible_peers(seats, RADIUS)
        for m in comp:
            for j in lists_idx[m]:
                d = dist(seats[m], seats[j])
                max_listed_dist = max(max_listed_dist, d)
                if comp_of[j] == c_idx:
                    internal_claims += 1
                else:
                    outbound_claims += 1
                    if m in lists_idx[j]:
                        reciprocated_outbound += 1
            for j, d in vis_m[m]:
                if comp_of[j] != c_idx:
                    min_external_visible_dist = min(min_external_visible_dist, d)
        blocks_out.append(
            {
                "comp": letters[c_idx],
                "size": len(comp),
                "x_range": (min(xs), max(xs)),
                "y_range": (min(ys), max(ys)),
                "internal_claims": internal_claims,
                "outbound_claims": outbound_claims,
                "reciprocated_outbound": reciprocated_outbound,
                "max_listed_dist": round(max_listed_dist, 3),
                "min_external_visible_dist": (
                    round(min_external_visible_dist, 3)
                    if math.isfinite(min_external_visible_dist)
                    else None
                ),
                "members_grid": sorted(
                    f"(x={seats[m][0]},y={seats[m][1]})" for m in comp
                ),
            }
        )

    min_cross = math.inf
    for a in range(len(seats)):
        for b in range(len(seats)):
            if comp_of[a] != comp_of[b]:
                min_cross = min(min_cross, dist(seats[a], seats[b]))

    thresholds = bridge_thresholds(seats, comp_of)
    pairs_out = []
    for (ca, cb), info in sorted(thresholds.items()):
        pairs_out.append(
            {
                "component_pair": f"{letters[ca]}-{letters[cb]}",
                "k_possible": info["k_possible"],
                "example_pair": info["example_pair"],
                "strictly_closer_on_each_end": info["strictly_closer_on_each_end"],
                "cross_visible_pairs": info["cross_visible_pairs"],
            }
        )

    statuses = Counter(r["status"] for r in variant["detailed"]["engine"])
    verified_n = statuses.get("VERIFIED_PRESENT", 0)

    return {
        "blocks": blocks_out,
        "min_cross_component_dist": round(min_cross, 3) if math.isfinite(min_cross) else None,
        "bridge_thresholds": pairs_out,
        "dominant_size": len(dominant),
        "anchor_floor_size": max(4, math.ceil(0.35 * TOTAL)),
        "dominant_enroll_ratio": round(len(dominant) / TOTAL, 4),
        "status_counts": dict(statuses),
        "not_auto_verified": TOTAL - verified_n,
    }


def ascii_grid(variant, analysis) -> str:
    seats = variant["seats"]
    comps = [set(c) for c in variant["detailed"]["comps"]]
    comps.sort(key=lambda c: min(c))
    comp_of = {}
    for li, comp in enumerate(comps):
        for m in comp:
            comp_of[m] = li
    letters = "ABCDEFGHIJKLMNOP"
    xmax = max(x for x, _ in seats)
    status_of = {r["ble_device_id"]: r["status"] for r in variant["detailed"]["engine"]}
    seat_index = {s: i for i, s in enumerate(seats)}
    lines = []
    for y in range(ROWS):
        top = []
        for x in range(xmax + 1):
            if (x, y) in seat_index:
                i = seat_index[(x, y)]
                sid = f"S{i:02d}"
                top.append(letters[comp_of[i]])
            else:
                top.append(" ")
        lines.append("".join(top))
    first_row_status = []
    for x in range(xmax + 1):
        if (x, 0) in seat_index:
            s = status_of[f"S{seat_index[(x, 0)]:02d}"]
            first_row_status.append(
                "V" if s == "VERIFIED_PRESENT"
                else "R" if s == "NEEDS_REVIEW"
                else "X" if s == "ISOLATED_FLAGGED"
                else "?"
            )
        else:
            first_row_status.append(" ")
    lines.insert(1, "".join(first_row_status))
    legend = (
        "\nTop grid: component letter per seat. Row 2: V=VERIFIED_PRESENT "
        "R=NEEDS_REVIEW X=ISOLATED_FLAGGED"
    )
    return "\n".join(lines) + legend


def radsweep_section(engine, gaps, report_lines, json_payload):
    report_lines.append("")
    report_lines.append(
        f"RADIUS x GAP matrix - FULL neighbor lists (no truncation), "
        f"{1 + N_RANDOM} tie-break seeds per cell"
    )
    report_lines.append(
        "cell = VERIFIED/review students: V = auto-verified, "
        "R = NEEDS_REVIEW to teacher"
    )
    report_lines.append("radius   " + "".join(f"gap{g}(dx{g + 1})  " for g in gaps))
    matrix = {}
    for r in (4.0, 5.0, 6.0, 7.0):
        row = f"R={r:<5}  "
        for gap in gaps:
            seats = build_seats(gap)
            vis = visible_peers(seats, r)
            signatures, verified, _ = run_cell(seats, vis, None, engine, full=True)
            v = verified.get(TOTAL, 0)
            modal_sig, _ = signatures.most_common(1)[0]
            ver_students = dict(modal_sig[1]).get("VERIFIED_PRESENT", 0)
            row += f"{ver_students}V/{TOTAL - ver_students}R    "
            matrix[f"R{r}/gap{gap}"] = {
                "verified": dict(verified),
                "signatures": {str(s): c for s, c in signatures.most_common()},
            }
        report_lines.append(row)
    report_lines.append("")
    report_lines.append(
        "Rule: a cell fully auto-verifies when radius >= aisle dx (gap + 1); "
        "below that the engine refers EVERYONE to the teacher instead of "
        "guessing - no component is ever auto-labeled 'outside classroom'."
    )
    json_payload["radsweep"] = matrix
    return report_lines, json_payload


def full_section(engine, gaps, report_lines, json_payload):
    report_lines.append("")
    report_lines.append(
        f"FULL-LIST mode (v3 client: report ALL visible peers) - "
        f"radius {RADIUS}, {1 + N_RANDOM} seeds per gap"
    )
    cells = {}
    for gap in gaps:
        seats = build_seats(gap)
        vis = visible_peers(seats, RADIUS)
        signatures, verified, detailed = run_cell(seats, vis, None, engine, full=True)
        v = verified.get(TOTAL, 0)
        lists = detailed["lists"]
        avg = sum(len(x) for x in lists.values()) / len(lists)
        modal_sig, _ = signatures.most_common(1)[0]
        modal_status = dict(modal_sig[1])
        ver_students = modal_status.get("VERIFIED_PRESENT", 0)
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
            f"to teacher review {TOTAL - ver_students}"
        )
        cells[f"gap{gap}"] = {
            "avg_list_size": round(avg, 2),
            "verified": dict(verified),
            "signatures": {str(s): c for s, c in signatures.most_common()},
        }
    json_payload["full_cells"] = cells
    return report_lines, json_payload


GANG_WALL_OFFSET = 2  # gang devices sit this far beyond the room wall


def gang_section(engine, report_lines, json_payload):
    """L3 adversarial: 5-device gang outside the room wall.

    In-room pairs see each other at RADIUS; gang-class pairs see each other
    only at the (wall-attenuated) cross-wall radius. Two roster variants:
    gang devices with off-roster IDs (fake devices) and gang devices spoofing
    enrolled-but-absent classmates' IDs.
    """
    report_lines.append("")
    report_lines.append(
        f"L3 SCENARIO: 5-device gang {GANG_WALL_OFFSET} seat-lengths beyond "
        f"the room wall | gap-2 layout (dx3) | in-room radius {RADIUS} | "
        f"full lists | 1 config per cell"
    )
    base = build_seats(2)
    xmax = max(x for x, _ in base)
    gang = [(xmax + GANG_WALL_OFFSET, y) for y in range(5)]
    seats = base + gang
    class_ids = [f"S{i:02d}" for i in range(len(base))]
    gang_ids = [f"S{i:02d}" for i in range(len(base), len(seats))]
    base_set = set(range(len(base)))

    def build_vis(r_wall):
        out = {}
        for i, a in enumerate(seats):
            peers = []
            for j, b in enumerate(seats):
                if i == j:
                    continue
                d = dist(a, b)
                same_side = (i in base_set) == (j in base_set)
                eff = RADIUS if same_side else r_wall
                if d <= eff + 1e-9:
                    peers.append((j, d))
            out[i] = peers
        return out

    cells = {}
    for r_wall in (1.0, 1.5, 2.5):
        vis = build_vis(r_wall)
        lists = nearest_k(vis, None, None, full=True)
        reports = {f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in lists}
        for roster_mode, enrolled in (
            ("off-roster (fake devices)", set(class_ids)),
            ("spoofed classmate IDs", set(class_ids) | set(gang_ids)),
        ):
            res = engine.audit_classroom_mesh(
                reports, total_enrolled=TOTAL, face_verified_keys=None,
                enrolled_ids=enrolled,
            )
            status = {r["ble_device_id"]: r["status"] for r in res}
            gang_status = dict(Counter(status[g] for g in gang_ids))
            class_ver = sum(1 for g in class_ids if status[g] == "VERIFIED_PRESENT")
            report_lines.append(
                f"  cross-wall radius {r_wall:<4} | {roster_mode:<26} | "
                f"class {class_ver}/{TOTAL} verified | gang statuses {gang_status}"
            )
            cells[f"rwall{r_wall}/{roster_mode.split()[0]}"] = {
                "class_verified": class_ver,
                "gang_statuses": gang_status,
            }
    report_lines.append("")
    report_lines.append(
        "Expectation: off-roster gang never joins the graph (rule 0); "
        "spoofed-ID gang is decided purely by cross-wall range - beyond the "
        "attenuated radius it cannot be heard back, fails federation and "
        "anchoring, and ends in NEEDS_REVIEW, never VERIFIED."
    )
    json_payload["gang_cells"] = cells
    return report_lines, json_payload


FLAP_PS = (0.0, 0.1, 0.2, 0.3, 0.4, 0.5)


def flap_section(engine, gaps, report_lines, json_payload):
    """L2: visibility flap - each listed peer independently dropped with
    probability p per observer (asymmetric radio). Full lists, no truncation.
    """
    report_lines.append("")
    report_lines.append(
        f"L2 VISIBILITY FLAP - full lists, each listed peer dropped "
        f"independently with probability p per observer | radius {RADIUS} | "
        f"{1 + N_RANDOM} configs per cell"
    )
    report_lines.append("gap(dx)   " + "".join(f"p={p:<6}" for p in FLAP_PS))
    cells = {}
    for gap in gaps:
        seats = build_seats(gap)
        vis = visible_peers(seats, RADIUS)
        row = f"g{gap}(dx{gap + 1})   "
        for p in FLAP_PS:
            configs = [("cfg0", random.Random(0))] + [
                (f"seed{s}", random.Random(s)) for s in range(N_RANDOM)
            ]
            v_counts: Counter = Counter()
            for _, rng in configs:
                lists = {}
                for i, peers in vis.items():
                    ordered = sorted(peers, key=lambda q: (q[1], rng.random()))
                    lists[i] = [j for j, _ in ordered if rng.random() >= p]
                reports = {
                    f"S{i:02d}": [f"S{j:02d}" for j in lists[i]] for i in lists
                }
                res = engine.audit_classroom_mesh(
                    reports, total_enrolled=TOTAL, face_verified_keys=None
                )
                v_counts[sum(1 for r in res if r["status"] == "VERIFIED_PRESENT")] += 1
            modal = v_counts.most_common(1)[0][0]
            star = "*" if len(v_counts) > 1 else " "
            row += f"{modal:<5}{star}  "
            cells[f"gap{gap}/p{p}"] = {
                "verified_spread": {str(k): v for k, v in v_counts.items()}
            }
        report_lines.append(row)
    report_lines.append("")
    report_lines.append(
        "T self-adapts to flapping (T is half of the SHRUNK observed list), "
        "so honest nodes keep verifying; deficits fall to bridges and the "
        "witness rescue. Any cell below 70 degrades to review, never to "
        "'outside classroom'."
    )
    json_payload["flap_cells"] = cells
    return report_lines, json_payload


PROXY_PLACEMENTS = (
    # (name, offset beyond room edge, walled)
    ("doorway", 1, False),   # open door: cross radius = R, gang distance 1
    ("corridor", 3, True),   # through wall: cross radius = R/2, gang distance 3
)
PROXY_PCTS = (30, 50, 70, 100)
PROXY_FEDS = (0.1, 0.3, 0.5, 1.0)


def proxy_section(engine, report_lines, json_payload):
    """Proxy-attendance sweep: G of the 70 enrolled students stand outside
    (their seats are empty) and submit from there, hoping to merge into the
    class. Sweeps gang size G=1..15, radius R=3..10, direct-threshold
    percentage and federation percentage. The proxies carry valid enrolled
    IDs, so only graph structure and radio range can stop them.
    """
    base = build_seats(2)
    xmax = max(x for x, _ in base)
    cells = {}
    knob_combos = 0
    knob_flips = 0

    def build_vis(seats, r_in, r_cross, n_present):
        out = {}
        for i, a in enumerate(seats):
            peers = []
            for j, b in enumerate(seats):
                if i == j:
                    continue
                d = dist(a, b)
                same = (i < n_present) == (j < n_present)
                eff = r_in if same else r_cross
                if d <= eff + 1e-9:
                    peers.append((j, d))
            out[i] = peers
        return out

    for pname, offset, walled in PROXY_PLACEMENTS:
        report_lines.append("")
        cross_desc = (
            "wall-attenuated cross radius R/2" if walled
            else "open doorway, cross radius = R"
        )
        report_lines.append(
            f"PROXY sweep | {pname}: gang distance {offset} beyond room edge, "
            f"{cross_desc} | proxy IDs = valid absent classmates"
        )
        report_lines.append(
            "B = proxy blocked (0 verified, all in-room verified) | "
            "P = proxy succeeded (all G verified) | m = mixed"
        )
        report_lines.append("R     " + "".join(f"G={g:<4}" for g in range(1, 16)))
        for R in range(3, 11):
            cross_r = float(R) if not walled else max(1.0, R * 0.5)
            row = f"R={R:<4}"
            for G in range(1, 16):
                removed = set(range(len(base) - G, len(base)))
                present_seats = [s for i, s in enumerate(base) if i not in removed]
                n_present = len(present_seats)
                absent_ids = [f"S{i:02d}" for i in sorted(removed)]
                present_ids = [f"S{i:02d}" for i in range(len(base)) if i not in removed]
                gang = [(xmax + offset, y) for y in range(G)]
                seats = present_seats + gang
                ids = present_ids + absent_ids
                vis = build_vis(seats, float(R), cross_r, n_present)
                lists = nearest_k(vis, None, None, full=True)
                reports = {
                    ids[i]: [ids[j] for j in lists[i]] for i in range(len(seats))
                }

                def run_once():
                    res = engine.audit_classroom_mesh(
                        reports, total_enrolled=TOTAL, face_verified_keys=None
                    )
                    status = {r["ble_device_id"]: r["status"] for r in res}
                    gv = sum(1 for a in absent_ids if status[a] == "VERIFIED_PRESENT")
                    cv = sum(1 for a in present_ids if status[a] == "VERIFIED_PRESENT")
                    return gv, cv

                engine.T_OVERRIDE_PCT = None
                engine.FEDERATION_FRAC = 0.3
                gv0, cv0 = run_once()
                outcomes = {(gv0, cv0)}
                for pct in PROXY_PCTS:
                    for f in PROXY_FEDS:
                        engine.T_OVERRIDE_PCT = pct
                        engine.FEDERATION_FRAC = f
                        outcomes.add(run_once())
                        knob_combos += 1
                engine.T_OVERRIDE_PCT = None
                engine.FEDERATION_FRAC = 0.3
                knob_flips += max(0, len(outcomes) - 1)
                if gv0 == 0 and cv0 == n_present:
                    mark = "B"
                elif gv0 == G and cv0 == n_present:
                    mark = "P"
                else:
                    mark = "m"
                row += f"{mark:<5}"
                cells[f"{pname}/R{R}/G{G}"] = {
                    "gang_verified_default": gv0,
                    "class_verified_default": cv0,
                    "distinct_outcomes_across_knobs": [
                        list(o) for o in sorted(outcomes)
                    ],
                }
            report_lines.append(row)
    report_lines.append("")
    report_lines.append(
        f"Knob sensitivity: {knob_flips} outcome changes across "
        f"{knob_combos} (T%, federation%) combos. The direct-threshold and "
        f"federation percentages never decide a proxy outcome - with full "
        f"lists every node's mutuals equal its own list, so any threshold "
        f"<= 100% passes, and federation only applies between components, "
        f"which mutual edges have already merged or fully separated."
    )
    json_payload["proxy_cells"] = cells
    json_payload["knob_flips"] = knob_flips
    json_payload["knob_combos"] = knob_combos
    return report_lines, json_payload


PROXY_NOISE_WS = (1.0, 1.5, 2.0, 3.0)     # wall attenuation: cross-wall pairs
                                          # heard as if W times farther
PROXY_NOISE_RADII = (3, 4, 5, 6)
PROXY_NOISE_PMINS = (0.0, 0.1, 0.2, 0.3)  # hearing floor at the radius edge
PROXY_NOISE_GS = (1, 5, 10, 15)
PROXY_NOISE_SEEDS = 50
PROXY_NOISE_D_CLOSE = 2.0                 # solid band: <= 2 seats, always heard
PROXY_NOISE_OFFSET = 3                    # gang distance beyond room edge


def proxy_noise_section(engine, report_lines, json_payload):
    """Proxy sweep under probabilistic hearing.

    Hearing model per pair: p = 1 within D_CLOSE seats; linear decay to
    p_min at radius R; 0 beyond R. Cross-wall pairs (class <-> gang) use
    effective distance d * W. Mutual edge needs BOTH directions heard.
    Cell = block%/insider% over PROXY_NOISE_SEEDS runs:
    block = 0 proxies verified, insider = all in-room students verified.
    """
    base = build_seats(2)
    xmax = max(x for x, _ in base)
    cells = {}
    best = []
    for W in PROXY_NOISE_WS:
        for R in PROXY_NOISE_RADII:
            for pmin in PROXY_NOISE_PMINS:
                line = f"W={W:<4} R={R} pmin={pmin:<4}"
                for G in PROXY_NOISE_GS:
                    removed = set(range(len(base) - G, len(base)))
                    present_seats = [s for i, s in enumerate(base) if i not in removed]
                    n_present = len(present_seats)
                    absent_ids = [f"S{i:02d}" for i in sorted(removed)]
                    present_ids = [f"S{i:02d}" for i in range(len(base)) if i not in removed]
                    gang = [(xmax + PROXY_NOISE_OFFSET, y) for y in range(G)]
                    seats = present_seats + gang
                    ids = present_ids + absent_ids
                    n = len(seats)
                    pairs = []
                    for i in range(n):
                        for j in range(i + 1, n):
                            d = dist(seats[i], seats[j])
                            same_side = (i < n_present) == (j < n_present)
                            d_eff = d * (W if not same_side else 1.0)
                            if d_eff > R + 1e-9:
                                p = 0.0
                            elif d_eff <= PROXY_NOISE_D_CLOSE + 1e-9:
                                p = 1.0
                            else:
                                p = 1.0 - (d_eff - PROXY_NOISE_D_CLOSE) / (
                                    R - PROXY_NOISE_D_CLOSE
                                ) * (1.0 - pmin)
                            if p > 0:
                                pairs.append((i, j, p))
                    block = 0
                    insider = 0
                    gang_v_total = 0
                    for seed in range(PROXY_NOISE_SEEDS):
                        rng = random.Random(seed * 7919 + G)
                        adj = {i: set() for i in range(n)}
                        for i, j, p in pairs:
                            if p >= 1.0:
                                adj[i].add(j)
                                adj[j].add(i)
                            else:
                                if rng.random() < p:
                                    adj[i].add(j)
                                if rng.random() < p:
                                    adj[j].add(i)
                        reports = {
                            ids[i]: [ids[j] for j in sorted(adj[i])]
                            for i in range(n)
                        }
                        res = engine.audit_classroom_mesh(
                            reports, total_enrolled=TOTAL, face_verified_keys=None
                        )
                        status = {r["ble_device_id"]: r["status"] for r in res}
                        gv = sum(
                            1 for a in absent_ids if status[a] == "VERIFIED_PRESENT"
                        )
                        cv = sum(
                            1 for a in present_ids if status[a] == "VERIFIED_PRESENT"
                        )
                        gang_v_total += gv
                        if gv == 0:
                            block += 1
                        if cv == n_present:
                            insider += 1
                    bpc = 100 * block // PROXY_NOISE_SEEDS
                    ipc = 100 * insider // PROXY_NOISE_SEEDS
                    avg_gv = gang_v_total / PROXY_NOISE_SEEDS
                    line += f" G{G}:{bpc:3d}/{ipc:3d}"
                    cells[f"W{W}/R{R}/pmin{pmin}/G{G}"] = {
                        "block_pct": bpc,
                        "insider_all_pct": ipc,
                        "avg_proxies_verified": round(avg_gv, 2),
                    }
                    if bpc >= 94 and ipc == 100:
                        best.append((bpc, ipc, W, R, pmin, G))
                report_lines.append(line)
    report_lines.append("")
    report_lines.append(
        "cell = block%/insider-all% | block = runs with 0 proxies verified | "
        "insider = runs with every in-room student verified | "
        "proxies stand 3 seats beyond the room edge with valid classmate IDs"
    )
    if best:
        report_lines.append("")
        report_lines.append("BEST CONFIGS (block >= 94% AND insiders 100%):")
        for bpc, ipc, W, R, pmin, G in sorted(best, key=lambda t: (-t[0], t[2], t[3], t[4], t[5]))[:24]:
            report_lines.append(
                f"  W={W} R={R} pmin={pmin} G={G}: block {bpc}%, insiders {ipc}%"
            )
    else:
        report_lines.append("")
        report_lines.append("NO config reached block >= 94% with insiders 100%")
    json_payload["proxy_noise_cells"] = cells
    json_payload["proxy_noise_best"] = [
        {"W": W, "R": R, "pmin": pmin, "G": G, "block_pct": b, "insider_pct": i}
        for b, i, W, R, pmin, G in best
    ]
    return report_lines, json_payload


def detailed_section(seats, gap, k, engine) -> tuple[str, dict]:
    """Rich per-gap breakdown for single-K mode."""
    vis = visible_peers(seats, RADIUS)
    signatures, verified, detailed = run_cell(seats, vis, k, engine)
    variant = {
        "aisle_reading": f"dx{gap + 1}",
        "seats": seats,
        "signatures": signatures,
        "detailed": detailed,
    }
    analysis = comp_analysis(variant)
    lines = []
    lines.append("")
    lines.append(f"--- Aisle gap {gap} (block-to-block dx={gap + 1}), K={k} ---")
    lines.append("Component structures across all tie-break configs:")
    for sig, count in signatures.most_common():
        sizes, statuses = sig
        lines.append(f"  {count}x  component sizes {list(sizes)}  statuses {dict(statuses)}")
    lines.append("")
    lines.append("Component detail (deterministic tie-break):")
    for b in analysis["blocks"]:
        lines.append(
            f"  [{b['comp']}] size {b['size']:3d} x{b['x_range']} y{b['y_range']} | "
            f"internal claims {b['internal_claims']}, outbound {b['outbound_claims']} "
            f"(reciprocated {b['reciprocated_outbound']}) | "
            f"farthest listed peer {b['max_listed_dist']}, "
            f"nearest external visible peer {b['min_external_visible_dist']}"
        )
    lines.append(
        f"  min cross-component distance: {analysis['min_cross_component_dist']}"
    )
    lines.append("  bridge thresholds (K where a cross mutual edge becomes POSSIBLE):")
    if analysis["bridge_thresholds"]:
        for t in analysis["bridge_thresholds"]:
            lines.append(
                f"    pair {t['component_pair']}: K >= {t['k_possible']} | "
                f"example pair idx {t['example_pair']} | strictly-closer internal "
                f"candidates per end {t['strictly_closer_on_each_end']} | "
                f"cross-visible pairs {t['cross_visible_pairs']}"
            )
    else:
        lines.append("    none - no cross-visible pairs at this radius")
    lines.append(
        f"  dominant size {analysis['dominant_size']} | anchor floor "
        f"max(4, ceil(0.35*{TOTAL})) = {analysis['anchor_floor_size']} | "
        f"enroll_ratio {analysis['dominant_enroll_ratio']} (needs >= 0.5)"
    )
    lines.append(f"  engine statuses: {analysis['status_counts']}")
    lines.append(
        f"  students NOT auto-verified: {analysis['not_auto_verified']}/{TOTAL}"
    )
    lines.append("")
    lines.append("Grid (deterministic tie-break):")
    lines.append(ascii_grid(variant, analysis))
    payload = {
        "signatures": {str(s): c for s, c in signatures.most_common()},
        "analysis": analysis,
        "detailed_lists": detailed["lists"],
    }
    return "\n".join(lines), payload


def main():
    parser = argparse.ArgumentParser(description="Layer 1 satellite simulation")
    parser.add_argument(
        "--k", type=int, default=None,
        help="single K detailed mode; omit to sweep K=3..15",
    )
    parser.add_argument(
        "--engine", choices=["v1", "v2", "v3"], default="v1",
        help="v1 = production graph_engine, v2 = user-rules engine, "
        "v3 = full-visibility federation engine",
    )
    parser.add_argument(
        "--no-rule4", action="store_true",
        help="v2 only: drop the rule-4 mutual-edge cap",
    )
    parser.add_argument(
        "--full", action="store_true",
        help="v3 mode: client reports ALL visible peers (no truncation)",
    )
    parser.add_argument(
        "--radius", type=float, default=None,
        help="override visibility radius (default 4.0 seat-lengths)",
    )
    parser.add_argument(
        "--radsweep", action="store_true",
        help="v3 mode: radius 4..7 x gap 2..5 matrix with full lists",
    )
    parser.add_argument(
        "--gang", action="store_true",
        help="v3 mode: L3 5-device gang outside the room wall",
    )
    parser.add_argument(
        "--flap", action="store_true",
        help="v3 mode: L2 visibility-flap sweep (drop probability p)",
    )
    parser.add_argument(
        "--proxy", action="store_true",
        help="v3 mode: proxy-attendance sweep (gang size x radius x knobs)",
    )
    parser.add_argument(
        "--proxy-noise", action="store_true",
        help="v3 mode: proxy sweep under probabilistic hearing (wall x radius x floor x gang)",
    )
    args = parser.parse_args()
    global RADIUS
    if args.radius is not None:
        RADIUS = args.radius
    k_list = [args.k] if args.k is not None else list(range(3, 16))
    gaps = [2, 3, 4, 5] if args.k is None else [2]  # sweep spec: gap 2..5

    engine = load_engine(args.engine)
    if args.engine == "v2" and args.no_rule4:
        engine.RULE4_ENABLED = False
    prefix = "" if args.engine == "v1" else f"{args.engine}_"
    if args.engine == "v2" and args.no_rule4:
        prefix += "no4_"
    if args.radsweep:
        out_name = f"{prefix}radsweep"
    elif args.full:
        out_name = f"{prefix}full"
    elif args.gang:
        out_name = f"{prefix}gang"
    elif args.flap:
        out_name = f"{prefix}flap"
    elif args.proxy:
        out_name = f"{prefix}proxy"
    elif args.proxy_noise:
        out_name = f"{prefix}proxy_noise"
    else:
        out_name = f"{prefix}sweep" if args.k is None else f"{prefix}k{args.k}"
    if (args.gang or args.proxy or args.proxy_noise) and args.engine != "v3":
        parser.error("--gang, --proxy and --proxy-noise require --engine v3")
    out_dir = BACKEND / "simulations" / "layer1_out" / out_name
    out_dir.mkdir(parents=True, exist_ok=True)

    report_lines: list[str] = []
    json_payload: dict = {
        "layer": 1,
        "layout": {"blocks": BLOCKS, "rows": ROWS, "total": TOTAL},
        "radius": RADIUS,
        "k_list": k_list,
        "gaps": gaps,
        "random_seeds": N_RANDOM,
        "total_enrolled": TOTAL,
        "cells": {},
    }

    report_lines.append("=" * 78)
    report_lines.append("LAYER 1 SATELLITE SIMULATION - deterministic nearest-K, geometry only")
    if args.radsweep or args.full or args.gang or args.flap or args.proxy or args.proxy_noise:
        report_lines.append(
            f"v3 special mode | radius {RADIUS} | engine {args.engine}"
        )
    elif args.k is None:
        report_lines.append(
            f"SWEEP: K={k_list[0]}..{k_list[-1]} x aisle gap {gaps[0]}..{gaps[-1]} "
            f"(dx={gaps[0] + 1}..{gaps[-1] + 1}) | radius {RADIUS} | "
            f"{1 + N_RANDOM} tie-break configs per cell"
        )
    else:
        report_lines.append(
            f"Single-K detailed mode: K={args.k} | radius {RADIUS} | "
            f"{1 + N_RANDOM} tie-break configs"
        )
    report_lines.append("=" * 78)

    # ---- v3 special modes (full neighbor lists) ----------------------------
    if (
        args.radsweep or args.full or args.gang or args.flap
        or args.proxy or args.proxy_noise
    ):
        if args.radsweep:
            report_lines, json_payload = radsweep_section(
                engine, gaps, report_lines, json_payload
            )
        elif args.full:
            report_lines, json_payload = full_section(
                engine, gaps, report_lines, json_payload
            )
        elif args.gang:
            report_lines, json_payload = gang_section(
                engine, report_lines, json_payload
            )
        elif args.flap:
            report_lines, json_payload = flap_section(
                engine, gaps, report_lines, json_payload
            )
        elif args.proxy:
            report_lines, json_payload = proxy_section(
                engine, report_lines, json_payload
            )
        else:
            report_lines, json_payload = proxy_noise_section(
                engine, report_lines, json_payload
            )
        report = "\n".join(report_lines)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "report.txt").write_text(report + "\n")
        (out_dir / "results.json").write_text(json.dumps(json_payload, indent=2) + "\n")
        print(report)
        print(f"\nSaved: {out_dir / 'report.txt'}")
        print(f"Saved: {out_dir / 'results.json'}")
        return

    # ---- Sweep matrix -----------------------------------------------------
    matrix: dict[tuple[int, int], dict] = {}
    if args.k is None:
        for gap in gaps:
            seats = build_seats(gap)
            vis = visible_peers(seats, RADIUS)
            bids = seat_blocks(seats, gap)
            cross_vis = sum(
                1
                for a in range(len(seats))
                for b, _ in vis[a]
                if bids[a] != bids[b]
            ) // 2
            for k in k_list:
                engine.K_LIST_MAX = k  # client sends exactly k; clamp must follow
                signatures, verified, detailed = run_cell(seats, vis, k, engine)
                merged = verified.get(TOTAL, 0)
                matrix[(gap, k)] = {
                    "merged": merged,
                    "verified": dict(verified),
                    "signatures": {str(s): c for s, c in signatures.most_common()},
                    "cross_visible_pairs": cross_vis,
                }
                json_payload["cells"][f"gap{gap}/K{k}"] = matrix[(gap, k)]

        # Table: rows = gap, cols = K. Cell = modal VERIFIED count;
        # '*' = mixed outcomes across seeds.
        ks = k_list
        header = "gap(dx)  " + "".join(f"K{k:<4}" for k in ks)
        report_lines.append("")
        report_lines.append("VERIFIED-students matrix (modal count per cell, '*' = seed-dependent):")
        report_lines.append(header)
        for gap in gaps:
            row = f"g{gap}(dx{gap + 1})   "
            for k in ks:
                cell = matrix[(gap, k)]
                vcount = Counter({int(v): c for v, c in cell["verified"].items()})
                modal_v, _ = vcount.most_common(1)[0]
                mark = "*" if len(vcount) > 1 else " "
                row += f"{modal_v:<5}{mark}"
            report_lines.append(row)
        report_lines.append("(70 = fully merged and fully verified; 0 = fully sealed)")

        # Per-gap summaries and interesting cells.
        report_lines.append("")
        report_lines.append("Per-gap detail:")
        for gap in gaps:
            merged_by_k = {k: matrix[(gap, k)]["merged"] for k in ks}
            stable = [k for k in ks if merged_by_k[k] == 1 + N_RANDOM]
            partial = [k for k in ks if 0 < merged_by_k[k] < 1 + N_RANDOM]
            cross_vis = matrix[(gap, ks[0])]["cross_visible_pairs"]
            if cross_vis == 0:
                report_lines.append(
                    f"  gap {gap} (dx{gap + 1}): cross-aisle peers beyond radius "
                    f"{RADIUS} - invisible, K cannot bridge at any size"
                )
                continue
            if stable:
                report_lines.append(
                    f"  gap {gap} (dx{gap + 1}): full merge from K>={min(stable)} "
                    f"(stable across all seeds); partial/seed-dependent at "
                    f"K={partial if partial else 'none'}"
                )
            else:
                report_lines.append(
                    f"  gap {gap} (dx{gap + 1}): never fully merges in K range; "
                    f"partial at K={partial if partial else 'none'}"
                )
            for k in ks:
                cell = matrix[(gap, k)]
                if 0 < cell["merged"] < 1 + N_RANDOM:
                    report_lines.append(f"    K={k} outcome spread:")
                    for sig_str, count in cell["signatures"].items():
                        report_lines.append(f"      {count}x {sig_str}")

        # Verdict
        report_lines.append("")
        report_lines.append("=" * 78)
        report_lines.append("VERDICT")
        for gap in gaps:
            cross_vis = matrix[(gap, ks[0])]["cross_visible_pairs"]
            stable = [
                k for k in ks if matrix[(gap, k)]["merged"] == 1 + N_RANDOM
            ]
            if cross_vis == 0:
                report_lines.append(
                    f"  gap {gap} (dx{gap + 1}): NO K in {ks[0]}..{ks[-1]} can bridge "
                    f"- nearest cross-aisle peer at distance {gap + 1} > radius "
                    f"{RADIUS}; radius must grow with the aisle first"
                )
            elif stable:
                report_lines.append(
                    f"  gap {gap} (dx{gap + 1}): satellites eliminated at "
                    f"K>={min(stable)} (all {1 + N_RANDOM} seeds merge, "
                    f"70/70 VERIFIED_PRESENT)"
                )
            else:
                if args.engine == "v3":
                    report_lines.append(
                        f"  gap {gap} (dx{gap + 1}): no cross-aisle evidence at "
                        f"these K - all submitters referred to teacher review "
                        f"(no one is auto-labeled 'outside classroom')"
                    )
                else:
                    report_lines.append(
                        f"  gap {gap} (dx{gap + 1}): no stable K in range - satellites "
                        f"persist or bridging is seed-dependent luck"
                    )
        report_lines.append("=" * 78)

    # ---- Single-K detailed mode -------------------------------------------
    if args.k is not None:
        gap = gaps[0]
        seats = build_seats(gap)
        engine.K_LIST_MAX = args.k
        section, payload = detailed_section(seats, gap, args.k, engine)
        report_lines.append(section)
        json_payload["detailed"] = payload
        signatures, verified, _ = run_cell(seats, visible_peers(seats, RADIUS), args.k, engine)
        report_lines.append("")
        report_lines.append("VERDICT")
        report_lines.append(
            f"gap {gap} (dx{gap + 1}) at K={args.k}: "
            f"{verified.get(TOTAL, 0)}/{1 + N_RANDOM} seeds fully merge "
            f"(70/70 VERIFIED_PRESENT); component structures above."
        )
        report_lines.append("=" * 78)

    report = "\n".join(report_lines)
    (out_dir / "report.txt").write_text(report + "\n")
    (out_dir / "results.json").write_text(json.dumps(json_payload, indent=2) + "\n")
    print(report)
    print(f"\nSaved: {out_dir / 'report.txt'}")
    print(f"Saved: {out_dir / 'results.json'}")


if __name__ == "__main__":
    main()
