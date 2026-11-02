import math
import random
from typing import Dict, List, Set, Tuple
from app.services.graph_engine import audit_classroom_mesh

class SmallLabSim:
    """
    Simulates a small seminar / computer lab session with 5 to 15 students.
    Tests how the graph mesh behaves when class size is small,
    especially against outside spoofers (door eavesdroppers, window sniffers, cafeteria cliques).
    """
    def __init__(self, seed: int = 42):
        random.seed(seed)
        self.tx_power_ref = -65.0
        self.path_loss_exp = 2.4
        self.rssi_cutoff = -82.0
        self.max_reported_neighbors = 6

    def generate_lab_and_spoofers(self, num_students: int, num_spoofers: int = 4) -> Tuple[Dict[str, Tuple[float, float]], Dict[str, dict], Set[str], Set[str]]:
        positions = {}
        phone_profiles = {}
        in_class_ids = set()
        spoofer_ids = set()

        # Lab room: 6m x 6m room
        # Students sit around 1 or 2 lab benches in the center: (x in [1.5, 4.5], y in [1.5, 4.5])
        # Door is at (x=6.0, y=1.5)
        # Window is at (x=0.0, y=3.0)
        lab_seats = [
            (2.0, 2.0), (2.8, 2.0), (3.6, 2.0), (4.4, 2.0), # Bench 1 Front
            (2.0, 3.2), (2.8, 3.2), (3.6, 3.2), (4.4, 3.2), # Bench 1 Back / Facing
            (2.0, 4.5), (2.8, 4.5), (3.6, 4.5), (4.4, 4.5), # Bench 2
            (1.5, 5.2), (3.0, 5.2), (4.5, 5.2), (5.0, 3.0), # Extra seats
        ]

        selected_seats = random.sample(lab_seats, min(num_students, len(lab_seats)))
        for i, (x, y) in enumerate(selected_seats, 1):
            sid = f"LAB_{i:02d}"
            positions[sid] = (x, y)
            in_class_ids.add(sid)
            phone_profiles[sid] = {
                "is_offline": False,
                "tx_bias": random.gauss(0, 2.2),
                "rx_bias": random.gauss(0, 2.0),
                "body_shield": random.choice([0.0, 0.0, 4.0, 8.0]),
                "is_spoofer": False,
                "location_type": "lab_bench"
            }

        # 1-2 students might be offline
        if num_students >= 6 and random.random() < 0.40:
            offline_id = random.choice(list(in_class_ids))
            phone_profiles[offline_id]["is_offline"] = True

        # Generate outside spoofers
        # Threat A: Door eavesdroppers (2 students right outside the door at x=7.2, y=1.5)
        # Threat B: Window sniffers (2 students outside window at x=-1.5, y=3.0)
        # Threat C: Cafeteria colluders (2 students at x=25, y=20)
        potential_spoofers = [
            ("SP_DOOR_1", 7.2, 1.5, "hallway_door", 16.0),
            ("SP_DOOR_2", 7.6, 1.8, "hallway_door", 16.0),
            ("SP_WIN_1", -1.5, 2.8, "window_cluster", 10.0),
            ("SP_WIN_2", -1.8, 3.2, "window_cluster", 10.0),
            ("SP_CAF_1", 25.0, 20.0, "cafeteria_clique", 30.0),
            ("SP_CAF_2", 25.5, 20.3, "cafeteria_clique", 30.0),
        ]

        selected_spoofers = potential_spoofers[:num_spoofers]
        for sid, sx, sy, ltype, wall_loss in selected_spoofers:
            positions[sid] = (sx, sy)
            spoofer_ids.add(sid)
            phone_profiles[sid] = {
                "is_offline": False,
                "tx_bias": random.gauss(0, 2.5),
                "rx_bias": random.gauss(0, 2.5),
                "body_shield": 0.0,
                "is_spoofer": True,
                "location_type": ltype,
                "wall_loss": wall_loss
            }

        return positions, phone_profiles, in_class_ids, spoofer_ids

    def compute_simulated_rssi(self, id_a: str, id_b: str, pos: Dict[str, Tuple[float, float]], profiles: Dict[str, dict]) -> float:
        xa, ya = pos[id_a]
        xb, yb = pos[id_b]
        distance = math.sqrt((xa - xb) ** 2 + (ya - yb) ** 2)
        if distance < 0.1:
            distance = 0.1

        path_loss = 10.0 * self.path_loss_exp * math.log10(distance)
        rssi = self.tx_power_ref - path_loss

        rssi += profiles[id_a]["tx_bias"]
        rssi += profiles[id_b]["rx_bias"]
        rssi -= profiles[id_a]["body_shield"]
        rssi -= profiles[id_b]["body_shield"]

        # Ray crosses wall/window into lab
        a_is_spoofer = profiles[id_a]["is_spoofer"]
        b_is_spoofer = profiles[id_b]["is_spoofer"]
        if a_is_spoofer != b_is_spoofer:
            spoofer_prof = profiles[id_a] if a_is_spoofer else profiles[id_b]
            rssi -= spoofer_prof.get("wall_loss", 16.0)

        rssi += random.gauss(0.0, 4.0) # shadow fading
        return rssi

    def run_single(self, num_students: int, num_spoofers: int = 4) -> dict:
        positions, profiles, in_class_ids, spoofer_ids = self.generate_lab_and_spoofers(num_students, num_spoofers)
        all_ids = list(positions.keys())
        reports: Dict[str, List[str]] = {}

        for observer in all_ids:
            if profiles[observer]["is_offline"]:
                continue
            observed_peers = []
            for target in all_ids:
                if target == observer:
                    continue
                rssi = self.compute_simulated_rssi(target, observer, positions, profiles)
                if rssi >= self.rssi_cutoff:
                    drop_prob = 0.0
                    if rssi < -76.0:
                        drop_prob = (rssi - (-76.0)) / (-6.0) * 0.40
                    if random.random() > drop_prob:
                        observed_peers.append((target, rssi))
            observed_peers.sort(key=lambda x: x[1], reverse=True)
            reports[observer] = [p[0] for p in observed_peers[:self.max_reported_neighbors]]

        audit_results = audit_classroom_mesh(reports)
        res_map = {r["ble_device_id"]: r for r in audit_results}

        verified_in_class = sum(1 for cid in in_class_ids if res_map.get(cid, {}).get("status") == "VERIFIED_PRESENT")
        quarantined_in_class = sum(1 for cid in in_class_ids if res_map.get(cid, {}).get("status") == "ISOLATED_FLAGGED")
        rejected_in_class = sum(1 for cid in in_class_ids if res_map.get(cid, {}).get("status", "DROPPED") not in ("VERIFIED_PRESENT", "ISOLATED_FLAGGED"))

        verified_spoofers = sum(1 for sp in spoofer_ids if res_map.get(sp, {}).get("status") == "VERIFIED_PRESENT")
        quarantined_spoofers = sum(1 for sp in spoofer_ids if res_map.get(sp, {}).get("status") == "ISOLATED_FLAGGED")
        rejected_spoofers = sum(1 for sp in spoofer_ids if res_map.get(sp, {}).get("status", "REJECTED") not in ("VERIFIED_PRESENT", "ISOLATED_FLAGGED"))

        return {
            "num_students": num_students,
            "num_spoofers": num_spoofers,
            "verified_in_class": verified_in_class,
            "quarantined_in_class": quarantined_in_class,
            "rejected_in_class": rejected_in_class,
            "verified_spoofers": verified_spoofers,
            "quarantined_spoofers": quarantined_spoofers,
            "rejected_spoofers": rejected_spoofers,
            "spoofer_details": {sp: res_map.get(sp, {}) for sp in spoofer_ids}
        }


def run_small_lab_suite():
    class_sizes = [5, 6, 8, 10, 12, 14]
    trials_per_size = 100
    print("=================================================================")
    print(f"RUNNING SMALL LAB / SEMINAR SIMULATION SUITE")
    print(f"Testing Class Sizes: {class_sizes} students | 4 Outside Spoofers per session")
    print(f"Total Trials: {len(class_sizes) * trials_per_size} ({trials_per_size} per class size)")
    print("=================================================================\n")

    overall_results = {}

    for size in class_sizes:
        sp_breaches = 0
        sp_quarantined = 0
        sp_rejected = 0
        in_verified = 0
        in_quarantined = 0
        in_rejected = 0
        total_sp = trials_per_size * 4
        total_in = trials_per_size * size

        sample_breach = None

        for t in range(trials_per_size):
            sim = SmallLabSim(seed=3000 + size * 1000 + t)
            res = sim.run_single(num_students=size, num_spoofers=4)

            sp_breaches += res["verified_spoofers"]
            sp_quarantined += res["quarantined_spoofers"]
            sp_rejected += res["rejected_spoofers"]

            in_verified += res["verified_in_class"]
            in_quarantined += res["quarantined_in_class"]
            in_rejected += res["rejected_in_class"]

            if res["verified_spoofers"] > 0 and not sample_breach:
                sample_breach = (t, res["spoofer_details"])

        overall_results[size] = {
            "sp_breaches": sp_breaches,
            "sp_breach_rate": sp_breaches / total_sp * 100,
            "sp_catch_rate": (sp_quarantined + sp_rejected) / total_sp * 100,
            "in_verified_rate": in_verified / total_in * 100,
            "in_quarantine_rate": in_quarantined / total_in * 100,
            "sample_breach": sample_breach
        }

        print(f"--- [CLASS SIZE: {size:2d} STUDENTS] ---")
        print(f"  🚨 Spoofer Breaches: {sp_breaches:3d} / {total_sp} ({sp_breaches / total_sp * 100:5.2f}%)")
        print(f"  🛡️ Spoofer Caught:   {sp_quarantined + sp_rejected:3d} / {total_sp} ({(sp_quarantined + sp_rejected) / total_sp * 100:5.2f}%)")
        print(f"  ✅ Innocent Verified: {in_verified:3d} / {total_in} ({in_verified / total_in * 100:5.2f}%)")
        print(f"  ⚠️ Innocent Flagged:  {in_quarantined:3d} / {total_in} ({in_quarantined / total_in * 100:5.2f}%)")
        if sample_breach:
            print(f"  Sample Breach in Trial {sample_breach[0]}:")
            for spid, dt in sample_breach[1].items():
                if dt.get("status") == "VERIFIED_PRESENT":
                    print(f"    • {spid}: status={dt.get('status')} | remarks={dt.get('remarks')}")
        print()

    print("=================================================================")
    print("SUMMARY BY CLASS SIZE:")
    print("Size | Spoofer Breach Rate | Spoofer Catch Rate | Innocent Auto-Verified")
    print("------------------------------------------------------------------------")
    for size, r in overall_results.items():
        print(f" {size:2d}  |       {r['sp_breach_rate']:5.2f}%       |       {r['sp_catch_rate']:5.2f}%      |         {r['in_verified_rate']:5.2f}%")
    print("=================================================================")


if __name__ == "__main__":
    run_small_lab_suite()

