# social mesh graph topology engine
def audit_classroom_mesh(reports: dict[str, list[str]]) -> list[dict]:
    direct_mutuals: dict[str, list[str]] = {}
    verified_set: set[str] = set()
    results: list[dict] = []

    # direct mutual pairs ( A in P(B) and B in P(A))
    for student_id, claimed_neighbor in reports.items():
        mutual_peers = [
            peer for peer in claimed_neighbor
            if student_id in reports.get(peer, [])
        ]
        direct_mutuals[student_id] = mutual_peers
        if len(mutual_peers) > 0:
            verified_set.add(student_id)

    # if share a verified student, they are in the cluster
    changed = True
    while changed:
        changed = False
        for student_id, claimed_neighbor in reports.items():
            if student_id not in verified_set:
                shared_bridges = set(claimed_neighbor) & verified_set
                if len(shared_bridges) > 0:
                    verified_set.add(student_id)
                    changed = True

    # final classification
    for student_id, claimed_neighbor in reports.items():
        mutuals = direct_mutuals.get(student_id, [])

        if len(mutuals) > 0:
            status = "VERIFIED_PRESENT"
            reason = f"Direct mutuals with [{', '.join(mutuals)}]"

        elif student_id in verified_set:
            status = "VERIFIED_PRESENT"
            reason = "Triadic closure via shared bridge in classroom cluster"

        elif len(claimed_neighbor) == 0:
            status = "ISOLATED_FLAGGED"
            reason = "Zero desk neighbors detected (isolated seat)"
        else:
            status = "SPOOFER_REJECTED"
            reason = f"Unreciprocated link to [{', '.join(claimed_neighbor)}] (Hallway Spoofer)"

        results.append({
            "student_id": student_id,
            "status": status,
            "reason": reason,
            "mutuals": mutuals
        })
    return results
