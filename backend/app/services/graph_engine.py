from typing import Dict, List, Optional


# social mesh graph topology engine
def audit_classroom_mesh(reports: Dict[str, List[str]]) -> List[dict]:
    direct_mutuals: Dict[str, List[str]] = {}
    bridge_map: Dict[str, Optional[str]] = {}
    verified_set: set[str] = set()
    results: List[dict] = []

    # Step 1: Direct mutual pairs (A in P(B) and B in P(A)) -> Affinity 1.00
    for student_id, claimed_neighbors in reports.items():
        mutual_peers = [
            peer for peer in claimed_neighbors
            if student_id in reports.get(peer, [])
        ]
        direct_mutuals[student_id] = mutual_peers
        if len(mutual_peers) > 0:
            verified_set.add(student_id)

    # Step 2: Triadic closure set intersection via verified bridge -> Affinity 0.50
    changed = True
    while changed:
        changed = False
        for student_id, claimed_neighbors in reports.items():
            if student_id not in verified_set:
                shared_bridges = set(claimed_neighbors) & verified_set
                if len(shared_bridges) >= 2:
                    verified_set.add(student_id)
                    bridge_map[student_id] = next(iter(shared_bridges))
                    changed = True

    # Step 3: Final classification & affinity scoring
    for student_id, claimed_neighbors in reports.items():
        mutuals = direct_mutuals.get(student_id, [])

        if len(mutuals) > 0:
            status = "VERIFIED_PRESENT"
            method = "DIRECT_MUTUAL"
            affinity = 1.00
            bridge = None
            remarks = f"Direct mutual desk neighbor with [{', '.join(mutuals)}]"

        elif student_id in verified_set:
            status = "VERIFIED_PRESENT"
            method = "TRIADIC_CLOSURE"
            affinity = 0.50
            bridge = bridge_map.get(student_id)
            remarks = f"Triadic closure via verified bridge node {bridge}"

        elif len(claimed_neighbors) == 0:
            status = "ISOLATED_FLAGGED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = "Zero desk neighbors detected (isolated seat)"

        elif len(verified_set) == 0 and any(n in reports for n in claimed_neighbors):
            status = "ISOLATED_FLAGGED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = f"In-room neighbor link to [{', '.join(claimed_neighbors)}] (Pending reciprocation - Teacher manual override recommended)"

        else:
            status = "SPOOFER_REJECTED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = f"Unreciprocated link to [{', '.join(claimed_neighbors)}] (Hallway Spoofer)"

        results.append({
            "ble_device_id": student_id,
            "status": status,
            "verification_method": method,
            "affinity_score": affinity,
            "bridge_device_id": bridge,
            "reported_neighbors": claimed_neighbors,
            "remarks": remarks,
            "mutuals": mutuals,
        })

    # Step 4: Pure In-Degree Social Graph Witnessing for Offline Students
    # Any peer mentioned in a verified student's desk neighbors who did not submit a report directly
    witness_map: Dict[str, List[str]] = {}
    for reporter_id in verified_set:
        for neighbor in reports.get(reporter_id, []):
            if neighbor not in reports:
                witness_map.setdefault(neighbor, []).append(reporter_id)

    for offline_id, witnesses in witness_map.items():
        if len(witnesses) >= 2:
            # Verified via >= 2 independent verified classmates (Tamper-proof against buddy punching)
            results.append({
                "ble_device_id": offline_id,
                "status": "VERIFIED_PRESENT",
                "verification_method": "PEER_WITNESS",
                "affinity_score": 0.85,
                "bridge_device_id": witnesses[0],
                "reported_neighbors": [],
                "remarks": f"Offline student witnessed by {len(witnesses)} verified classmates [{', '.join(witnesses)}]",
                "mutuals": witnesses,
            })
        else:
            # Single witness (Edge/corner desk student or potential buddy-punch attempt)
            # Surfaced for Teacher Manual Override
            results.append({
                "ble_device_id": offline_id,
                "status": "ISOLATED_FLAGGED",
                "verification_method": "NONE",
                "affinity_score": 0.00,
                "bridge_device_id": witnesses[0],
                "reported_neighbors": [],
                "remarks": f"Single verified witness [{witnesses[0]}] - Teacher manual override recommended",
                "mutuals": witnesses,
            })

    return results
