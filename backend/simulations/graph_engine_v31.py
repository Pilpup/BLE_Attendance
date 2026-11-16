"""Graph engine v3.1 - user amendments for the outside-gang problem.

Testing engine only (simulations/). Builds on v3 (full lists, bridges, room
federation, anchoring) with the user's amendments:

1. DIRECT threshold (user's final table):
       N = 1 -> T = 1
       N = 2 -> T = 2
       N = 3 -> T = 2
       N > 3 -> T = ceil(N / 2)   (50% of reported; halves round UP,
                                   matching the user's N=3 -> 2 example)
   Safe at T(1)=1 because of rule 4: the single mutual must be
   triangularly corroborated, so a ghost + one corrupt insider still
   fails (nobody else hears the ghost to complete the triangle).

2. BRIDGE: need = max(1, T(N) - |M|). The old "|M|=1 -> 3 bridges" special
   is dropped; it was calibrated to the cap-5 threshold table.

3. FEDERATION FLOOR (user's Case C defense): two verified components join
   the same room only when >= max(4, ceil(0.3 * |smaller|)) DISTINCT members
   of each component claim verified members of the other ("at least 4
   people from group A must hear group B and 4 people from group B must
   hear group A"). Distinct claimers, not distinct claims.

4. TRIANGLE CORROBORATION (completes the Case C defense): a mutual edge is
   verification evidence ONLY if its endpoints share a common mutual
   neighbor (the edge is in a triangle). Non-triangular mutual edges are
   demoted to one-directional claims: they still count as federation
   claimers and witness rescue, but never toward thresholds, bridges, or
   components. Without this, one rare lucky mutual edge across a wall
   merges an outside cluster into the class directly and the claim floors
   are never consulted.

5. OVERSUBSCRIPTION guard: when total_enrolled is known and more submitters
   than enrolled appear, the whole session goes to teacher review (fake
   -device flood).

6. ANCHORING (user decision, replaces ratio/face floors): the room
   containing the LARGEST verified component is the main class, always.
   No enrollment-ratio check - submissions are auth-bound to the session
   roster, so every submitter is an enrolled member of that class.
   No face gate - face proves identity, not location (a corridor gang
   face-verifies fine).

7. TWO-FLAG TAXONOMY (user decision, this revision): the engine emits
   exactly two statuses - VERIFIED and UNVERIFIED. Every non-verified
   outcome (isolated desk, below-threshold corroboration, unanchored
   cluster, witnessed non-submitter, oversubscribed session) collapses to
   UNVERIFIED so the teacher manually checks it. There is no
   "outside classroom" label anywhere: the engine never claims to know
   WHERE an unverified student was, only that the mesh could not verify
   them. verification_method carries the cause for the teacher's context;
   affinity_score is binary (1.00 verified, 0.00 unverified).

8. TWO-SUBMITTER SESSION (user decision: the two-phone honest test):
   with exactly two submitters, T(2)=2 is unreachable (no third member
   exists to corroborate a triangle), so the single mutual edge A<->B is
   accepted as sufficient evidence. Fires only when the WHOLE session is
   two submitters; a spoofed pair inside a larger session is unaffected.
   total_enrolled <= 0 is treated as unknown enrollment (oversubscription
   check skipped).

Not implemented (user standing rejections): rule 4 mutual-edge cap, per-B
sponsorship cap, enrollment-ratio anchoring, face-gated anchoring.

FINAL (locked rule set, validated):
  L1 gang sweep (simulate_v31.py, layer1_out/v31_*): class side 70/70
     VERIFIED across the wall grid; gang never verifies.
  L2 (simulate_l2.py, layer2_out): rank noise, flapping, seat shuffle -
     flat 70V clean cells; worst case is UNVERIFIED for teacher check,
     never an "outside classroom" verdict on an in-room seat.
  L3 adversarial suite (simulate_l3.py, layer3_out): buddy2 / cluster25 /
     split5 / gang_only / ghost1 - no verification leaks inside the
     accepted boundary; gang-only sessions stay soft.

Ingest hardening (this revision): reported lists are deduplicated
(order-preserving) and self-references dropped before any counting, so a
client echoing its own advert or double-listing a peer cannot inflate
mutual counts or thresholds. No-op for the simulation harnesses, which
already send clean lists; reruns of L1/L2/L3 are byte-identical.
"""

import math
from collections import deque

FULL_LIST_CAP = 64         # payload sanity cap on stored list length
SUBMIT_LIST_MIN = 0        # optional gate: submitters listing fewer peers
                           # than this are held for review. 0 = off.
FED_ABS_MIN = 4            # user: at least 4 claimers from each side
FED_FRAC = 0.30            # ...or 30% of the smaller component, whichever
                           # is larger


def _threshold(n: int) -> int:
    # User's final table: T(1)=1, T(2)=2, T(3)=2, T(N>3)=ceil(N/2).
    if n <= 1:
        return 1
    if n == 2 or n == 3:
        return 2
    return (n + 1) // 2


def _sanitize(raw_lists: dict[str, list[str]]) -> dict[str, list[str]]:
    """Deduplicate each reported list (order-preserving) and drop
    self-references before any counting. Without this, a client that
    double-lists a peer inflates its own mutual count past the threshold,
    and one that echoes its own advert gains a phantom self-mutual that
    trivially satisfies triangle corroboration."""
    out: dict[str, list[str]] = {}
    for u, claimed in raw_lists.items():
        seen: dict[str, None] = {}
        for peer in claimed:
            if peer != u:
                seen[peer] = None
        out[u] = list(seen)[:FULL_LIST_CAP]
    return out


def audit_classroom_mesh(
    reports: dict[str, list[str]],
    *,
    total_enrolled: int | None = None,
    # Accepted for call-site parity with the production engine; v3.1
    # deliberately does NOT gate anchoring on face evidence (user decision:
    # face proves identity, not location).
    face_verified_keys: set[str] | None = None,
) -> list[dict]:
    lists = _sanitize(reports)
    sets = {u: set(l) for u, l in lists.items()}

    # Raw mutual graph: edge iff each side listed the other.
    mut = {u: [v for v in lists[u] if v in lists and u in sets[v]] for u in lists}
    mutset = {u: set(peers) for u, peers in mut.items()}

    # Triangle corroboration: keep only mutual edges with a common mutual
    # neighbor. Demoted edges remain as claim material only.
    strong = {u: [v for v in mut[u] if mutset[u] & mutset[v]] for u in lists}
    m = {u: len(strong[u]) for u in lists}
    t_of = {u: _threshold(len(lists[u])) for u in lists}

    held = {u for u in lists if len(lists[u]) < SUBMIT_LIST_MIN}
    direct = {u for u in lists if u not in held and m[u] >= t_of[u]}

    # Bridge verification over strong edges only.
    bridge_verified: set[str] = set()
    bridge_info: dict[str, tuple[str, list[str]]] = {}
    for u in lists:
        if u in direct:
            continue
        mu = strong[u]
        if not mu:
            continue
        mu_set = set(mu)
        need = max(1, t_of[u] - len(mu))
        cs: set[str] = set()
        bridge_node: str | None = None
        for b in sorted(mu, key=lambda x: lists[u].index(x)):
            if b not in direct:
                continue  # B must be direct-verified; bridges cannot cascade
            if bridge_node is None:
                bridge_node = b
            for c in strong[b]:
                if c != u and c not in mu_set:
                    cs.add(c)
        if bridge_node is not None and len(cs) >= need:
            bridge_verified.add(u)
            bridge_info[u] = (bridge_node, sorted(cs)[:need])

    verified = direct | bridge_verified

    # Components over verified nodes (strong edges only).
    adjacency = {u: [v for v in strong[u] if v in verified] for u in verified}
    comps = _components(adjacency)
    comp_of = {u: i for i, comp in enumerate(comps) for u in comp}

    # Federation: union components when enough DISTINCT claimers exist in
    # both directions (user floor: max(4, 30% of the smaller component)).
    n = len(comps)
    parent = list(range(n))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    claimers: list[list[set[str]]] = [
        [set() for _ in range(n)] for _ in range(n)
    ]
    for u in verified:
        i = comp_of[u]
        for v in lists[u]:
            if v in verified and comp_of[v] != i:
                claimers[i][comp_of[v]].add(u)
    for i in range(n):
        for j in range(i + 1, n):
            req = max(
                FED_ABS_MIN,
                math.ceil(FED_FRAC * min(len(comps[i]), len(comps[j]))),
            )
            if len(claimers[i][j]) >= req and len(claimers[j][i]) >= req:
                ri, rj = find(i), find(j)
                if ri != rj:
                    parent[ri] = rj

    rooms: dict[int, set[str]] = {}
    for i in range(n):
        rooms.setdefault(find(i), set()).update(comps[i])

    # Rule 8: two-submitter session. The mutual edge must be raw (both
    # directions present); triangle corroboration is unreachable here by
    # construction, which is why the rule exists.
    pair_ids = sorted(lists)
    pair_direct = (
        len(pair_ids) == 2
        and pair_ids[1] in lists[pair_ids[0]]
        and pair_ids[0] in lists[pair_ids[1]]
    )

    # total_enrolled <= 0 means unknown enrollment (v1 parity): skip the
    # oversubscription hold instead of holding every session.
    oversubscribed = (
        total_enrolled is not None
        and total_enrolled > 0
        and len(reports) > total_enrolled
    )
    anchored: set[str] = set()
    if n and not oversubscribed:
        # Layer 8 (user decision): the room containing the largest verified
        # component IS the main class. No ratio floor, no face gate.
        # Tie-break: earliest-built component (smallest member id).
        core_comp = max(range(n), key=lambda i: (len(comps[i]), -i))
        anchored = rooms[find(core_comp)]

    results: list[dict] = []
    for u in sorted(lists):
        mu = strong[u]
        if oversubscribed:
            results.append(_row(
                u, "UNVERIFIED", "SESSION_OVERSUBSCRIBED", 0.00, None,
                lists[u], mu,
                f"Submission count ({len(reports)}) exceeds enrollment "
                f"({total_enrolled}) - Teacher check",
            ))
        elif pair_direct:
            partner = pair_ids[1] if u == pair_ids[0] else pair_ids[0]
            results.append(_row(
                u, "VERIFIED", "DIRECT_CONNECTION", 1.00, None,
                lists[u], mut[u],
                f"Two-submitter session: mutual edge with [{partner}] - "
                f"verified (two-phone test)",
            ))
        elif u in anchored:
            if u in direct:
                results.append(_row(
                    u, "VERIFIED", "DIRECT_CONNECTION", 1.00, None,
                    lists[u], mu,
                    f"Direct mutual with [{', '.join(mu)}] "
                    f"({m[u]}/{t_of[u]} threshold met)",
                ))
            else:
                b_node, cs = bridge_info[u]
                results.append(_row(
                    u, "VERIFIED", "BRIDGE_CONNECTION", 1.00, b_node,
                    lists[u], mu,
                    f"Verified via bridge path through [{b_node}] to "
                    f"[{', '.join(cs)}]",
                ))
        elif u in verified:
            results.append(_row(
                u, "UNVERIFIED", "MESH_UNANCHORED", 0.00, None, lists[u], mu,
                "Verified cluster could not be anchored to the main class "
                "- Teacher check",
            ))
        elif m[u] == 0:
            rescuers = [v for v in lists if v in verified and u in lists[v]]
            if len(rescuers) >= 3:
                results.append(_row(
                    u, "UNVERIFIED", "PEER_WITNESS", 0.00, None, lists[u], mu,
                    f"No corroborated mutuals but reported present by "
                    f"[{', '.join(rescuers)}] - likely radio failure - "
                    f"Teacher check",
                ))
            else:
                results.append(_row(
                    u, "UNVERIFIED", "NONE", 0.00, None, lists[u], mu,
                    "Submitted report with 0 corroborated mutuals - isolated "
                    "desk or outside spoofer - Teacher check",
                ))
        else:
            results.append(_row(
                u, "UNVERIFIED", "BELOW_THRESHOLD", 0.00, None, lists[u], mu,
                f"Corroboration below threshold ({m[u]} mutuals, "
                f"needs {t_of[u]}) and bridge paths insufficient - "
                f"Teacher check",
            ))

    # Offline witnessing; reporters must be verified.
    witness_map: dict[str, list[str]] = {}
    for u in lists:
        for peer in lists[u]:
            if peer not in lists:
                witness_map.setdefault(peer, []).append(u)
    for offline_id, witnesses in sorted(witness_map.items()):
        strong_witnesses = [w for w in witnesses if w in verified]
        if len(strong_witnesses) >= 3:
            results.append(_row(
                offline_id, "UNVERIFIED", "PEER_WITNESS", 0.00,
                strong_witnesses[0], [], witnesses,
                f"Reported seen by {len(strong_witnesses)} verified peers "
                f"but never submitted - Teacher check",
            ))

    return results


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
