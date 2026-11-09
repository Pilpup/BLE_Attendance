from collections import deque


# Audits classroom BLE discovery reports to verify attendance using mesh graph topology
def audit_classroom_mesh(reports: dict[str, list[str]]) -> list[dict]:
    direct_mutuals: dict[str, list[str]] = {}
    results: list[dict] = []
    reports_sets: dict[str, set[str]] = {k: set(v) for k, v in reports.items()}
    empty_set: set[str] = set()

    # Identifies direct mutual links where peer A sees B and peer B sees A
    for student_id, claimed_neighbors in reports.items():
        mutual_peers = [
            peer for peer in claimed_neighbors
            if student_id in reports_sets.get(peer, empty_set)
        ]
        direct_mutuals[student_id] = mutual_peers

    # Discovers connected mutual components using breadth-first search
    visited_nodes: set[str] = set()
    components: list[set[str]] = []

    for student_id in reports.keys():
        if student_id not in visited_nodes and direct_mutuals.get(student_id):
            component: set[str] = set()
            queue = deque([student_id])
            visited_nodes.add(student_id)
            while queue:
                curr = queue.popleft()
                component.add(curr)
                for nbr in direct_mutuals.get(curr, []):
                    if nbr not in visited_nodes:
                        visited_nodes.add(nbr)
                        queue.append(nbr)
            components.append(component)

    # Sorts connected components by size descending
    components.sort(key=len, reverse=True)

    # Identifies the dominant in-room classroom component and isolates outside satellites
    verified_set: set[str] = set()
    satellite_clusters: dict[str, set[str]] = {}

    if len(components) == 1:
        verified_set = set(components[0])
    elif len(components) > 1:
        main_component = components[0]
        verified_set.update(main_component)
        total_reporters = len(reports)
        max_satellite_size = max(6, int(len(main_component) * 0.35))
        in_degree_req = 2 if len(main_component) >= 12 else 1

        for comp in components[1:]:
            # Counts incoming links from classroom core to candidate cluster
            incoming_anchors = sum(
                1 for m_node in main_component
                if bool(reports_sets.get(m_node, empty_set) & comp)
            )
            # Checks for cross-links between candidate cluster and classroom core
            has_link_to_main = incoming_anchors > 0 or any(
                bool(reports_sets.get(node, empty_set) & main_component)
                for node in comp
            )

            # Enforces quarantine for disconnected clusters in small seminar rooms
            if total_reporters <= 12:
                if not has_link_to_main or incoming_anchors < 1:
                    for node in comp:
                        satellite_clusters[node] = comp
                else:
                    verified_set.update(comp)

            # Quarantines completely isolated components in standard classrooms
            elif not has_link_to_main and len(main_component) >= 6:
                for node in comp:
                    satellite_clusters[node] = comp

            # Rejects unreciprocated out-degree claims without classroom anchors
            elif incoming_anchors < in_degree_req and len(comp) <= max_satellite_size and len(main_component) >= 6:
                for node in comp:
                    satellite_clusters[node] = comp
            else:
                verified_set.update(comp)

    # Evaluates presence status and calculates affinity scores for all reporting devices
    for student_id, claimed_neighbors in reports.items():
        mutuals = direct_mutuals.get(student_id, [])

        if student_id in satellite_clusters:
            # Flags disconnected satellite cluster detected outside classroom
            comp_nodes = sorted(list(satellite_clusters[student_id]))
            status = "ISOLATED_FLAGGED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = f"Disconnected satellite cluster [{', '.join(comp_nodes)}] (Likely outside classroom) - Teacher manual override recommended"

        elif student_id in verified_set:
            # Verifies student with mutual peer confirmations inside classroom core
            status = "VERIFIED_PRESENT"
            method = "DIRECT_MUTUAL"
            affinity = 1.00
            bridge = None
            remarks = f"Direct mutual desk neighbor with [{', '.join(mutuals)}]"

        elif len(claimed_neighbors) == 0:
            # Flags corner desk or solitary student reporting zero neighbors
            status = "ISOLATED_FLAGGED"
            method = "NONE"
            affinity = 0.00
            bridge = None
            remarks = "Zero desk neighbors detected (isolated seat)"

        else:
            # Evaluates unreciprocated claims against verified classroom nodes
            shared_bridges = reports_sets.get(student_id, empty_set) & verified_set
            if len(shared_bridges) >= 2:
                status = "ISOLATED_FLAGGED"
                method = "TRIADIC_CLOSURE"
                affinity = 0.50
                sorted_bridges = sorted(list(shared_bridges))
                bridge = sorted_bridges[0]
                remarks = f"Unreciprocated link to [{', '.join(sorted_bridges)}] (Pending Teacher Confirmation)"

            elif len(shared_bridges) == 1:
                # Classic component-riding attack: claiming a verified classroom
                # node without reciprocation stays flagged as spoofer
                status = "SPOOFER_REJECTED"
                method = "NONE"
                affinity = 0.00
                bridge = None
                remarks = f"Unreciprocated link to [{', '.join(claimed_neighbors)}] (Hallway Spoofer - claimed a verified classroom node without reciprocation)"

            else:
                # Zero verified bridges: distinguishes radio-timing asymmetry from
                # fabricated claims. A claim pointing at a peer that itself
                # submitted a report during this session is a plausible real-world
                # neighbor that simply did not reciprocate before finalize (BLE
                # discovery windows are asymmetric) — flag for manual review, not
                # spoofer. Claims referencing keys that never checked in remain
                # spoofed — nobody with that device was in the room.
                submitted_peers = [peer for peer in claimed_neighbors if peer in reports]
                if submitted_peers:
                    status = "ISOLATED_FLAGGED"
                    method = "PEER_UNCONFIRMED"
                    affinity = 0.25
                    bridge = None
                    remarks = f"Unreciprocated link to [{', '.join(sorted(claimed_neighbors))}] (Referenced peer submitted a report but did not confirm) - Teacher manual override recommended"
                else:
                    status = "SPOOFER_REJECTED"
                    method = "NONE"
                    affinity = 0.00
                    bridge = None
                    remarks = f"Unreciprocated link to [{', '.join(claimed_neighbors)}] (Claimed peers never checked in during the session)"

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

    # Collects offline peers reported by verified in-room classmates. Peer
    # witnessing is hearsay — two colluding friends could ghost an absent
    # classmate from home by appending a known id. Witnessing must therefore
    # only RAISE a record to instructor review, never auto-verify it.
    witness_map: dict[str, list[str]] = {}
    for reporter_id in verified_set:
        for neighbor in reports.get(reporter_id, []):
            if neighbor not in reports:
                witness_map.setdefault(neighbor, []).append(reporter_id)

    # Flags offline peers for instructor review based on witness support
    for offline_id, witnesses in witness_map.items():
        if len(witnesses) >= 2:
            results.append({
                "ble_device_id": offline_id,
                "status": "FLAGGED_WITNESSED",
                "verification_method": "PEER_WITNESS",
                "affinity_score": 0.00,
                "bridge_device_id": witnesses[0],
                "reported_neighbors": [],
                "remarks": f"Witnessed by {len(witnesses)} verified classmates [{', '.join(witnesses)}] - Instructor confirmation required",
                "mutuals": witnesses,
            })
        else:
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
