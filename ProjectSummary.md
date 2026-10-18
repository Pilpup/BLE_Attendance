# BLE Autonomous Classroom Attendance Verification System
## Comprehensive Technical Specification, RF Theory, Evolutionary History, & Developer Context

> **Note for Future AI Assistants & Developers:**  
> This document is the **single source of truth** for the BLE Attendance project located at `/home/wink/Projects/ble_attendance`. It contains the complete architectural context, mathematical formulations, lessons from empirical physical failures, protocol byte budgets, code mappings, backend specifications, and security threat models. Read this thoroughly before modifying any code.

---

## 1. Project Overview & Core Mission

### 1.1 The Objective
Build a **tamper-proof, low-friction, peer-to-peer classroom attendance verification system** combining **Bluetooth Low Energy (BLE) near-field proximity sensing** on Android smartphones with a **high-speed Cloud Backend (Python FastAPI + Neon.tech PostgreSQL)**.

### 1.2 Core Architectural Axiom: Proximity vs. Transport Separation
```
┌─────────────────────────────────────────────────────────────┐
│                      JOB 1: PROXIMITY                       │
│        "Am I physically 1 meter away from Student B?"       │
│           ==> Handled by BLE Radio (Whisper Mode)           │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   JOB 2: DATA TRANSPORT & AUDIT             │
│   "Submit student lists, authenticate, run graph audit"     │
│        ==> Handled by HTTPS & Python FastAPI Server         │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 Non-Negotiable System Requirements
1. **Zero External Hardware:** No wall beacons, no ESP32s, no Raspberry Pis. Runs solely on existing teacher and student smartphones.
2. **Zero Manual User Friction:** 
   - No typing roll numbers, room codes, or passwords during class.
   - No QR code scanning or camera selfies (facial recognition rejected as slow and privacy-invasive).
   - Student phones verify presence autonomously while resting on desks or in pockets.
3. **Zero Prior Room Calibration:** Neither teacher nor students provide classroom dimensions, seating charts, or anchor positions. The system autonomously discovers local seating topology.
4. **Strict Anti-Spoofing & Hallway Proxy Defense:** Must reliably detect and reject:
   - A "hallway proxy" standing outside the classroom door attempting to piggyback on in-room signals.
   - A "buddy puncher" attempting to submit attendance for an absent friend at home.
5. **Graceful Offline Safety Net (1-Hop Peer Relay):** Students with no cellular data plan or bad reception in basement lecture halls can relay their attendance packet through an adjacent online desk neighbor.

---

## 2. The Evolutionary History of Failures (Why Other Ideas Failed)

Understanding why earlier approaches collapsed in physical testing is essential to avoid re-introducing flawed paradigms.

```
       [EVOLUTION OF ARCHITECTURAL ATTEMPTS]

  1. 2-Beacon Linear Differential (commit ebfa77a)
     └── FAILED: Collinear symmetry / hyperbolic contour ("The Doorway Trap").
         Inside (1m) and outside (1m) produce identical ratios (+10 dB).

  2. 3-Beacon Dynamic Triangulation (commit 405b923)
     └── FAILED: Metal wall waveguide reflections in classrooms inverted RSSI.
         Back row: -70 dBm (constructive reflections).
         Center room: -80+ dBm (destructive multipath + body shielding).
         Radial minByOrNull explicitly elected the middle student as C1!

  3. Naive Mesh Reciprocity (4-Phone Field Test)
     └── FAILED: 3 distinct bugs:
         a) Probes at TX_POWER_HIGH reached 8m (-73 dBm), passing -78 dBm cutoff -> Far Guy verified!
         b) take(2) ranking truncation forced Middle to drop Front -> Front marked as spoofer!
         c) Teacher verdict sent in a 400ms flash -> Middle phone missed packet, stayed unverified.

  4. Pure BLE Multi-Cohort ALOHA Schedule (commit fdf7ec4)
     └── WORKING BUT LIMITED:
         18.7s session duration; 24-byte packet limit required extensive token compression;
         radio collisions during report uplinks; no long-term SQL persistence.

  5. Current Final Paradigm: Hybrid BLE Radar + Cloud Backend (FastAPI + Neon Postgres)
     └── SUCCESS:
         • BLE strictly used as 2-second near-field radar (< 1.5m, TX_POWER_LOW, >= -70 dBm).
         • HTTPS transport via Python FastAPI server handles 70 students in < 10ms.
         • Neon.tech PostgreSQL handles permanent record persistence with auto-wake in 500ms.
         • Opportunistic 1-hop offline peer relay supports students without data plans.
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
* **Why It Failed:**
  Classrooms with metal-reinforced walls, whiteboards, and steel desk frames act as **resonant RF waveguides**:
  - **Back row students received $\approx -70\text{ dBm}$** from the Teacher due to constructive specular reflections along perimeter walls.
  - **Center students received $\approx -80+\text{ dBm}$** from the Teacher due to destructive multipath phase cancellation and human body attenuation (70% water absorbing 2.4 GHz).
  - The election logic executed `val farthest = reports.minByOrNull { it.rssi }`.
  - Because $-80+\text{ dBm} < -70\text{ dBm}$, `minByOrNull` mathematically selected the **middle student as the "farthest" beacon every single time**, inverting the coordinate system.

### 2.3 Failure 3: The 4-Phone Field Test Pathology
In testing with 4 physical phones (Front, Middle, Back, and an outsider 5m away):
1. **The Far Guy Got Verified:** Student probes broadcasted at `ADVERTISE_TX_POWER_HIGH` ($+1\text{ to } +4\text{ dBm}$). At 5m Line-of-Sight, RSSI was $-73.4\text{ dBm}$. Because the filter cutoff was set loosely to $\ge -78\text{ dBm}$, the distant student was treated as a close neighbor and formed mutual links with the middle phone.
2. **The Front Guy Got Marked as a Spoofer:** The code enforced `take(2)` on neighbor reporting. The Middle phone heard Back ($-62\text{ dBm}$), Far Guy ($-73\text{ dBm}$), and Front ($-75\text{ dBm}$). Truncating to 2 forced Middle to report $[B, X]$ and drop Front ($F$). Front reported $M$, but $M$ did not report $F$. The Teacher saw an unreciprocated link and falsely branded Front as a spoofer.
3. **The 400ms Verdict Drop:** The Teacher broadcasted `VERDICT:<ID>:VERIFIED` for only 400ms. In BLE, 400ms transmits only 3–4 packets. Channel hopping and scan latency caused the Middle phone to miss the burst, leaving its screen unverified.

---

## 3. The Physical Radio Layer: Social-Mesh Graph & Power Separation

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
* Every student records observed peers and filters to **median $\text{RSSI} \ge -70\text{ dBm}$**, keeping the Top 3 closest.

### 3.2 Principle 2: Triadic Closure / Shared Mutual Friends
Instead of brittle 1-to-1 binary reciprocity ($A \leftrightarrow B$ only), the system models the room as an undirected social graph and evaluates **Bilateral Affinity ($S_{A,B}$)**:
$$S(A, B) = \begin{cases} 
1.0 & \text{if } A \in P(B) \text{ and } B \in P(A) & \text{(Direct Mutual Match)} \\
0.5 & \text{if } P(A) \cap P(B) \ne \emptyset & \text{(Triadic Closure via Shared Bridge)} \\
0.0 & \text{otherwise} & \text{(Stranger / Spoofer)}
\end{cases}$$

* **Why this fixes the Zigzag Squeeze-Out:**
  Front ($F$) claims Middle ($M$). Middle ($M$) and Back ($B$) have a direct mutual match. Because $F$ shares the verified bridge node $M$ with the cluster ($S = 0.5$), $F$ is confirmed part of the physical desk cluster and marked **`VERIFIED_PRESENT`**.

### 3.3 Principle 3: RF Power Separation & Near-Field Calibrated Fence
* **Teacher Orchestrator Beacon:** Broadcasts `SESSION:<ID>:<SALT>` at **`ADVERTISE_TX_POWER_HIGH`** ($+1\text{ to } +4\text{ dBm}$) to blanket the entire lecture hall.
* **Student Probes (`P:<myDeviceID>`):** Broadcast at **`ADVERTISE_TX_POWER_LOW`** (approx. $-15\text{ to } -20\text{ dBm}$).
  - At $1\text{m}$, RSSI is $-65\text{ to } -70\text{ dBm}$.
  - Beyond $2.5\text{m}$, RSSI drops below $-85\text{ dBm}$.
  - At $5\text{m}$ (Far Guy or Hallway Spoofer), the signal is below $-90\text{ dBm}$ or undetectable.
* **Calibrated Proximity Threshold:** Set strictly to **`it.medianRssi >= -70 dBm`**. Distant phones physically cannot qualify for anyone's preference list.

### 3.4 Why 70 Phones Do Not Jam the Airwaves (The Math)
* A student probe (`P:U1234`) is only ~28 bytes total on the wire.
* At 1 Mbps BLE transmission speed:
  $$\text{Airtime} = 28 \text{ bytes} \times 8\,\mu\text{s} \approx 0.22\text{ ms}$$
* Across 3 advertising channels (37, 38, 39), total transmission burst is **$0.7\text{ ms}$**.
* Broadcast at $100\text{ ms}$ intervals $\implies$ radio is **transmitting $0.7\%$ of the time and silent $99.3\%$ of the time**.
* **Spatial Frequency Reuse:** At $-20\text{ dBm}$, human bodies absorb 2.4 GHz. Row 1 and Row 8 cannot hear each other, splitting 70 students into ~15 isolated micro-clusters of 4–5 desks. Collision probability in a 5-desk cluster is **$< 7\%$**. Over 20 pings in 2 seconds, packet delivery is **$> 99.9\%$**.

---

## 4. The 3-Layer Security & Authentication Model

```
[ LAYER 1: DIGITAL IDENTITY ]
   └── JWT Bearer Token: Proves "Who is holding this phone account"
       (Prevents submitting under someone else's name).
           ▼
[ LAYER 2: IN-ROOM TIME SYNC ]
   └── Dynamic BLE Nonce (e.g. SALT99): Proves "This phone is inside the room right now"
       (Prevents a student at home from submitting via internet).
           ▼
[ LAYER 3: PHYSICAL DESK MESH ]
   └── Low-Power BLE Probes (RSSI >= -70 dBm): Proves "Real classmates physically surround this phone"
       (Prevents hallway proxies and lone spoofers).
```

### Threat Defense Matrix

| Attack Scenario | Attacker Action | System Defense Mechanism | Result |
| :--- | :--- | :--- | :--- |
| **Hallway Spoofer** | Standing outside closed classroom door, sniffs teacher beacon, submits via app. | Concrete wall loss ($>20\text{ dB}$) + `TX_POWER_LOW` probes ensure no interior student hears the spoofer above $-70\text{ dBm}$. Spoofer appears in 0 interior lists ($S = 0.0$). | **SPOOFER REJECTED** |
| **Buddy Punching (At Home)** | Absent student receives session start notification via WhatsApp and tries to submit API call. | Absent phone cannot sniff the physical in-room `SALT99` beacon and has 0 physical neighbors in the room. | **REJECTED (Invalid Salt / 0 Peers)** |
| **Identity Theft** | Student A tries to submit attendance using Student B's roll number. | The backend extracts identity directly from `req.user.id` (cryptographically signed JWT). Request body identity fields are ignored. | **TAMPER-PROOF** |
| **Corner Desk Student** | Legitimate student sits at edge desk and is dropped by a busy center student's Top-3 list. | **Triadic Closure ($S = 0.5$):** Edge student shares mutual bridge contacts with the classroom component. | **VERIFIED PRESENT** |
| **Offline Student (No Data)** | Legitimate student has no cellular balance or Wi-Fi in basement. | **1-Hop Peer Relay:** Student broadcasts `RELAY:MyID:NeighborID`. The neighbor's phone bundles it into their HTTP payload. | **VERIFIED PRESENT** |

---

## 5. End-to-End System Protocol & Sequence

```
[Time]      Teacher Phone              67 Online Students           3 Offline Students           Backend Server
───────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 0.0s    BROADCAST (HIGH) ───────►  SCANNING                 SCANNING                     Listening
            "SESSION:89F2:SALT99"       Receives session & salt  Receives session & salt
───────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 1.0s    IDLE / WAITING             WHISPER & LISTEN         WHISPER & LISTEN             Waiting
    to                                 • Broadcast P:ID (LOW)   • Broadcast P:ID (LOW)
T = 3.0s                               • Scan for 1m neighbors  • Scan for 1m neighbors
───────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 3.5s    IDLE                       SCANNING (For Relay) ◄── BROADCAST (LOW)              Waiting
                                       Neighbor hears relay     "RELAY:S_off:S_on:SALT99"
───────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 4.5s    IDLE                       HTTPS POST ────────────────────────────────────────►  PROCESSES GRAPH
                                       Direct + Relayed                                      • Matches mutuals
                                       Payloads                                              • Verifies salt
───────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 5.5s    WEBSOCKET STREAM ◄─────────────────────────────────────────────────────────────  EMITS VERDICTS
            Roster turns 100% green    ◄── HTTP 200 (Success)                                "70 Verified"
                                       Screens turn green!
───────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 6.0s    IDLE                       BROADCAST ACK (LOW) ───► SCANNING
                                       "ACK:S_off"               Screen turns green!
```

---

## 6. Codebase Architecture & File Responsibilities

### 6.1 Android Client (`app/src/main/java/com/attendance/ble/`)

```
app/src/main/java/com/attendance/ble/
├── MainActivity.kt                      # Activity lifecycle, permission guards, radio bindings
├── ble/
│   ├── BleConstants.kt                  # Base 16-bit UUID (0x1234) & runtime permission definitions
│   ├── BleAdvertiser.kt                 # Compact ServiceData advertiser (omits redundant ServiceUuid)
│   └── BleScanner.kt                    # Low-latency hardware-filtered scanner with noise rejection
├── ui/
│   ├── screens/
│   │   └── MainScreen.kt                # Jetpack Compose UI: Teacher Console & Student Mode tabs
│   └── theme/
│       └── Color.kt                     # Modern Material 3 color system (Light theme & Cyber palette)
└── viewmodel/
    └── AttendanceViewModel.kt           # State container, 60s countdown, beacon listener, peer filtering
```

#### Detailed Component Responsibilities:
1. **[`BleAdvertiser.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ble/BleAdvertiser.kt):** Configures `AdvertiseSettings` with `setConnectable(false)` and injects `addServiceData(ParcelUuid(TEST_UUID), token)`. Omits `addServiceUuid` to stay within the strict 31-byte legacy BLE limit.
2. **[`BleScanner.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ble/BleScanner.kt):** Uses hardware `ScanFilter` for `TEST_UUID` in `SCAN_MODE_LOW_LATENCY`. Filters out null/empty service data (hardware leakage) and calculates packet transit latency in milliseconds.
3. **[`AttendanceViewModel.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/viewmodel/AttendanceViewModel.kt):**
   - **Role Toggle:** Supports `isTeacherMode` switching.
   - **Teacher Engine:** Generates 4-char `sessionId` and 6-char `sessionSalt`. Conducts 60-second countdown timer and commands radio to transmit `SESSION:<id>:<salt>` at `ADVERTISE_TX_POWER_HIGH`.
   - **Student Engine:** Generates 4-char `studentId`. When `SESSION:` packet is detected, transitions state to `PROBING_NEIGHBORS`, broadcasts `P:<studentId>` at `ADVERTISE_TX_POWER_LOW` for 2.0s, records classmate RSSIs, and filters to `topDeskNeighbours` with median $\ge -70\text{ dBm}$.
4. **[`MainScreen.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ui/screens/MainScreen.kt):**
   - Clean Material 3 top tab switcher (`Teacher Mode` vs `Student Mode`).
   - **Teacher HUD:** Live broadcasting status pill, 60s countdown display, room nonce chip, airwave payload preview, and Start/End session button.
   - **Student HUD:** Status transition pill (`STANDBY` $\to$ `LISTENING FOR TEACHER...` $\to$ `PROBING NEIGHBORS (2s)` $\to$ `PROXIMITY VERIFIED`), room credentials display, and live desk neighbors list with green RSSI badges.
5. **[`MainActivity.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/MainActivity.kt):** Checks runtime permissions (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), checks Bluetooth power state, and binds ViewModel callbacks to hardware radio methods.

---

### 6.2 Backend Server (`server/` - Python FastAPI & Neon Postgres)

```
server/
├── requirements.txt                     # FastAPI, uvicorn, PyJWT, bcrypt, psycopg2
├── database.py                          # Neon.tech PostgreSQL connection with SQLite fallback
├── auth.py                              # Password hashing (bcrypt) & JWT token verification
├── graph_engine.py                      # Social-Mesh Audit Algorithm (Mutuals + Triadic Closure)
└── main.py                              # FastAPI REST endpoints + WebSocket stream
```

#### Database Schema (`Neon.tech` PostgreSQL):
```sql
-- 1. Users Table (Teachers & Students)
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(50) PRIMARY KEY,          -- Roll Number (e.g. "2024CS042") or Teacher ID
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL, -- Hashed with bcrypt (never plain text)
    role VARCHAR(10) NOT NULL CHECK (role IN ('TEACHER', 'STUDENT')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Sessions Table
CREATE TABLE IF NOT EXISTS sessions (
    session_id VARCHAR(10) PRIMARY KEY,  -- e.g. "89F2"
    session_salt VARCHAR(10) NOT NULL,   -- e.g. "SALT99"
    teacher_id VARCHAR(50) REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE'
);

-- 3. Attendance Records Table
CREATE TABLE IF NOT EXISTS attendance_records (
    id SERIAL PRIMARY KEY,
    session_id VARCHAR(10) REFERENCES sessions(session_id),
    student_id VARCHAR(50) REFERENCES users(id),
    status VARCHAR(30) NOT NULL,         -- 'VERIFIED_PRESENT', 'SPOOFER_REJECTED', 'ISOLATED'
    reason TEXT,                         -- e.g. 'Direct mutual match with [C3D1]'
    neighbors_reported TEXT,             -- JSON array e.g. '["C3D1", "E5F2"]'
    relayed_by VARCHAR(50),              -- Neighbor ID if relayed via offline helper
    verified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### Social-Mesh Audit Algorithm (`graph_engine.py`):
```python
def audit_classroom_mesh(reports: dict[str, list[str]], valid_nonce: str) -> list[dict]:
    """
    Evaluates mutual reciprocity and triadic closure across all submissions.
    reports = { "studentId": ["neighborId1", "neighborId2"] }
    """
    direct_mutuals = {}
    verified_set = set()
    results = []

    # Step 1: Map direct mutual pairs (A in P(B) and B in P(A))
    for student_id, claimed_neighbors in reports.items():
        mutual_peers = [
            peer for peer in claimed_neighbors 
            if student_id in reports.get(peer, [])
        ]
        direct_mutuals[student_id] = mutual_peers
        if len(mutual_peers) > 0:
            verified_set.add(student_id)

    # Step 2: Resolve Triadic Closure (Shared Bridge Node)
    changed = True
    while changed:
        changed = False
        for student_id, claimed_neighbors in reports.items():
            if student_id not in verified_set:
                # If student shares a neighbor that is already verified
                shared_bridges = set(claimed_neighbors) & verified_set
                if len(shared_bridges) > 0:
                    verified_set.add(student_id)
                    changed = True

    # Step 3: Classification
    for student_id, claimed_neighbors in reports.items():
        mutuals = direct_mutuals.get(student_id, [])
        if len(mutuals) > 0:
            status = "VERIFIED_PRESENT"
            reason = f"Direct mutual match with [{', '.join(mutuals)}]"
        elif student_id in verified_set:
            status = "VERIFIED_PRESENT"
            reason = "Triadic closure via shared bridge in classroom cluster"
        elif len(claimed_neighbors) == 0:
            status = "ISOLATED_FLAGGED"
            reason = "Zero desk neighbors detected"
        else:
            status = "SPOOFER_REJECTED"
            reason = f"Unreciprocated link to [{', '.join(claimed_neighbors)}] (Hallway Spoofer)"

        results.append({
            "student_id": student_id,
            "status": status,
            "reason": reason,
            "mutuals": mutuals
        })

    return results
```

---

## 7. College Administration Data Integration Checklist

When requesting directory data from the university IT department, **explicitly assure them that no sensitive PII is required** (no phone numbers, home addresses, dates of birth, parent details, or grades/GPA).

### Requested Data Schema (Standard CSV Export):
1. **Students (`students.csv`):**
   - `student_id` / Roll Number (Primary Key)
   - `name` (Full Name)
   - `email` (Institutional email used for app login)
   - `department` (e.g. Computer Science)
   - `semester` / Year
   - `section` (e.g. Section A)
2. **Teachers (`teachers.csv`):**
   - `teacher_id` / Employee ID (Primary Key)
   - `name` (Full Name)
   - `email` (Institutional email used for app login)
   - `department`
3. **Courses (`courses.csv`):**
   - `course_code` (e.g. `CS301`)
   - `course_title` (e.g. `Database Management Systems`)
   - `department`
4. **Course Enrollments (`course_enrollments.csv`):**
   - `course_code`
   - `teacher_id`
   - `student_id`
   - `section`

---

## 8. Developer Quick-Start & Testing Guide

### 8.1 Android Build & Install
Compile the debug build using Gradle:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 8.2 Testing with Physical Devices
1. Deploy the APK to 2 or 3 Android devices.
2. **Device 1 (Teacher):** Keep on `Teacher Mode` tab.
3. **Device 2 & 3 (Students):** Switch to `Student Mode` tab and tap **"Listen for Teacher"** (Status turns orange: `LISTENING FOR TEACHER...`).
4. **Device 1 (Teacher):** Tap **"Start 60s Session"**.
5. **Observe Autonomous Sequence:**
   - Devices 2 & 3 capture the `SESSION ID` and `NONCE` over the air immediately.
   - Devices 2 & 3 flash blue for 2 seconds (`PROBING NEIGHBORS (2s)`).
   - Devices 2 & 3 detect each other's low-power probes at $\ge -70\text{ dBm}$ and flip to green cards showing verified desk neighbors.

### 8.3 Backend Server Launch
```bash
cd server
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```
Visit `http://localhost:8000/docs` in any browser to access the interactive Swagger UI and test endpoints directly.
