from typing import Dict, List, Optional, Set


# Social mesh graph topology engine with Giant Component Anchor & Quarantine Gates
def audit_classroom_mesh(reports: Dict[str, List[str]]) -> List[dict]:
    direct_mutuals: Dict[str, List[str]] = {}
    results: List[dict] = []

    # 1. Identify all direct mutual links (undirected edges: A in reports[B] and B in reports[A])
    for student_id, claimed_neighbors in reports.items():
        mutual_peers = [
            peer for peer in claimed_neighbors
            if student_id in reports.get(peer, [])
        ]
        direct_mutuals[student_id] = mutual_peers

    # 2. Find Connected Components of mutual edges using BFS
    visited_nodes: Set[str] = set()
    components: List[Set[str]] = []

    for student_id in reports.keys():
        if student_id not in visited_nodes and len(direct_mutuals.get(student_id, [])) > 0:
            component: Set[str] = set()
            queue = [student_id]
            visited_nodes.add(student_id)
            while queue:
                curr = queue.pop(0)
                component.add(curr)
                for nbr in direct_mutuals.get(curr, []):
                    if nbr not in visited_nodes:
                        visited_nodes.add(nbr)
                        queue.append(nbr)
            components.append(component)

    # Sort components by size descending
    components.sort(key=len, reverse=True)

    # 3. Identify the Classroom Main/Giant Component
    # A component is considered the in-room classroom core if it is the largest,
    # or if it has connections to the majority of reporting students.
    verified_set: Set[str] = set()
    satellite_clusters: Dict[str, Set[str]] = {}  # student_id -> component set

    if len(components) == 1:
        # All mutual students belong to a single classroom web
        verified_set = set(components[0])
    elif len(components) > 1:
        main_component = components[0]
        verified_set.update(main_component)
        total_reporters = len(reports)
        max_satellite_size = max(6, int(len(main_component) * 0.35))
        in_degree_req = 2 if len(main_component) >= 12 else 1

        for comp in components[1:]:
            # Count INCOMING acknowledgements: How many distinct students in main_component
            # reported seeing someone in this candidate component?
            incoming_anchors = sum(
                1 for m_node in main_component
                if any(c_node in reports.get(m_node, []) for c_node in comp)
            )
            # Check if there is ANY cross-link (in or out) connecting this component to the classroom core
            has_link_to_main = any(
                any(nbr in main_component for nbr in reports.get(node, [])) or
                any(node in reports.get(m_node, []) for m_node in main_component)
                for node in comp
            )

            # Rule 1: In small seminar/lab sessions (<= 12 total reporting participants),
            # any secondary cluster MUST have direct incoming acknowledgements from the seminar core (incoming_anchors >= 1)
            # and cross-links; otherwise it is quarantined as an outside satellite.
            if total_reporters <= 12:
                if not has_link_to_main or incoming_anchors < 1:
                    for node in comp:
                        satellite_clusters[node] = comp
                else:
                    verified_set.update(comp)

            # Rule 2: In standard classrooms, a completely isolated component with zero cross-links is quarantined
            elif not has_link_to_main and len(main_component) >= 6:
                for node in comp:
                    satellite_clusters[node] = comp

            # Rule 3: In standard classrooms, out-degree eavesdropping claims (comp reporting main_component)
            # are strictly rejected; the classroom core must provide at least in_degree_req incoming acknowledgements.
            elif incoming_anchors < in_degree_req and len(comp) <= max_satellite_size and len(main_component) >= 6:
                for node in comp:
                    satellite_clusters[node] = comp
            else:
                # Legitimate separated classroom row/bench confirmed by in-room peers
                verified_set.update(comp)

    # 4. Classify Presence Status & Compute Affinity Scores
    for student_id, claimed_neighbors in reports.items():
        mutuals = direct_mutuals.get(student_id, [])

        if student_id in satellite_clusters:
            # Satellite cluster gathered outside the classroom
            comp_nodes = sorted(list(satellite_clusters[student_id]))
            status = "ISOLATED_FLAGGED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = f"Disconnected satellite cluster [{', '.join(comp_nodes)}] (Likely outside classroom) - Teacher manual override recommended"

        elif student_id in verified_set:
            # Direct mutual desk neighbor in the classroom web
            status = "VERIFIED_PRESENT"
            method = "DIRECT_MUTUAL"
            affinity = 1.00
            bridge = None
            remarks = f"Direct mutual desk neighbor with [{', '.join(mutuals)}]"

        elif len(claimed_neighbors) == 0:
            # Corner desk / solitary student with 0 neighbors
            status = "ISOLATED_FLAGGED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = "Zero desk neighbors detected (isolated seat)"

        else:
            # Node has out-degree claims, but no mutual return link
            shared_bridges = set(claimed_neighbors) & verified_set
            if len(shared_bridges) >= 2:
                # Student claims >= 2 verified peers, but neither reported them back (Triadic claim)
                # QUARANTINED: NEVER auto-verified to prevent hallway 2-ID sniffing attacks!
                status = "ISOLATED_FLAGGED"
                method = "TRIADIC_CLOSURE"
                affinity = 0.50
                sorted_bridges = sorted(list(shared_bridges))
                bridge = sorted_bridges[0]
                remarks = f"Unreciprocated link to [{', '.join(sorted_bridges)}] (Pending Teacher Confirmation)"

            elif len(shared_bridges) == 1:
                # Unreciprocated link to only 1 verified classmate (Hallway spoofer)
                status = "SPOOFER_REJECTED"
                method = "NONE"
                affinity = 0.00
                bridge = None
                remarks = f"Unreciprocated link to [{', '.join(claimed_neighbors)}] (Hallway Spoofer)"

            else:
                # Claims unverified or non-existent nodes
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

    # 5. Offline Peer Witnessing (In-Degree >= 2 from verified classroom nodes)
    witness_map: Dict[str, List[str]] = {}
    for reporter_id in verified_set:
        for neighbor in reports.get(reporter_id, []):
            if neighbor not in reports:
                witness_map.setdefault(neighbor, []).append(reporter_id)

    for offline_id, witnesses in witness_map.items():
        if len(witnesses) >= 2:
            # Verified via multiple independent in-room classmate witnesses
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
            # Single witness requiring teacher manual verification
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
