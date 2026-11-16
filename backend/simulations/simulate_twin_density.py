#!/usr/bin/env python3
"""Twin-detector density probe (L4b): does sparse attendance saturate
heard-set overlap, and does the 'present > ~30 + 100% overlap' rule work?

User proposal under test:
  1. run the twin detector only when present > ~30
  2. flag pairs with 100% neighbor overlap
  3. flag the device with the lower attendance history as the proxy
     (mark unverified for teacher check)

Questions:
  Q1 saturation - at which present-count does the honest seatmate overlap
     distribution reach twin level (0.75+)? uniform vs front-filled
     (L2 model: front 35 seats fill first).
  Q2 rule100 - P(twin jac == 1.0) vs P(honest jac == 1.0): is exact-100%
     a usable tell, and what does flapping (drop > 0) do to it?
  Q3 gate - heard-set-size gate (>= 6 peers both sides) vs present>30:
     which matches the actual blind zone?
  Q4 attribution - P('less attendance history' picks the carried device).

Metric: Jaccard over heard sets (L4 D1). Geometry/noise identical to
simulate_twin.py v3: radius 4.0, directed flapping dropout, hand-carry
(att = 1.0; bag carry already shown to invert the signal in L4).

Known from L4 (fixed seats, 70 present, drop = 0): twin jac is
DETERMINISTIC 0.750 (+-0.000) - so exact-1.0 never fires in a full clean
class. This probe measures where it does fire, and at what cost.
"""

import json
import math
import random
import statistics
from pathlib import Path

import simulate_satellites as base

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "layer4_out"

SEEDS = 200
SEED_BASE = 12000
RADIUS = base.RADIUS
N_ACCOUNTS = 70
FRONT = 35
ANCHOR = 31            # seat nearest room centre at gap 2 (L4 carrier seat)
PRESENT_SWEEP = (8, 12, 16, 20, 25, 30, 35, 42, 50, 60, 70)
DROPS = (0.0, 0.1)
OFFSETS = (0.15, 0.5)
MODELS = ("uniform", "frontfill")
MIN_SET = 6            # detector input gate: both heard sets >= 6 peers
MIN_COMMON = 6         # ...and >= 6 shared peers
HONEST_CAP = 200       # honest pairs sampled per session
HIST_SESSIONS = 20     # attendance-history length for attribution probe

SEATS = base.build_seats(2)
DIST = [[0.0 if i == j else math.hypot(SEATS[i][0] - SEATS[j][0],
                                       SEATS[i][1] - SEATS[j][1])
         for j in range(N_ACCOUNTS)] for i in range(N_ACCOUNTS)]
RAD = [[j for j in range(N_ACCOUNTS) if j != i and DIST[i][j] <= RADIUS + 1e-9]
       for i in range(N_ACCOUNTS)]
NEAR = [[j for j in range(N_ACCOUNTS) if j != i and DIST[i][j] <= 1.5 + 1e-9]
        for i in range(N_ACCOUNTS)]


def jac(a, b):
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter) if inter else 0.0


def gate(a, b):
    return (len(a) >= MIN_SET and len(b) >= MIN_SET
            and len(a & b) >= MIN_COMMON)


def q99(vals):
    v = sorted(vals)
    return v[min(len(v) - 1, int(math.ceil(0.99 * len(v))) - 1)]


def choose_present(rng, present, model):
    """Accounts whose devices are in the room. uniform = random subset;
    frontfill = L2 model: front 35 seats fill first, rest random."""
    if model == "uniform":
        return set(rng.sample(range(N_ACCOUNTS), present))
    front = list(range(FRONT))
    back = list(range(FRONT, N_ACCOUNTS))
    rng.shuffle(front)
    rng.shuffle(back)
    kf = min(FRONT, present)
    return set(front[:kf] + back[:present - kf])


def build_session(rng, present, model, drop, d0):
    """One session: `present` devices in the room; one of them (carrier)
    also carries the absent student's phone at +d0 seat-lengths.

    sets[i] = frozenset of accounts device i heard. Membership: true
    distance <= RADIUS, each DIRECTION present independently w.p. 1-drop.
    The carried device sits OFF its assigned seat (at carrier + d0), so
    neighbors hear it from its true position, not its seat's.
    """
    chosen = choose_present(rng, present, model)
    carrier = min(chosen, key=lambda i: DIST[i][ANCHOR])
    carried = rng.choice(sorted(i for i in chosen if i != carrier))
    cx, cy = SEATS[carrier]
    px, py = cx + d0, cy

    sets = {}
    for i in chosen:
        if i == carried:
            pool = [j for j in chosen if j != i
                    and math.hypot(SEATS[j][0] - px, SEATS[j][1] - py)
                    <= RADIUS + 1e-9]
        else:
            pool = [j for j in RAD[i] if j in chosen and j != carried]
            if math.hypot(SEATS[i][0] - px, SEATS[i][1] - py) <= RADIUS + 1e-9:
                pool.append(carried)
        sets[i] = frozenset(j for j in pool if rng.random() >= drop)

    adj = [(i, j) for i in chosen if i != carried
           for j in NEAR[i] if j > i and j in chosen and j != carried]
    return chosen, carrier, carried, sets, adj


def run_cell(model, present, drop, d0):
    honest_all, honest_elig, sizes = [], [], []
    twin_raw, twin_ok = [], []
    arg_tpr = arg_fpr = 0
    for s in range(SEEDS):
        rng = random.Random(SEED_BASE + s * 97)
        chosen, carrier, carried, sets, adj = build_session(
            rng, present, model, drop, d0)
        sizes.extend(len(sets[i]) for i in chosen if i != carried)
        tj = jac(sets[carrier], sets[carried])
        twin_raw.append(tj)
        twin_ok.append(gate(sets[carrier], sets[carried]))
        rng.shuffle(adj)
        seed_honest = []
        for i, j in adj[:HONEST_CAP]:
            v = jac(sets[i], sets[j])
            honest_all.append(v)
            seed_honest.append(v)
            if gate(sets[i], sets[j]):
                honest_elig.append(v)
        # argmax-with-margin: flag only the session's top-overlap pair when
        # it beats the runner-up by >= 0.01. Class-level false alarms are
        # capped at <= 1 pair per session by construction.
        if len(seed_honest) >= 5:
            sess = seed_honest + [tj]
            sess.sort(reverse=True)
            if sess[0] - sess[1] >= 0.01:
                if sess[0] == tj and twin_ok[-1]:
                    arg_tpr += 1
                elif sess[0] != tj:
                    arg_fpr += 1

    n = len(honest_all)
    honest = {
        "med_set": round(statistics.median(sizes), 1) if sizes else 0.0,
        "gate_pass": round(len(honest_elig) / n, 3) if n else 0.0,
        "p100_raw": round(sum(1 for v in honest_all if v == 1.0) / n, 3)
        if n else 0.0,
        "p100_gated": round(sum(1 for v in honest_elig if v == 1.0)
                            / len(honest_elig), 3) if honest_elig else 0.0,
        "q99": round(q99(honest_elig), 3) if len(honest_elig) >= 100 else None,
        "max": round(max(honest_elig), 3) if len(honest_elig) >= 100 else None,
    }
    if honest["q99"] is not None:
        thr = honest["q99"] + 0.01
        d1_tpr = round(sum(1 for t, ok in zip(twin_raw, twin_ok)
                           if ok and t > thr) / SEEDS, 3)
        r100_tpr = round(sum(1 for t, ok in zip(twin_raw, twin_ok)
                             if ok and t == 1.0) / SEEDS, 3)
        r100_fpr = honest["p100_gated"]
    else:
        d1_tpr = r100_tpr = None
        r100_fpr = 0.0
    twin = {
        "jac_mean": round(sum(twin_raw) / SEEDS, 3),
        "p100": round(sum(1 for t in twin_raw if t == 1.0) / SEEDS, 3),
        "gate_pass": round(sum(twin_ok) / SEEDS, 3),
        "d1_tpr": d1_tpr,
        "r100_tpr": r100_tpr,
        "r100_fpr": r100_fpr,
        "arg_tpr": round(arg_tpr / SEEDS, 3),
        "arg_fpr": round(arg_fpr / SEEDS, 3),
    }
    return {"honest": honest, "twin": twin}


def attribution_mc(trials=20000, hist=HIST_SESSIONS):
    """Q4: two devices flagged as a twin pair - does 'fewer past
    attendances' pick the carried (proxy) phone?

    carrier = present student, attendance rate U(0.6, 1.0).
    carried = absent student whose phone rides along; two profiles:
      one-time skipper  - rate U(0.6, 1.0) like everyone
      habitual skipper  - rate U(0.3, 0.7)
    History = Binomial(hist, rate) attended of last `hist` sessions.
    """
    rng = random.Random(99001)
    out = []
    profiles = (
        ("one-time skipper (both rates U(0.6,1.0))", (0.6, 1.0)),
        ("habitual skipper (proxy rate U(0.3,0.7))", (0.3, 0.7)),
    )
    for label, proxy_range in profiles:
        correct = wrong = tie = 0
        for _ in range(trials):
            rc = rng.uniform(0.6, 1.0)
            rx = rng.uniform(*proxy_range)
            ac = sum(1 for _ in range(hist) if rng.random() < rc)
            ax = sum(1 for _ in range(hist) if rng.random() < rx)
            if ax < ac:
                correct += 1
            elif ax > ac:
                wrong += 1
            else:
                tie += 1
        out.append((label, correct / trials, wrong / trials, tie / trials))
    return out


def pct(x):
    return f"{x * 100:>3.0f}%" if x is not None else " off"


def pc1(x):
    return f"{x * 100:>4.1f}%"


def fmt2(x):
    return f"{x:.2f}" if x is not None else "  - "


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = [
        "=" * 78,
        "LAYER 4b - TWIN-DETECTOR DENSITY PROBE",
        "user proposal: present > ~30 gate | flag 100% heard-set overlap |",
        "attribute via lower attendance history",
        "Q1 honest saturation vs present count   Q2 exact-100 vs adaptive D1",
        "Q3 set-size gate vs count gate          Q4 attribution accuracy",
        "=" * 78,
    ]
    payload = {"seeds": SEEDS, "radius": RADIUS, "front": FRONT,
               "min_set": MIN_SET, "min_common": MIN_COMMON,
               "present_sweep": PRESENT_SWEEP}

    for model in MODELS:
        for drop in DROPS:
            per_present = {}
            for present in PRESENT_SWEEP:
                a = run_cell(model, present, drop, OFFSETS[0])
                b = run_cell(model, present, drop, OFFSETS[1])
                per_present[present] = (a, b)

            lines.append("")
            lines.append(f"SEAT MODEL = {model} | FLAP drop = {drop}")
            lines.append("honest seatmate baseline (pairs <= 1.5 apart):")
            lines.append(
                "pres  med_set  gate-pass  q99(elig)  max(elig)  "
                "P(jac=1.0) raw/gated")
            for present in PRESENT_SWEEP:
                h = per_present[present][0]["honest"]
                lines.append(
                    f"{present:>4}  {h['med_set']:>6.1f}  "
                    f"{pct(h['gate_pass']):>9}  {fmt2(h['q99']):>9}  "
                    f"{fmt2(h['max']):>9}  "
                    f"{pc1(h['p100_raw'])}/{pc1(h['p100_gated'])}"
                )
            for di, d0 in enumerate(OFFSETS):
                lines.append("")
                lines.append(f"twin d0 = {d0} (drop = {drop}):")
                lines.append(
                    "pres  twin_jac  P(twin=1.0)  twin-gate  "
                    "D1 TPR  R100 TPR  ARG TPR  ARG FPR")
                for present in PRESENT_SWEEP:
                    t = per_present[present][di]["twin"]
                    lines.append(
                        f"{present:>4}  {fmt2(t['jac_mean']):>8}  "
                        f"{pc1(t['p100']):>11}  {pct(t['gate_pass']):>9}  "
                        f"{pct(t['d1_tpr']):>6}  {pct(t['r100_tpr']):>9}  "
                        f"{pct(t['arg_tpr']):>8}  {pc1(t['arg_fpr']):>8}"
                    )
            payload.setdefault(model, {})[f"drop{drop}"] = {
                str(present): {
                    "honest": per_present[present][0]["honest"],
                    "twin": {str(OFFSETS[di]):
                             per_present[present][di]["twin"]
                             for di in range(len(OFFSETS))},
                }
                for present in PRESENT_SWEEP
            }

    lines.append("")
    lines.append("Q4 ATTRIBUTION - 'flag the device with less attendance' "
                 f"(history = {HIST_SESSIONS} sessions):")
    lines.append("profile                                     correct   "
                 "wrong(honest carrier blamed)   tie")
    for label, c, w, t in attribution_mc():
        lines.append(f"{label:<42} {c * 100:>5.1f}%   {w * 100:>7.1f}%"
                     f"                        {t * 100:>5.1f}%")

    report = "\n".join(lines)
    (OUT_DIR / "density_report.txt").write_text(report + "\n")
    (OUT_DIR / "density_results.json").write_text(
        json.dumps(payload, indent=2) + "\n")
    print(report)
    print(f"\nSaved: {OUT_DIR / 'density_report.txt'}")
    print(f"Saved: {OUT_DIR / 'density_results.json'}")


if __name__ == "__main__":
    main()
