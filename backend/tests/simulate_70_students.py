import math
import random
from typing import Dict, List, Set, Tuple
from app.services.graph_engine import audit_classroom_mesh

class AuditoriumClassroomSim:
    def __init__(self, seed: int = 42):
        random.seed(seed)
        self.tx_power_ref = -65.0       # Calibrated RSSI at 1m (ADVERTISE_TX_POWER_LOW)
        self.path_loss_exp = 2.4        # Indoor classroom exponent
        self.rssi_cutoff = -82.0        # Top neighbors filter threshold
        self.max_reported_neighbors = 6

    def generate_classroom_and_spoofers(self, trial_idx: int) -> Tuple[Dict[str, Tuple[float, float]], Dict[str, dict], Set[str], Set[str]]:
        positions = {}
        phone_profiles = {}
        in_class_ids = set()
        spoofer_ids = set()

        aisle1_w = random.uniform(1.6, 2.0)
        aisle2_w = random.uniform(1.6, 2.0)
        row_spacing = random.uniform(1.1, 1.25)

        col_x = [
            0.5,                                    # Col 0: Left window seat (||)
            1.3,                                    # Col 1: Left aisle seat  (||)
            1.3 + aisle1_w,                         # Col 2: Center left seat (|||)
            1.3 + aisle1_w + 0.8,                   # Col 3: Center mid seat  (|||)
            1.3 + aisle1_w + 1.6,                   # Col 4: Center right seat(|||)
            1.3 + aisle1_w + 1.6 + aisle2_w,        # Col 5: Right aisle seat (||)
            1.3 + aisle1_w + 1.6 + aisle2_w + 0.8,  # Col 6: Right wall/door seat (||)
        ]
        room_x_max = col_x[6] + 0.9

        scenario_roll = random.random()
        if scenario_roll < 0.50:
            target_students = 70
        elif scenario_roll < 0.80:
            target_students = random.randint(58, 68)
        else:
            target_students = random.randint(48, 57)

        # Generate all 70 seat slots (10 rows x 7 cols)
        all_seats = []
        for r in range(10):
            y = 2.0 + r * row_spacing
            for c in range(7):
                all_seats.append((r, c, col_x[c], y))

        occupied_seats = random.sample(all_seats, target_students)

        # Assign students to seats with realistic hardware/human biases
        for idx, (r, c, x, y) in enumerate(occupied_seats, 1):
            sid = f"ST{idx:02d}"
            positions[sid] = (x, y)
            in_class_ids.add(sid)

            bank = "LEFT" if c <= 1 else ("CENTER" if c <= 4 else "RIGHT")
            phone_profiles[sid] = {
                "is_offline": False,
                "tx_bias": random.gauss(0, 2.2),
                "rx_bias": random.gauss(0, 2.0),
                "body_shield": random.choice([0.0, 0.0, 4.0, 8.0, 12.0]), # desk, hand, pocket, deep bag
                "is_spoofer": False,
                "bank": bank,
                "location_type": f"seat_r{r}_c{c}"
            }

        # Offline students: 3 to 7 students per session
        num_offline = random.randint(3, 7)
        offline_sids = random.sample(list(in_class_ids), min(num_offline, len(in_class_ids)))
        for sid in offline_sids:
            phone_profiles[sid]["is_offline"] = True

        spoofer_configs = [
            # Scenario A: Outside Window cluster (3 students standing under classroom window)
            ("SP_WIN_1", -1.5, random.uniform(3.0, 6.0), "window_cluster", 10.0),
            ("SP_WIN_2", -1.8, random.uniform(3.5, 6.5), "window_cluster", 10.0),
            ("SP_WIN_3", -2.1, random.uniform(4.0, 7.0), "window_cluster", 10.0),

            # Scenario B: Hallway Door eavesdroppers (2 students outside front entry door)
            ("SP_DOOR_1", room_x_max + 1.0, random.uniform(1.8, 3.0), "hallway_door", 16.0),
            ("SP_DOOR_2", room_x_max + 1.4, random.uniform(2.2, 3.5), "hallway_door", 16.0),

            # Scenario C: Corridor Walkers (2 students walking/colluding in hallway)
            ("SP_HALL_1", room_x_max + 1.2, random.uniform(5.5, 8.0), "hallway_walker", 16.0),
            ("SP_HALL_2", room_x_max + 1.5, random.uniform(6.5, 9.0), "hallway_walker", 16.0),

            # Scenario D: Cafeteria clique (2 students 35m away exchanging mutual links)
            ("SP_CAF_1", 35.0, 30.0, "cafeteria_clique", 30.0),
            ("SP_CAF_2", 35.5, 30.3, "cafeteria_clique", 30.0),

            # Scenario E: Parking lot / lone distant spoofer (50m away)
            ("SP_PARK_1", -30.0, -20.0, "parking_lot_lone", 30.0),
        ]

        for sid, sx, sy, ltype, wall_loss in spoofer_configs:
            positions[sid] = (sx, sy)
            spoofer_ids.add(sid)
            phone_profiles[sid] = {
                "is_offline": False,
                "tx_bias": random.gauss(0, 2.5),
                "rx_bias": random.gauss(0, 2.5),
                "body_shield": 0.0,
                "is_spoofer": True,
                "bank": "OUTSIDE",
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

        # Ray crosses exterior wall or window
        a_is_spoofer = profiles[id_a]["is_spoofer"]
        b_is_spoofer = profiles[id_b]["is_spoofer"]
        if a_is_spoofer != b_is_spoofer:
            spoofer_prof = profiles[id_a] if a_is_spoofer else profiles[id_b]
            wall_loss = spoofer_prof.get("wall_loss", 16.0)
            rssi -= wall_loss

        # Multipath shadow fading N(0, 4.0 dB)
        shadow_fading = random.gauss(0.0, 4.0)
        rssi += shadow_fading

        return rssi

    def run_single_simulation(self, trial_idx: int) -> dict:
        positions, profiles, in_class_ids, spoofer_ids = self.generate_classroom_and_spoofers(trial_idx)
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
                        drop_prob = (rssi - (-76.0)) / (-6.0) * 0.40 # up to 40% loss
                    if random.random() > drop_prob:
                        observed_peers.append((target, rssi))

            observed_peers.sort(key=lambda x: x[1], reverse=True)
            top_neighbors = [p[0] for p in observed_peers[:self.max_reported_neighbors]]
            reports[observer] = top_neighbors

        audit_results = audit_classroom_mesh(reports)
        res_map = {r["ble_device_id"]: r for r in audit_results}

        verified_in_class = 0
        quarantined_in_class = 0
        rejected_in_class = 0

        verified_spoofers = 0
        quarantined_spoofers = 0
        rejected_spoofers = 0

        spoofer_details = {}
        for spid in spoofer_ids:
            rec = res_map.get(spid)
            if not rec:
                status = "COMPLETELY_IGNORED"
                rejected_spoofers += 1
            else:
                status = rec["status"]
                if status == "VERIFIED_PRESENT":
                    verified_spoofers += 1
                elif status == "ISOLATED_FLAGGED":
                    quarantined_spoofers += 1
                else:
                    rejected_spoofers += 1
            spoofer_details[spid] = {
                "type": profiles[spid]["location_type"],
                "status": status,
                "claimed": reports.get(spid, []),
                "remarks": rec["remarks"] if rec else "0 reports recorded"
            }

        in_class_details = {}
        for cid in in_class_ids:
            rec = res_map.get(cid)
            if not rec:
                status = "DROPPED"
                rejected_in_class += 1
            else:
                status = rec["status"]
                if status == "VERIFIED_PRESENT":
                    verified_in_class += 1
                elif status == "ISOLATED_FLAGGED":
                    quarantined_in_class += 1
                else:
                    rejected_in_class += 1
            in_class_details[cid] = {
                "bank": profiles[cid]["bank"],
                "is_offline": profiles[cid]["is_offline"],
                "status": status,
                "claimed": reports.get(cid, []),
                "remarks": rec["remarks"] if rec else "No record generated"
            }

        return {
            "total_in_class": len(in_class_ids),
            "total_spoofers": len(spoofer_ids),
            "verified_in_class": verified_in_class,
            "quarantined_in_class": quarantined_in_class,
            "rejected_in_class": rejected_in_class,
            "verified_spoofers": verified_spoofers,
            "quarantined_spoofers": quarantined_spoofers,
            "rejected_spoofers": rejected_spoofers,
            "spoofer_details": spoofer_details,
            "in_class_details": in_class_details
        }


def run_monte_carlo(trials: int = 1000):
    print("=================================================================")
    print(f"RUNNING {trials:,} MONTE CARLO SIMULATIONS (3-COLUMN AUDITORIUM: ||  |||  ||)")
    print("Geometry: Left Bank (2 seats), Aisle 1 (<=2m), Center (3 seats), Aisle 2 (<=2m), Right (2 seats)")
    print("Distortions: PathLoss(n=2.4) + Fading(sigma=4dB) + Wall/Window Atten + Hardware Biases")
    print("Attendance: 50% Full (70), 30% Moderate (58-68), 20% Sparse (48-57)")
    print("=================================================================\n")

    total_in_class = 0
    total_verified_innocent = 0
    total_quarantined_innocent = 0
    total_rejected_innocent = 0

    total_spoofers = 0
    total_breaches = 0
    total_quarantined_spoofers = 0
    total_rejected_spoofers = 0

    breaches_by_type = {}
    quarantined_by_bank = {"LEFT": 0, "CENTER": 0, "RIGHT": 0}
    sample_run = None

    for trial in range(trials):
        sim = AuditoriumClassroomSim(seed=2000 + trial)
        res = sim.run_single_simulation(trial)
        if trial == 0:
            sample_run = res

        total_in_class += res["total_in_class"]
        total_verified_innocent += res["verified_in_class"]
        total_quarantined_innocent += res["quarantined_in_class"]
        total_rejected_innocent += res["rejected_in_class"]

        total_spoofers += res["total_spoofers"]
        total_breaches += res["verified_spoofers"]
        total_quarantined_spoofers += res["quarantined_spoofers"]
        total_rejected_spoofers += res["rejected_spoofers"]

        for spid, data in res["spoofer_details"].items():
            if data["status"] == "VERIFIED_PRESENT":
                t = data["type"]
                breaches_by_type[t] = breaches_by_type.get(t, 0) + 1

        for cid, data in res["in_class_details"].items():
            if data["status"] == "ISOLATED_FLAGGED":
                bank = data.get("bank", "UNKNOWN")
                if bank in quarantined_by_bank:
                    quarantined_by_bank[bank] += 1

    print("--- [OVERALL RESULTS ACROSS 1,000 SESSIONS] ---")
    print(f"Total Sessions: {trials:,}")
    print(f"Total Student Audits Evaluated: {total_in_class + total_spoofers:,}")
    print(f"Total In-Class Students: {total_in_class:,} (avg {total_in_class / trials:.1f} / session)")
    print(f"Total Outside Spoofer Attacks: {total_spoofers:,} (10 / session)")
    print("-----------------------------------------------------------------")
    print(f"CRITICAL SPOOFER BREACHES: {total_breaches} / {total_spoofers} ({total_breaches / total_spoofers * 100:.2f}%)")
    print(f"SPOOFERS QUARANTINED (ISOLATED_FLAGGED): {total_quarantined_spoofers} / {total_spoofers} ({total_quarantined_spoofers / total_spoofers * 100:.2f}%)")
    print(f"SPOOFERS REJECTED / DROPPED: {total_rejected_spoofers} / {total_spoofers} ({total_rejected_spoofers / total_spoofers * 100:.2f}%)")
    print(f"TOTAL SPOOFER CATCH RATE: {(total_quarantined_spoofers + total_rejected_spoofers) / total_spoofers * 100:.2f}%")
    print("-----------------------------------------------------------------")
    print(f"INNOCENT AUTO-VERIFIED: {total_verified_innocent} / {total_in_class} ({total_verified_innocent / total_in_class * 100:.2f}%)")
    print(f"INNOCENT QUARANTINED:    {total_quarantined_innocent} / {total_in_class} ({total_quarantined_innocent / total_in_class * 100:.2f}%)")
    print(f"INNOCENT FALSE REJECTIONS: {total_rejected_innocent} / {total_in_class} ({total_rejected_innocent / total_in_class * 100:.2f}%)")
    print("-----------------------------------------------------------------")
    print(f"Breaches by Threat Category: {breaches_by_type}")
    print(f"Innocent Quarantines by Column Bank: {quarantined_by_bank}")
    print("=================================================================\n")

    print("--- [SAMPLE SESSION DRILL-DOWN (SESSION #1)] ---")
    print("1. SPOOFER BEHAVIOR & DETECTION:")
    for sid, info in sample_run["spoofer_details"].items():
        print(f"  • {sid:14} [{info['type']:18}] ➔ Status: {info['status']:18} | Claimed: {info['claimed'][:3]} | {info['remarks']}")

    print("\n2. OFFLINE IN-CLASS STUDENTS (PEER WITNESS):")
    for sid, info in sample_run["in_class_details"].items():
        if info["is_offline"]:
            print(f"  • {sid:14} [OFFLINE STUDENT   ] ➔ Status: {info['status']:18} | {info['remarks']}")


if __name__ == "__main__":
    run_monte_carlo(trials=1000)
