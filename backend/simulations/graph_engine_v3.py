"""Graph engine v3.1 - full-visibility corroboration with room federation.

Testing engine only (simulations/), not wired into the app.

Mandate: zero internal satellites, ever. v3 established the design; v3.1
upgrades the threshold, bridge and federation rules to the user's spec and
adds a roster guard against off-roster submitters.

0. ROSTER GUARD. If enrolled_ids is provided, any submitter not on the
   roster is rejected outright (SPOOFER_REJECTED, affinity 0) and takes no
   part in the graph: its claims verify nothing. Enrolled-but-absent
   students spoofed from outside are not catchable by ID alone - radio
   range (client RSSI floor), oversubscription and the face layer are the
   levers there.

1. FULL LISTS. The client reports every visible peer, strongest first. The
   engine keeps ranks. No K parameter exists; nothing the radio heard is
   discarded. Rank noise is provably a non-event: mutual edges depend only
   on membership, and full lists have no cutoff to jitter across.

2. DIRECT verification: mutuals >= T(N), N = number of reported peers,
   T(N) = max(2, 2 * floor(N / 4)) - half of the reported neighbors,
   rounded down to an even number, floored at 2 so lists of N <= 3 can
   never auto-verify with zero mutuals. T self-adapts: a node at radio
   range edge loses peers, N shrinks, T shrinks with it.

       N :  1  2  3  4  5  6  7  8  9  10  11  12  13  14  15  16
       T :  2  2  2  2  2  2  2  4  4  4   4   6   6   6   6   8

3. BRIDGE verification: below threshold but >= 1 mutual; need =
   max(1, T(N) - |M|); B direct-verified; distinct C in M(B), C != u,
   C not in M(u); bridge-verified nodes cannot sponsor. No per-B cap
   (user rejected). The old "|M| = 1 -> 3 bridges" special is dropped -
   it was calibrated to the retired 60%-cap-5 table.

4. FEDERATION. Verified components A (large) and B (small, n = |B|) join
   into a room when DISTINCT cross-claims are >= max(2, ceil(0.3 * n)) in
   BOTH directions (>=, not exact equality - radio noise must not be able
   to sever a room). Mutual edges cannot exist across components, so
   cross-claims are genuine one-directional radio evidence; the
   both-direction requirement blocks a cluster that only claims to hear
   the class (the class does not claim it back).

5. ROOM ANCHORING (user rule 7 generalized). A room passes if
   size >= max(4, ceil(0.35 * total_enrolled)), size / total_enrolled >= 0.5,
   and (when face evidence exists) face-verified >= 50% of the room.
   Members of an anchored room: VERIFIED_PRESENT (DIRECT 1.00 / BRIDGE 0.50).

6. NO AUTO "OUTSIDE CLASSROOM" VERDICT, EVER. Verified components that fail
   anchoring -> NEEDS_REVIEW. A 0-mutual submitter listed by >= 3 verified
   peers -> NEEDS_REVIEW with positive evidence (witness rescue);
   otherwise ISOLATED_FLAGGED soft. Rule 5 witness: a non-submitter listed
   by >= 3 VERIFIED submitters -> FLAGGED_WITNESSED. If no room anchors,
   the whole class goes to review - the engine refuses to guess.

Not implemented (user standing rejections): rule 4 mutual-edge cap, per-B
sponsorship cap.
"""

import math
from collections import deque

FULL_LIST_CAP = 64         # payload sanity cap on stored list length
SUBMIT_LIST_MIN = 0        # optional gate: submitters listing fewer peers
                           # than this are held for review (never
                           # auto-verified). 0 = off.
ROOM_FLOOR_FRAC = 0.35
ROOM_RATIO_MIN = 0.5
FACE_RATIO_MIN = 0.5
FEDERATION_FRAC = 0.3      # 30% of the smaller component, both directions
FEDERATION_FLOOR = 2
T_OVERRIDE_PCT = None      # sweep knob: if set, T = max(2, pct*N//100)
                           # instead of the half-even rule (proxy sweep)


def _threshold(n: int) -> int:
    """Half of reported neighbors, rounded down to an even number, floor 2."""
    if T_OVERRIDE_PCT is not None:
        return max(2, (T_OVERRIDE_PCT * n) // 100)
    return max(2, 2 * (n // 4))


def audit_classroom_mesh(
    reports: dict[str, list[str]],
    *,
    total_enrolled: int | None = None,
    face_verified_keys: set[str] | None = None,
    enrolled_ids: set[str] | None = None,
) -> list[dict]:
    unknown: list[str] = []
    if enrolled_ids is not None:
        unknown = sorted(u for u in reports if u not in enrolled_ids)
    lists = {
        u: list(v)[:FULL_LIST_CAP]
        for u, v in reports.items()
        if u not in unknown
    }
    sets = {u: set(l) for u, l in lists.items()}

    # Mutual graph: edge iff each side listed the other.
    mutuals = {u: [v for v in lists[u] if v in lists and u in sets[v]] for u in lists}
    m = {u: len(mutuals[u]) for u in lists}
    n_of = {u: len(lists[u]) for u in lists}
    t_of = {u: _threshold(n_of[u]) for u in lists}

    held = {u for u in lists if len(lists[u]) < SUBMIT_LIST_MIN}
    direct = {u for u in lists if u not in held and m[u] >= t_of[u]}

    # Rule 3 bridges.
    bridge_verified: set[str] = set()
    bridge_info: dict[str, tuple[str, list[str]]] = {}
    for u in lists:
        if u in direct or u in held:
            continue
        mu = mutuals[u]
        if not mu:
            continue
        need = max(1, t_of[u] - len(mu))
        cs: set[str] = set()
        bridge_node: str | None = None
        for b in sorted(mu, key=lambda x: lists[u].index(x)):
            if b not in direct:
                continue  # B must be direct-verified; bridges cannot cascade
            if bridge_node is None:
                bridge_node = b
            for c in mutuals[b]:
                if c != u and c not in mu:
                    cs.add(c)
        if bridge_node is not None and len(cs) >= need:
            bridge_verified.add(u)
            bridge_info[u] = (bridge_node, sorted(cs)[:need])

    verified = direct | bridge_verified

    # Components over verified nodes (mutual edges only).
    adjacency = {u: [v for v in mutuals[u] if v in verified] for u in verified}
    comps = _components(adjacency)
    comp_of = {u: i for i, comp in enumerate(comps) for u in comp}

    # Federation: union components whose cross-claims clear the 30%-of-
    # smaller-component gate in both directions.
    n_comp = len(comps)
    parent = list(range(n_comp))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    cross = [[0] * n_comp for _ in range(n_comp)]
    for u in verified:
        i = comp_of[u]
        for v in lists[u]:
            if v in verified and comp_of[v] != i:
                cross[i][comp_of[v]] += 1
    for i in range(n_comp):
        for j in range(i + 1, n_comp):
            need = max(
                FEDERATION_FLOOR,
                math.ceil(FEDERATION_FRAC * min(len(comps[i]), len(comps[j]))),
            )
            if cross[i][j] >= need and cross[j][i] >= need:
                ri, rj = find(i), find(j)
                if ri != rj:
                    parent[ri] = rj

    rooms: dict[int, set[str]] = {}
    for i in range(n_comp):
        rooms.setdefault(find(i), set()).update(comps[i])
    anchored: set[str] = set()
    for room in rooms.values():
        if _room_anchored(room, total_enrolled, face_verified_keys):
            anchored |= room

    results: list[dict] = []
    for u in sorted(lists):
        mu = mutuals[u]
        if u in anchored:
            if u in direct:
                results.append(_row(
                    u, "VERIFIED_PRESENT", "DIRECT_CONNECTION", 1.00, None,
                    lists[u], mu,
                    f"Direct mutual with [{', '.join(mu)}] "
                    f"({m[u]}/{t_of[u]} threshold met)",
                ))
            else:
                b_node, cs = bridge_info[u]
                results.append(_row(
                    u, "VERIFIED_PRESENT", "BRIDGE_CONNECTION", 0.50, b_node,
                    lists[u], mu,
                    f"Verified via bridge path through [{b_node}] to "
                    f"[{', '.join(cs)}] (50% score met with mutuals + bridges)",
                ))
        elif u in verified:
            results.append(_row(
                u, "NEEDS_REVIEW", "NONE", 0.25, None, lists[u], mu,
                "Verified cluster could not be anchored to the main class "
                "- Teacher review",
            ))
        elif m[u] == 0:
            rescuers = [v for v in lists if v in verified and u in lists[v]]
            if len(rescuers) >= 3:
                results.append(_row(
                    u, "NEEDS_REVIEW", "NONE", 0.25, None, lists[u], mu,
                    f"0 mutuals but reported present by "
                    f"[{', '.join(rescuers)}] - likely radio failure - "
                    f"Teacher review",
                ))
            else:
                results.append(_row(
                    u, "ISOLATED_FLAGGED", "NONE", 0.00, None, lists[u], mu,
                    "Submitted report with 0 mutual reciprocations - "
                    "isolated desk or outside spoofer - Teacher review",
                ))
        else:
            results.append(_row(
                u, "NEEDS_REVIEW", "NONE", 0.25, None, lists[u], mu,
                f"Corroboration below 50% threshold ({m[u]} mutuals, "
                f"needs {t_of[u]}) and bridge paths insufficient - "
                f"Teacher review",
            ))

    # Rule 0: roster rejections.
    for u in unknown:
        results.append(_row(
            u, "SPOOFER_REJECTED", "NONE", 0.00, None, list(reports[u]), [],
            "Submitter not on this session's roster - Teacher review",
        ))

    # Rule 5: offline witnessing; reporters must be verified.
    emitted = set(lists) | set(unknown)
    witness_map: dict[str, list[str]] = {}
    for u in lists:
        for peer in lists[u]:
            if peer not in lists:
                witness_map.setdefault(peer, []).append(u)
    for offline_id, witnesses in sorted(witness_map.items()):
        if offline_id in emitted:
            continue
        strong = [w for w in witnesses if w in verified]
        if len(strong) >= 3:
            results.append(_row(
                offline_id, "FLAGGED_WITNESSED", "PEER_WITNESS", 0.00,
                strong[0], [], witnesses,
                f"Reported seen by {len(strong)} verified peers but never "
                f"submitted - Teacher review",
            ))

    return results


def _room_anchored(room: set[str], total_enrolled: int | None,
                   face_keys: set[str] | None) -> bool:
    size = len(room)
    if size < 4:
        return False
    if total_enrolled:
        if size < max(4, math.ceil(ROOM_FLOOR_FRAC * total_enrolled)):
            return False
        if size / total_enrolled < ROOM_RATIO_MIN:
            return False
    if face_keys:
        if sum(1 for u in room if u in face_keys) / size < FACE_RATIO_MIN:
            return False
    return True


def _components(adjacency: dict[str, list[str]]) -> list[set[str]]:
    seen: set[str] = set()
    out: list[set[str]] = []
    for start in sorted(adjacency):
        if start in seen:
            continue
        comp: set[str] = set()
        queue = deque([start])
        seen.add(start)
        while queue:
            cur = queue.popleft()
            comp.add(cur)
            for nbr in adjacency[cur]:
                if nbr not in seen:
                    seen.add(nbr)
                    queue.append(nbr)
        out.append(comp)
    return out


def _row(device_id, status, method, affinity, bridge, claimed, mutuals, remarks):
    return {
        "ble_device_id": device_id,
        "status": status,
        "verification_method": method,
        "affinity_score": affinity,
        "bridge_device_id": bridge,
        "reported_neighbors": list(claimed),
        "mutuals": list(mutuals),
        "remarks": remarks,
    }
