"""Graph engine v2 - the user's restated rules 1-7, testing engine only.

NOT wired into the app. Lives in simulations/ for the satellite test harness.

Faithful implementation of the user's rule list:

1. Clients report up to 12 neighbors, strongest-RSSI first; the engine decides
   connections. Threshold math uses k = min(len(list), 5) - the user's note:
   "number of neighbors reported" means 5 when more than 5 were reported.
2. Direct verification: mutuals >= round(0.6 * k).
   k=1..5 -> T = 1, 1, 2, 2, 3.
3. Bridge verification for nodes below threshold. Bridge A-B-C: B in M(A),
   B direct-verified, C in M(B), C != A, C not in M(A); each distinct C is
   one bridge connection. Needed bridges (user's examples):
       |M|=1, T=3 -> 3 bridges;  |M|=1, T=2 -> 1 bridge;  |M|=2, T=3 -> 1.
   Requires |M| >= 1. Bridge-verified nodes can never act as B (cascade ban).
4. Mutual-edge cap: candidate mutual edges finalized greedily strongest-first
   (edge strength = min of the two list ranks). An edge is dropped iff BOTH
   endpoints have already met their 60% threshold. Saturated endpoints may
   still bond with peers below threshold (the user's 4/5 example).
5. Offline student: ID present in >=3 submitters' lists but never submitted
   -> FLAGGED_WITNESSED (teacher review). <3 -> ignored.
6. Submitter with 0 finalized mutuals -> ISOLATED_FLAGGED (soft, review).
7. Largest connected component over verified (direct + bridge) nodes = main
   class -> VERIFIED_PRESENT. Other verified components = satellites
   -> ISOLATED_FLAGGED. (This, not rule 4, separates class from spoofer group.)

Not included (user's v2 rules omit them): H3 enrollment anchoring, witness
restriction to verified reporters, hard SPOOFER_REJECTED verdicts, per-B
bridge sponsorship caps.
"""

from collections import deque

K_LIST_MAX = 12
K_THRESHOLD_CAP = 5

# Rule 4 switch (mutual-edge cap). Set False to drop rule 4 - anti-satellite
# work then rests entirely on rule 7 (dominant component), per simulation
# evidence that the cap re-seals aisle-split classrooms at every list size.
RULE4_ENABLED = True


def _threshold(k: int) -> int:
    # round(0.6*k) in exact integer math: floor(3k/5 + 0.5)
    return (6 * k + 5) // 10


def audit_classroom_mesh(
    reports: dict[str, list[str]],
    *,
    total_enrolled: int | None = None,   # accepted for interface parity; unused
    face_verified_keys: set[str] | None = None,  # unused in v2 rules
) -> list[dict]:
    lists = {u: list(v)[:K_LIST_MAX] for u, v in reports.items()}
    sets = {u: set(l) for u, l in lists.items()}
    k_of = {u: min(len(l), K_THRESHOLD_CAP) for u, l in lists.items()}
    t_of = {u: _threshold(k_of[u]) for u in lists}

    # Rule 4: candidate mutual edges, strongest-first greedy finalization.
    candidates: list[tuple[int, str, str]] = []
    seen_pairs: set[tuple[str, str]] = set()
    for u in lists:
        for v in sets[u]:
            if v not in lists or u not in sets[v]:
                continue  # peer never submitted -> no mutual possible
            key = (u, v) if u < v else (v, u)
            if key in seen_pairs:
                continue
            seen_pairs.add(key)
            strength = min(lists[u].index(v), lists[v].index(u))
            candidates.append((strength, key[0], key[1]))
    candidates.sort()

    mutuals: dict[str, list[str]] = {u: [] for u in lists}
    m: dict[str, int] = {u: 0 for u in lists}
    for _strength, u, v in candidates:
        if (
            RULE4_ENABLED
            and m[u] >= t_of[u]
            and m[v] >= t_of[v]
        ):
            continue  # both already met 60% - edge stays open for bridging
        mutuals[u].append(v)
        mutuals[v].append(u)
        m[u] += 1
        m[v] += 1

    # Rule 2: direct verification.
    direct = {u for u in lists if m[u] >= t_of[u]}

    # Rule 3: bridge verification.
    bridge_verified: set[str] = set()
    bridge_info: dict[str, tuple[str, list[str]]] = {}
    for u in lists:
        if u in direct:
            continue
        mu = mutuals[u]
        if not mu:
            continue  # rule 3 requires >= 1 mutual
        t = t_of[u]
        need = 3 if (len(mu) == 1 and t >= 3) else max(1, t - len(mu))
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

    # Rule 7: dominant component over verified nodes.
    verified = direct | bridge_verified
    adjacency = {u: [v for v in mutuals[u] if v in verified] for u in verified}
    visited: set[str] = set()
    components: list[set[str]] = []
    for start in sorted(verified):
        if start in visited:
            continue
        comp: set[str] = set()
        queue = deque([start])
        visited.add(start)
        while queue:
            cur = queue.popleft()
            comp.add(cur)
            for nbr in adjacency[cur]:
                if nbr not in visited:
                    visited.add(nbr)
                    queue.append(nbr)
        components.append(comp)
    components.sort(key=lambda c: (-len(c), min(c)))
    core = components[0] if components else set()

    # Statuses.
    results: list[dict] = []
    for u in lists:
        mu = mutuals[u]
        if u in core:
            if u in bridge_verified:
                b_node, cs = bridge_info[u]
                results.append(_row(
                    u, "VERIFIED_PRESENT", "BRIDGE_CONNECTION", 0.50, b_node,
                    lists[u], mu,
                    f"Verified via bridge path through [{b_node}] to "
                    f"[{', '.join(cs)}] (60% score met with mutuals + bridges)",
                ))
            else:
                results.append(_row(
                    u, "VERIFIED_PRESENT", "DIRECT_CONNECTION", 1.00, None,
                    lists[u], mu,
                    f"Direct mutual with [{', '.join(mu)}] "
                    f"({m[u]}/{t_of[u]} threshold met)",
                ))
        elif u in verified:
            results.append(_row(
                u, "ISOLATED_FLAGGED", "NONE", 0.00, None, lists[u], mu,
                "Verified cluster outside main classroom group (satellite) "
                "- Teacher review",
            ))
        elif m[u] == 0:
            results.append(_row(
                u, "ISOLATED_FLAGGED", "NONE", 0.00, None, lists[u], mu,
                "Submitted report with 0 mutual reciprocations - isolated "
                "desk or outside spoofer - Teacher review",
            ))
        else:
            results.append(_row(
                u, "NEEDS_REVIEW", "NONE", 0.25, None, lists[u], mu,
                f"Corroboration below 60% threshold ({m[u]} mutuals, "
                f"needs {t_of[u]}) - Teacher review",
            ))

    # Rule 5: offline peer witnessing (any submitter counts).
    witness_map: dict[str, list[str]] = {}
    for u in lists:
        for n in lists[u]:
            if n not in lists:
                witness_map.setdefault(n, []).append(u)
    for offline_id, witnesses in witness_map.items():
        if len(witnesses) >= 3:
            results.append(_row(
                offline_id, "FLAGGED_WITNESSED", "PEER_WITNESS", 0.00,
                witnesses[0], [], witnesses,
                f"Reported seen by {len(witnesses)} peers but never submitted "
                f"- Teacher review",
            ))

    return results


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
