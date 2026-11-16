#!/usr/bin/env python3
"""Twin-fingerprint probe (v3): graph detection of a carried (proxy) phone.

Scenario: student X is absent; X's phone is carried by classmate Y. All 70
accounts submit, radio-normal, so verification passes (proven in L1-L3).
The graph-native tell: the carried phone sits d0 seat-lengths from the
carrier's phone, so both devices' radius disks differ only by a thin shell.

Three per-pair detectors, decided per session, confirmed by majority vote
across K co-sessions (per-pair session FPR ~1% by threshold calibration):
  D1 set-overlap   jac(u,v) = |A&B| / |A|B| over heard sets.
                   twin 0.75 (d0<=0.5) / 0.70 (d0=0.7) vs honest
                   seatmates 0.59 +- 0.06 (q99 0.71). Jitter-immune.
  D2 relative      the pair is the clean top-overlap pair of the class
                   (threshold-free version of D1).
  D3 list-length   |len(A) - len(B)| - catches a BAG-carried phone: its
                   attenuated radio hears ~30% fewer peers than the
                   device right next to it. Honest seatmates have
                   near-equal lengths.

Baseline = honest seatmate pairs (<=1.5 seat-lengths apart, the hardest
impostors). Grid: flapping drop {0, 0.1, 0.3}, carry offset
d0 {0.15, 0.5, 0.7} (lazy same-pocket vs smart other-pocket), carry style
att {1.0 = hand/pocket, 0.7 = bag}, seating {fixed, shuffle}, K {1, 5}.

Rank-order twin metric (first probe): FAILED - the 0.15 shift field
crosses the grid's near-degenerate distance rings, so rank diffs match
honest seatmates. Documented in earlier layer4_out report versions.
"""

import json
import math
import random
from pathlib import Path

import simulate_satellites as base

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "layer4_out"
SEEDS = 200
SEED_BASE = 11000
RADIUS = base.RADIUS
CARRIER = 31        # mid-room seat (5,4) at gap 2 (fixed mode)
CARRIED = 42        # absent student's account; device rides with CARRIER
DROPS = (0.0, 0.1, 0.3)
OFFSETS = (0.15, 0.5, 0.7)
KS = (1, 5)
ATTS = (1.0, 0.7)
MIN_SET = 6
MIN_COMMON = 6
HONEST_SAMPLE = 40


def build_session(rng, drop, d0, mode, att):
    """One session's heard sets. Returns (sets, adjacent_honest_pairs).

    sets[i] = frozenset of account indices device i heard. Membership:
    true distance <= RADIUS; each DIRECTION present independently w.p.
    (1-drop), and w.p. (1-drop)*att when the link touches the carried
    device (bag attenuation weakens its TX and RX).
    """
    seats = base.build_seats(2)
    if mode == "shuffle":
        order = rng.sample(range(70), 70)
        pos = {i: seats[order[i]] for i in range(70)}
    else:
        pos = {i: seats[i] for i in range(70)}
    pos[CARRIED] = (pos[CARRIER][0] + d0, pos[CARRIER][1])

    sets = {}
    for i in range(70):
        heard = []
        for j in range(70):
            if i == j:
                continue
            d = math.hypot(pos[i][0] - pos[j][0], pos[i][1] - pos[j][1])
            if d > RADIUS + 1e-9:
                continue
            p_present = 1.0 - drop
            if i == CARRIED or j == CARRIED:
                p_present *= att
            if rng.random() < p_present:
                heard.append(j)
        sets[i] = frozenset(heard)

    adj = []
    for i in range(70):
        for j in range(i + 1, 70):
            if CARRIED in (i, j):
                continue
            d = math.hypot(pos[i][0] - pos[j][0], pos[i][1] - pos[j][1])
            if d <= 1.5:
                adj.append((i, j))
    return sets, adj


def jac(a, b):
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter) if inter else 0.0


def class_argmax(sets):
    best, best_v, second = None, -1.0, -1.0
    for i in range(70):
        a = sets[i]
        if len(a) < MIN_SET:
            continue
        for j in range(i + 1, 70):
            b = sets[j]
            if len(b) < MIN_SET:
                continue
            inter = len(a & b)
            if inter < MIN_COMMON:
                continue
            v = inter / (len(a) + len(b) - inter)
            if v > best_v:
                second = best_v
                best_v, best = v, (i, j)
            elif v > second:
                second = v
    return best, best_v, second


def q99(vals):
    v = sorted(vals)
    return v[min(len(v) - 1, int(math.ceil(0.99 * len(v))) - 1)]


def run_cell(mode, d0, drop, k_sessions, att):
    """Per-pair detectors with majority vote over K co-sessions."""
    twin_by_seed, twin_def_by_seed = [], []
    honest_raw, def_raw, twin_raw = [], [], []
    arg_by_seed = []
    for s in range(SEEDS):
        twin_sess, twin_def_sess, arg_hits = [], [], 0
        for k in range(k_sessions):
            rng = random.Random(SEED_BASE + s * 97 + k)
            sets, adj = build_session(rng, drop, d0, mode, att)
            tj = jac(sets[CARRIER], sets[CARRIED])
            twin_sess.append(tj)
            twin_raw.append(tj)
            twin_def_sess.append(
                abs(len(sets[CARRIER]) - len(sets[CARRIED]))
            )
            best, best_v, second = class_argmax(sets)
            if best is not None and CARRIED in best and best_v - second >= 0.01:
                arg_hits += 1
            rng.shuffle(adj)
            sample = adj[:HONEST_SAMPLE]
            honest_raw.extend(jac(sets[i], sets[j]) for i, j in sample)
            def_raw.extend(
                abs(len(sets[i]) - len(sets[j])) for i, j in sample
            )
        twin_by_seed.append(twin_sess)
        twin_def_by_seed.append(twin_def_sess)
        arg_by_seed.append(arg_hits)

    thr_j = q99(honest_raw) + 0.01
    fpr_j = sum(1 for h in honest_raw if h > thr_j) / len(honest_raw)
    thr_d = q99(def_raw) + 1
    fpr_d = sum(1 for d in def_raw if d > thr_d) / len(def_raw)
    need = math.ceil(k_sessions / 2)

    d1 = sum(
        1 for ts in twin_by_seed
        if sum(1 for t in ts if t > thr_j) >= need
    ) / SEEDS
    d2 = sum(1 for a in arg_by_seed if a >= need) / SEEDS
    d3 = sum(
        1 for ds in twin_def_by_seed
        if sum(1 for d in ds if d > thr_d) >= need
    ) / SEEDS

    return {
        "d1": round(d1, 3), "d2": round(d2, 3), "d3": round(d3, 3),
        "thr_j": round(thr_j, 3), "fpr_j": round(fpr_j, 4),
        "thr_d": thr_d, "fpr_d": round(fpr_d, 4),
        "twin_jac_mean": round(sum(twin_raw) / len(twin_raw), 3),
        "honest_q99": round(q99(honest_raw), 3),
        "honest_max": round(max(honest_raw), 3),
        "honest_def_q99": q99(def_raw),
    }


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = [
        "=" * 78,
        "LAYER 4 - TWIN-FINGERPRINT PROBE (v3): carried-phone detection",
        "D1 set-overlap jac | D2 clean-argmax | D3 list-length deficit",
        "per-pair session FPR ~1% (threshold = honest seatmate q99), "
        "majority vote over K co-sessions",
        "=" * 78,
    ]
    payload = {
        "seeds": SEEDS, "radius": RADIUS, "carrier": CARRIER,
        "carried": CARRIED,
    }
    for att in ATTS:
        att_note = ("hand/pocket (full radio)" if att == 1.0
                    else "BAG (carried links at 70%)")
        for mode in ("fixed", "shuffle"):
            lines.append("")
            lines.append(
                f"CARRY = {att_note} | SEATING = {mode}  "
                + ("(assigned seats)" if mode == "fixed"
                   else "(random re-seating per session)")
            )
            for k in KS:
                lines.append("")
                lines.append(
                    f"--- K = {k} co-sessions (majority vote) | "
                    f"cell = D1%/D2%/D3% ---"
                )
                lines.append("d0        " + "".join(
                    f"drop={p:<10}" for p in DROPS))
                for d0 in OFFSETS:
                    row = f"d0={d0:<4}  "
                    cells = {}
                    for dp in DROPS:
                        st = run_cell(mode, d0, dp, k, att)
                        cells[f"drop{dp}"] = st
                        row += (
                            f"{st['d1']*100:>3.0f}/{st['d2']*100:<3.0f}"
                            f"/{st['d3']*100:<3.0f}   "
                        )
                    payload[f"att{att}/{mode}/K{k}/d0{d0}"] = cells
                    lines.append(row)
            st0 = payload[f"att{att}/{mode}/K1/d0{OFFSETS[0]}"]["drop0.0"]
            lines.append("")
            lines.append(
                f"honest seatmates: jac q99 {st0['honest_q99']} / max "
                f"{st0['honest_max']}, length-deficit q99 "
                f"{st0['honest_def_q99']} | twin jac "
                f"{st0['twin_jac_mean']} (d0=0.15)"
            )

    report = "\n".join(lines)
    (OUT_DIR / "report.txt").write_text(report + "\n")
    (OUT_DIR / "results.json").write_text(json.dumps(payload, indent=2) + "\n")
    print(report)
    print(f"\nSaved: {OUT_DIR / 'report.txt'}")
    print(f"Saved: {OUT_DIR / 'results.json'}")


if __name__ == "__main__":
    main()
