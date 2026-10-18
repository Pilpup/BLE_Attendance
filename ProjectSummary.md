# BLE Autonomous Classroom Attendance Verification System
## Comprehensive Technical Specification, RF Theory, Evolutionary History, & Developer Context

> **Note for Future AI Assistants:**  
> This document is the **single source of truth** for the BLE Attendance project located at `/home/wink/Projects/ble_attendance`. It contains the complete architectural context, mathematical formulations, lessons from empirical physical failures, protocol byte budgets, code mappings, and security threat models. Read this thoroughly before modifying any code.

---

## 1. Project Overview & Core Mission

### 1.1 The Objective
Build a **100% offline, peer-to-peer, automated Android BLE attendance system** that runs exclusively between a teacher's smartphone and student smartphones in an indoor university classroom.

### 1.2 Non-Negotiable Constraints
1. **Zero External Hardware:** No Estimote beacons, dedicated wall-mounted scanners, ESP32s, or Raspberry Pis. The system must operate solely using student and teacher phones.
2. **Zero Cloud / Internet Dependency:** Must work in classrooms with poor or nonexistent cellular/Wi-Fi coverage. All discovery, communication, and audits occur peer-to-peer over raw BLE advertising channels (37, 38, 39).
3. **Zero Manual User Friction:** 
   - No typing student IDs, roll numbers, room codes, or passwords.
   - No QR code scanning or camera selfies (facial recognition is rejected as high friction).
   - Student phones must verify presence autonomously while resting on desks or in pockets.
4. **Zero Prior Room Calibration:** Neither teacher nor students provide classroom dimensions, seating charts, or anchor positions. The system autonomously discovers local topology.
5. **Strict Anti-Spoofing / Hallway Proxy Defense:** Must reliably detect and reject a "hallway hacker" or proxy student standing outside in the corridor near the classroom door attempting to claim attendance.

---

## 2. The Evolutionary History of Failures (Why Other Ideas Failed)

Understanding why earlier approaches collapsed in real-world testing is essential to avoid re-introducing retired flawed paradigms.

```
       [EVOLUTION OF ARCHITECTURAL ATTEMPTS]

  1. 2-Beacon Linear Differential
     └── FAILED: Collinear symmetry / hyperbolic contour ("The Doorway Trap").
         Inside (1m) and outside (1m) produce identical ratios (+10 dB).

  2. 3-Beacon Dynamic Triangulation (Teacher + C1 + C2 Back Corners)
     └── FAILED: Metal wall waveguide reflections in classrooms inverted RSSI.
         Back row: -70 dBm (constructive reflections).
         Center room: -80+ dBm (destructive multipath + body shielding).
         Radial minByOrNull explicitly elected the middle student as C1!

  3. Naive Mesh Reciprocity (4-Phone Field Test)
     └── FAILED: 3 distinct bugs:
         a) Probes at TX_POWER_HIGH reached 8m (-73 dBm), passing -78 dBm cutoff -> Far Guy verified!
         b) take(2) ranking truncation forced Middle to drop Front -> Front marked as spoofer!
         c) Teacher verdict sent in a 400ms flash -> Middle phone missed packet, stayed unverified.

  4. Current Final Paradigm: Social-Mesh Graph & Reciprocal Matchmaking
     └── SUCCESS: Ordinal Top-3 Ranking (hardware-gain invariant) +
                  Triadic Closure / Shared Bridge Verification +
                  Low Probe TX Power (-70 dBm cutoff) + 1800ms Bulk Verdicts.
```

### 2.1 Failure 1: The 2-Beacon Linear Ambiguity ("The Doorway Trap")
* **Concept:** Teacher phone at front podium ($y = 0\text{m}$), one student phone elected as Child Beacon at back wall ($y = 8\text{m}$). The differential ratio $\Delta = \text{RSSI}_T - \text{RSSI}_C$ was used to estimate depth.
* **Why It Failed:** 
  Distance is a scalar norm $\|x - x_{\text{beacon}}\|$ with no directional sign.
  - A student 1m inside the room in front of Teacher: $d_T = 1\text{m}, d_C = 7\text{m} \implies \text{Ratio} = 7.0 \implies \Delta \approx +16.9\text{ dB}$.
  - A hallway spoofer 1m outside in the hallway behind Teacher: $d_T = 1\text{m}, d_C = 9\text{m} \implies \text{Ratio} = 9.0 \implies \Delta \approx +19.1\text{ dB}$.
  - The theoretical difference was only $2.2\text{ dB}$, completely drowned out by indoor multipath fading ($\pm 4\text{ to }6\text{ dB}$). Both spots sat on the exact same hyperbolic signal contour. The spoofer was indistinguishable from a legitimate student.

### 2.2 Failure 2: 3-Beacon Triangulation & The Metal Wall Resonant Cavity
* **Concept:** Dynamically elect Child 1 at back-right and Child 2 at back-left to form a 2D bounding triangle enclosing the room.
* **Why It Failed (Empirical Breakthrough):**
  Classrooms with metal-reinforced walls, whiteboards, and steel desk frames act as **resonant RF waveguides**:
  - **Back row students received $\approx -70\text{ dBm}$** from the Teacher due to constructive specular reflections along perimeter walls.
  - **Center students received $\approx -80+\text{ dBm}$** from the Teacher due to destructive multipath phase cancellation and human body attenuation (70% water absorbing 2.4 GHz).
  - The election logic executed `val farthest = reports.minByOrNull { it.bidirectionalScore }`.
  - Because $-80+\text{ dBm} < -70\text{ dBm}$, `minByOrNull` mathematically selected the **middle student as the "farthest" beacon every single time**, inverting the coordinate system and rejecting interior students.

### 2.3 Failure 3: The 4-Phone Field Test Pathology
In testing with 4 phones (3 in a diagonal zigzag: Front, Middle, Back; 1 sitting 5m away):
1. **The Far Guy Got Verified:** Student probes broadcasted at `ADVERTISE_TX_POWER_HIGH` ($+1\text{ to } +4\text{ dBm}$). At 5m Line-of-Sight, RSSI was $-73.4\text{ dBm}$. Because the filter cutoff was set loosely to $\ge -78\text{ dBm}$, the distant student was treated as a close neighbor and formed mutual links with the middle phone.
2. **The Front Guy Got Marked as a Spoofer:** The code enforced `take(2)` on neighbor reporting. The Middle phone heard Back ($-62\text{ dBm}$), Far Guy ($-73\text{ dBm}$), and Front ($-75\text{ dBm}$). Truncating to 2 forced Middle to report $[B, X]$ and drop Front ($F$). Front reported $M$, but $M$ did not report $F$. The Teacher saw an unreciprocated link and falsely branded Front as a spoofer.
3. **The 400ms Verdict Drop:** The Teacher broadcasted `VERDICT:<ID>:VERIFIED` for only 400ms. In BLE, 400ms transmits only 3–4 packets. Channel hopping and scan latency caused the Middle phone to miss the burst, leaving its screen unverified while the Teacher showed verified.

---

## 3. The Final Architecture: Social-Mesh Graph & Reciprocal Matchmaking

The system discards absolute distance calculations in favor of **Ordinal Preference Ranking (Top-3 Matches)** combined with **Social-Graph Triadic Closure (Mutual Friends)**.

```
       [FRONT (F)] ─────── (1.2m) ─────── [MIDDLE (M)] ─────── (1.2m) ─────── [BACK (B)]
                                                  │
                                          (5m) [TX_POWER_LOW: Signal < -88 dBm]
                                                  │
                                            [FAR GUY (X)]
                                        (Outside Proximity Fence)
```

### 3.1 Principle 1: Ordinal Preference Ranking (Hardware-Gain Invariance)
* **The Problem:** Phone antennas vary by $\pm 10\text{ dB}$. A flagship phone with an LNA receiver measures signals louder than a budget plastic phone.
* **The Solution:** Ranking is an **ordinal invariant**. Regardless of whether a receiving antenna has $+5\text{ dB}$ or $-5\text{ dB}$ gain, the desk neighbor sitting $1\text{ meter}$ away will **always rank higher** than a student sitting 5 meters away.
* Every student records observed peers and reports their **Top 3 closest preferences** ($P(i) = [p_1, p_2, p_3]$) with median $\text{RSSI} \ge -70\text{ dBm}$.

### 3.2 Principle 2: Triadic Closure / Shared Mutual Friends
* Instead of brittle 1-to-1 binary reciprocity ($A \leftrightarrow B$ only), the system models the room as an undirected social graph and evaluates **Bilateral Affinity ($S_{A,B}$)**:
  $$S(A, B) = \begin{cases} 
  1.0 & \text{if } A \in P(B) \text{ and } B \in P(A) & \text{(Direct Mutual Match)} \\
  0.5 & \text{if } P(A) \cap P(B) \ne \emptyset & \text{(Triadic Closure via Shared Bridge)} \\
  0.0 & \text{otherwise} & \text{(Stranger / Spoofer)}
  \end{cases}$$
* **Why this fixes the Zigzag Squeeze-Out:**
  Front ($F$) claims Middle ($M$). Middle ($M$) and Back ($B$) have a direct mutual match. Because $F$ shares the verified bridge node $M$ with the cluster ($S = 0.5$), $F$ is confirmed part of the physical desk cluster and marked **`VERIFIED_PRESENT`**.

### 3.3 Principle 3: RF Power Separation & Near-Field Calibrated Fence
* **Teacher Orchestrator:** Broadcasts conductor ticks and verdicts at **`ADVERTISE_TX_POWER_HIGH`** ($+1\text{ to } +4\text{ dBm}$) to blanket the entire lecture hall.
* **Student Probes (`P:<myDeviceID>`):** Broadcast at **`ADVERTISE_TX_POWER_LOW`** (approx. $-15\text{ to } -20\text{ dBm}$).
  - At $1\text{m}$, RSSI is $-65\text{ to } -70\text{ dBm}$.
  - Beyond $2.5\text{m}$, RSSI drops below $-85\text{ dBm}$.
  - At $5\text{m}$ (Far Guy or Hallway Spoofer), the signal is below $-90\text{ dBm}$ or undetectable.
* **Calibrated Proximity Threshold:** Set strictly to **`it.medianRssi >= -70 dBm`**. Distant phones physically cannot qualify for anyone's preference list.

---

## 4. Multi-Cohort Orchestration & Anti-Collision Protocol

### 4.1 The Coprime Cohort Schedule ($\approx 18.7\text{s}$ Total)
If 60 students broadcast simultaneously on channels 37, 38, and 39, ALOHA packet collisions destroy throughput. Sequential 1-by-1 polling requires $60\text{s}$, which is too slow.

**The Solution:** Teacher conducts the room through two rounds of time-slotted cohort broadcasting using coprime moduli:
$$\text{Cohort}_4 = |\text{myID.hashCode()}| \pmod 4 \in \{0, 1, 2, 3\}$$
$$\text{Cohort}_3 = |\text{myID.hashCode()}| \pmod 3 \in \{0, 1, 2\}$$

* In any slot, only $\sim \frac{1}{4}$ of the students broadcast for 1600ms, while $75\%$ scan continuously.
* **Coprime Cross-Scan Guarantee:** Because $\gcd(4, 3) = 1$, by the Chinese Remainder Theorem, any two students who shared a broadcast slot in Round 1 are guaranteed to be in **different slots in Round 2**. 100% of student pairs cross-scan each other.

### 4.2 End-to-End Timing Sequence Diagram

```
[Time]        Teacher Action                      Active Student Cohort           Scanning Students
──────────────────────────────────────────────────────────────────────────────────────────────────────────
0.0s - 1.8s   Broadcast TICK:R1:G0 (HIGH) -----> Cohort 0 probes (P:ID, LOW) ---> Cohorts 1, 2, 3 scan
2.0s - 3.8s   Broadcast TICK:R1:G1 (HIGH) -----> Cohort 1 probes (P:ID, LOW) ---> Cohorts 0, 2, 3 scan
4.0s - 5.8s   Broadcast TICK:R1:G2 (HIGH) -----> Cohort 2 probes (P:ID, LOW) ---> Cohorts 0, 1, 3 scan
6.0s - 7.8s   Broadcast TICK:R1:G3 (HIGH) -----> Cohort 3 probes (P:ID, LOW) ---> Cohorts 0, 1, 2 scan
──────────────────────────────────────────────────────────────────────────────────────────────────────────
8.0s - 9.8s   Broadcast TICK:R2:H0 (HIGH) -----> Cohort 0 probes (P:ID, LOW) ---> Cohorts 1, 2 scan
10.0s-11.8s   Broadcast TICK:R2:H1 (HIGH) -----> Cohort 1 probes (P:ID, LOW) ---> Cohorts 0, 2 scan
12.0s-13.8s   Broadcast TICK:R2:H2 (HIGH) -----> Cohort 2 probes (P:ID, LOW) ---> Cohorts 0, 1 scan
──────────────────────────────────────────────────────────────────────────────────────────────────────────
14.0s-15.2s   Broadcast COLLECT_REPORTS (HIGH) -> All students compile Top-3 preferences (RSSI >= -70 dBm)
15.2s-18.7s   Teacher Scans for Reports <------- Students send R:myID:p1:p2:p3 (HIGH, jitter 50-350ms)
──────────────────────────────────────────────────────────────────────────────────────────────────────────
18.7s         Teacher executes performMeshAudit() (Direct Mutuals + Triadic Closure)
19.0s-22.0s   Teacher broadcasts bulk OK:ID1:ID2:ID3 tokens (HIGH, 1800ms per batch) -> Student screens confirm
```

---

## 5. Byte-Level BLE Payload Specification (Strict 31-Byte Limit)

All communications strictly obey the **31-byte Legacy BLE PDU limit**, guaranteeing compatibility across 100% of Android devices (including budget chipsets without BLE 5.0 Extended Advertising):

```
Total Capacity: 31 Bytes
├── Flags AD Structure: 3 Bytes (0x02, 0x01, 0x06)
├── Service Data Header: 4 Bytes (AD Length, AD Type 0x16, 16-bit UUID 0x1234)
└── Usable Payload: 24 Bytes Maximum
```

### Packet Format Table

| Packet Type | Example Token | Size | Description |
| :--- | :--- | :--- | :--- |
| **Teacher Tick (R1)** | `TICK:R1:G0` | 10 Bytes | Signals Round 1, Cohort 0 to broadcast probes |
| **Teacher Tick (R2)** | `TICK:R2:H1` | 10 Bytes | Signals Round 2, Cohort 1 to broadcast probes |
| **Collect Command** | `COLLECT_REPORTS` | 15 Bytes | Tells students to compile top-3 peers and report |
| **Student Probe** | `P:A4B1` | 6 Bytes | 4-char hex ID broadcast at `TX_POWER_LOW` |
| **Student Report** | `R:A4B1:C2D3:E5F6:7890` | 21 Bytes | Uplink to teacher with up to 3 preferences ($\le 24\text{ B}$) |
| **Bulk Verdict** | `OK:A4B1:C2D3:E5F6:7890` | 23 Bytes | Broadcast by teacher for 1800ms to confirm 4 students at once |

---

## 6. Codebase Architecture & File Responsibilities

```
app/src/main/java/com/attendance/ble/
├── MainActivity.kt                      # Orchestrator, session state machine, radio loop timing
├── ble/
│   ├── BleConstants.kt                  # 16-bit UUID (0x1234) and Android 12-15 runtime permissions
│   ├── BleAdvertiser.kt                 # Compact ServiceData advertiser (omits redundant ServiceUuid)
│   └── BleScanner.kt                    # Low-latency, ServiceData hardware-filtered scanner with null-safety
├── ui/
│   ├── screens/
│   │   └── BleDebuggerScreen.kt         # Jetpack Compose UI: Teacher dashboard, Student view, and audit cards
│   └── theme/
│       └── Color.kt                     # Modern Material 3 color system (Light theme & Cyber palette)
└── viewmodel/
    └── AttendanceViewModel.kt           # State container, cohort hashing, packet dispatcher, Graph Audit Engine
```

### Detailed Component Logic

#### 1. [`AttendanceViewModel.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/viewmodel/AttendanceViewModel.kt)
- **ID Generation:** Generates 4-character hex ID: `java.util.UUID.randomUUID().toString().replace("-", "").take(4).uppercase()`.
- **Cohort Hash:** Computes `cohort4` ($\pmod 4$) and `cohort3` ($\pmod 3$).
- **`performMeshAudit()`:**
  - Step 1: Maps direct mutual pairs ($A \in P(B) \land B \in P(A)$).
  - Step 2: Resolves triadic closure ($P(A)$ contains an already-verified node that has mutuals with the cluster).
  - Step 3: Classifies nodes into `VERIFIED_PRESENT`, `SPOOFER_REJECTED`, or `ISOLATED_FLAGGED`.
- **`onPacketReceived()`:**
  - Dispatches `TICK:`, `P:`, `COLLECT_REPORTS`, `R:`, `OK:`, and `VERDICT:`.
  - Filters neighbor discovery to `medianRssi >= -70 dBm` and takes top 3.

#### 2. [`MainActivity.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/MainActivity.kt)
- **Teacher Orchestration:** `startTeacherMeshSession()` coordinates the 18.7s sequence (Round 1, Round 2, Report Collection).
- **RF Power Control:**
  - `viewModel.onBroadcastProbe`: Transmits at `AdvertiseSettings.ADVERTISE_TX_POWER_LOW` for 1600ms.
  - `viewModel.onBroadcastReport`: Transmits at `AdvertiseSettings.ADVERTISE_TX_POWER_HIGH` with jitter (50–350ms).
- **`broadcastVerdicts()`:** Chunks verified IDs into batches of up to 4 (`OK:ID1:ID2:ID3:ID4`) and broadcasts each chunk for **1800ms** at `ADVERTISE_TX_POWER_HIGH`.

#### 3. [`BleDebuggerScreen.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ui/screens/BleDebuggerScreen.kt)
- **Role Switcher:** Toggle between Teacher and Student.
- **Teacher HUD:** Live metric cards (`Verified`, `Spoofers`, `Isolated`) and the full Classroom Mesh Roster displaying verification reasons and neighbor chips (`↔ ID` for direct mutual, `⤹ Bridge: ID` for triadic closure).
- **Student HUD:** Live Discovered Nearby Peers list showing detected classmates, packet counts, and signal strength.

---

## 7. Security Threat Model & Verification Proofs

| Attack Scenario | Attacker Action | System Defense Mechanism | Result |
| :--- | :--- | :--- | :--- |
| **Hallway Spoofer** | Standing outside closed classroom door, sniffs teacher tick, transmits report claiming interior students. | Concrete/wood wall loss ($>20\text{ dB}$) + `TX_POWER_LOW` student probes ensure no interior student hears the spoofer above $-70\text{ dBm}$. The spoofer appears in 0 interior Top-3 lists. Affinity $S = 0.0$. | **SPOOFER REJECTED** |
| **Far Guy / Distant Classroom Student** | Sitting 5–8m away in an empty corner, attempting to piggyback on another group. | Low probe TX power drops signal below $-85\text{ dBm}$ at 5m. Desk neighbors with immediate Line-of-Sight ($1\text{m}$) dominate preference rankings. The distant student cannot make the Top-3 list. | **ISOLATED / REJECTED** |
| **Replay / Eavesdropping Attack** | Attacker sniffs a classmate's probe (`P:ID`) and claims them as a neighbor. | Reciprocity verification: The legitimate student inside has physical desk neighbors and does not report the distant attacker. Link is unreciprocated ($S = 0.0$). | **SPOOFER REJECTED** |
| **Ghost Student (At Home)** | Absent student receives session start notification via WhatsApp. | The remote phone cannot broadcast physical 2.4 GHz RF packets into the room. 0 packets received by Teacher or peers. | **ABSENT** |
| **Corner Desk Student** | Legitimate student sits at edge desk and gets dropped by one busy center student. | **Triadic Closure ($S = 0.5$):** Edge student shares mutual bridge contacts with the classroom component, preventing false spoofer flags. | **VERIFIED PRESENT** |

---

## 8. Developer Quick-Start & Testing Guide

### 8.1 Build Verification
Compile the debug build using Gradle:
```bash
./gradlew assembleDebug
```
Ensure output is `BUILD SUCCESSFUL` with 0 compilation errors.

### 8.2 Field Testing Protocol
1. Deploy the APK to at least 3 physical Android test devices.
2. Designate **Phone 1 as Teacher** (tap "Switch to Teacher", leave at front desk).
3. Designate **Phones 2 & 3 as Students** (ensure "Listen for Teacher" is active).
4. Arrange Phones 2 & 3 on adjacent desks ($1.0\text{--}1.5\text{m}$ apart).
5. (Optional) Place a 4th phone outside in the hallway behind a closed door to test spoofer rejection.
6. On Phone 1, tap **"Start Mesh Attendance"**.
7. Observe the 18.7s automated sequence:
   - Round 1 and Round 2 discovery ticks execute.
   - Phones 2 & 3 discover each other and report mutual links.
   - At $T = 18.7\text{s}$, Phone 1 displays the audit roster with green `VERIFIED PRESENT` badges.
   - Phones 2 & 3 flip to `✅ ATTENDANCE CONFIRMED (VERIFIED PRESENT)`.
   - The hallway phone outside the door is flagged as `❌ SPOOFER REJECTED`.
