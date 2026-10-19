# BLE Autonomous Classroom Attendance Verification System
## Comprehensive Technical Specification, RF Theory, Evolutionary History, & Developer Context

> **Note for Future AI Assistants & Developers:**  
> This document is the **single source of truth** for the BLE Attendance project located at `/home/wink/Projects/ble_attendance`. It contains the complete architectural context, mathematical formulations, lessons from empirical physical failures, protocol byte budgets, code mappings, backend specifications, and security threat models. Read this thoroughly before modifying any code.

---

## 1. Project Overview & Core Mission

### 1.1 The Objective
Build a **tamper-proof, low-friction, peer-to-peer classroom attendance verification system** combining **Bluetooth Low Energy (BLE) near-field proximity sensing** on Android smartphones with a **high-speed Cloud Backend (Python FastAPI + Neon.tech Serverless PostgreSQL)**.

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
   - No QR code scanning or camera selfies (facial recognition rejected as slow, easily spoofed by photos, and privacy-invasive).
   - Student phones verify presence autonomously while resting on desks or in pockets.
3. **Zero Prior Room Calibration:** Neither teacher nor students provide classroom dimensions, seating charts, or anchor positions. The system autonomously discovers local seating topology.
4. **Strict Anti-Spoofing & Hallway Proxy Defense:** Must reliably detect and reject:
   - A "hallway proxy" standing outside the classroom door attempting to piggyback on in-room signals.
   - A "buddy puncher" attempting to submit attendance for an absent friend at home.
5. **Graceful Offline Safety Net (1-Hop Peer Relay):** Students with no cellular data plan or bad reception in basement lecture halls can relay their attendance packet through an adjacent online desk neighbor.
6. **Strict Role Isolation:**
   - **Teachers** only orchestrate: create courses, broadcast in-room rolling nonces, trigger cloud audits, and view/export finalized attendance. Teachers **never scan** for classroom beacons or student probes.
   - **Students** only attend: listen for teacher beacons, probe desk neighbors, and record attendance. Students **never access** the teacher console.
   - Role privileges are enforced automatically via JWT authentication (`role == "TEACHER"`). There is no manual role switcher.

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
         a) Probes at TX_POWER_HIGH reached 8m (-73 dBm), passing loose cutoffs -> Far Guy verified!
         b) take(2) ranking truncation forced Middle to drop Front -> Front marked as spoofer!
         c) Teacher verdict sent in a 400ms flash -> Middle phone missed packet, stayed unverified.

  4. Pure BLE Multi-Cohort ALOHA Schedule (commit fdf7ec4)
     └── WORKING BUT LIMITED:
         18.7s session duration; 24-byte packet limit required extensive token compression;
         radio collisions during report uplinks; no long-term SQL persistence.

  5. Current Final Paradigm: Hybrid BLE Radar + Cloud Backend (FastAPI + Neon Postgres)
     └── SUCCESS:
         • BLE strictly used as 5-second near-field radar (< 2.5m, TX_POWER_LOW, >= -85 dBm).
         • HTTPS transport via Python FastAPI server handles 100+ students in < 15ms.
         • Neon.tech PostgreSQL handles permanent record persistence with auto-schema sync.
         • Opportunistic 1-hop offline peer relay supports students without data plans.
         • Strict role locking prevents teachers from scanning or students from hijacking.
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
2. **The Front Guy Got Marked as a Spoofer:** The code enforced `take(2)` on neighbor reporting. The Middle phone heard Back ($-62\text{ dBm}$), Far Guy ($-73\text{ dBm}$), and Front ($-75\text{ dBm}$). Truncating to 2 forced Middle to drop Front ($F$). Front reported $M$, but $M$ did not report $F$. The Teacher saw an unreciprocated link and falsely branded Front as a spoofer.
3. **The 400ms Verdict Drop:** The Teacher broadcasted `VERDICT:<ID>:VERIFIED` for only 400ms. In BLE, 400ms transmits only 3–4 packets. Channel hopping and scan latency caused the Middle phone to miss the burst, leaving its screen unverified.

---

## 3. The Physical Radio Layer: Social-Mesh Graph & Power Separation

The system discards absolute distance calculations in favor of **Ordinal Preference Ranking (Top-3 Matches)** combined with **Social-Graph Triadic Closure (Mutual Friends)**.

```
       [FRONT (F)] ─────── (1.2m) ─────── [MIDDLE (M)] ─────── (1.2m) ─────── [BACK (B)]
                                                  │
                                          (5m) [TX_POWER_LOW: Signal < -90 dBm]
                                                  │
                                            [FAR GUY (X)]
                                        (Outside Proximity Fence)
```

### 3.1 Principle 1: Ordinal Preference Ranking (Hardware-Gain Invariance)
* **The Problem:** Phone antennas vary by $\pm 10\text{ dB}$. A flagship phone with an LNA receiver measures signals louder than a budget plastic phone.
* **The Solution:** Ranking is an **ordinal invariant**. Regardless of whether a receiving antenna has $+5\text{ dB}$ or $-5\text{ dB}$ gain, the desk neighbor sitting $1\text{ meter}$ away will **always rank higher** than a student sitting 5 meters away.
* Every student records observed peers and filters to **median $\text{RSSI} \ge -85\text{ dBm}$**, keeping the Top 3 closest.

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
* **Teacher Orchestrator Beacon:** Broadcasts `S:<SESSION_ID>:<SALT>` (13 bytes) at **`ADVERTISE_TX_POWER_HIGH`** ($+1\text{ to } +4\text{ dBm}$) to blanket the lecture hall.
* **Student Probes:** Broadcast at **`ADVERTISE_TX_POWER_LOW`** (approx. $-15\text{ to } -20\text{ dBm}$) for **5 seconds** using the 8-byte fixed-width format `[SESSION_ID (4)][SELF_ID (4)]`.
  - At $1\text{m}$, RSSI is $-60\text{ to } -72\text{ dBm}$.
  - Beyond $2.5\text{m}$, RSSI drops below $-85\text{ dBm}$.
  - At $5\text{m}$ (Far Guy or Hallway Spoofer), signal drops below $-90\text{ dBm}$ or fails to be detected.
* **Calibrated Proximity Threshold:** Set strictly to **`medianRssi >= -85 dBm`**. Distant phones outside the classroom physically cannot qualify for anyone's preference list.

### 3.4 The Fixed-Width 24-Byte Packet Protocol
Legacy BLE advertising reserves 7 bytes for headers, leaving **strictly 24 bytes** of user payload. We eliminate string splitters and delimiters, using positional fixed-width encoding:

| Packet Type | Wire Format | Byte Size | Transmitter | Power Level | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Teacher Beacon** | `S:<SESSION_ID>:<SALT>` | **13 bytes** | Teacher | `TX_POWER_HIGH` | Starts room window, syncs time OTP |
| **Student Probe** | `[SESSION_ID (4)][SELF_ID (4)]` | **8 bytes** | Student | `TX_POWER_LOW` | 5-second near-field desk whisper |
| **Offline Relay Voucher** | `[SESSION_ID (4)][SELF_ID (4)][N1 (4)][N2 (4)][N3 (4)]` | **12–20 bytes** | Offline Student | `TX_POWER_HIGH` | Passes mutual proof to online neighbors |
| **Teacher Air Verdict** | `V:<SELF_ID (4)>:OK` | **11 bytes** | Teacher | `TX_POWER_HIGH` | Broadcasts verified list to offline phones |

> [!NOTE]
> Even with an offline student, self ID, and all 3 desk neighbors packed together, the total size is **20 bytes**, maintaining a **4-byte hardware safety margin** under the 24-byte ceiling.

### 3.5 Cross-Classroom Isolation & Collision Defense
To guarantee adjacent classrooms (e.g. CS501 in Room 101 and CS502 in Room 102) do not bleed into each other through drywall:
1. **Radio Gate (0ms Packet Drop):** Every probe and voucher begins with the 4-char `SESSION_ID`. If an incoming radio wave does not match the active session ID, the phone's scanner drops it in 0ms before it ever reaches candidate lists.
2. **Server Gate (Course Roster Validation):** When reports arrive at the FastAPI backend, the server matches all IDs against the course enrollment roster. Foreign students from another section cannot be credited with attendance.

---

## 4. The 3-Layer Security & Authentication Model

```
[ LAYER 1: DIGITAL IDENTITY ]
   └── JWT Bearer Token: Proves "Who is holding this phone account"
       (Prevents submitting under someone else's name).
           ▼
[ LAYER 2: IN-ROOM TIME SYNC ]
   └── Dynamic BLE Nonce (e.g. SALT): Proves "This phone is inside the room right now"
       (Prevents a student at home from submitting via internet).
           ▼
[ LAYER 3: PHYSICAL DESK MESH ]
   └── Low-Power BLE Probes (RSSI >= -85 dBm): Proves "Real classmates physically surround this phone"
       (Prevents hallway proxies, foreign-room bleeds, and lone spoofers).
```

### Threat Defense Matrix

| Attack Scenario | Attacker Action | System Defense Mechanism | Result |
| :--- | :--- | :--- | :--- |
| **Hallway Spoofer** | Standing outside door, sniffs beacon, submits via app. | Concrete wall loss ($>20\text{ dB}$) + `TX_POWER_LOW` ensures no interior student hears spoofer $\ge -85\text{ dBm}$. Appears in 0 interior lists ($S = 0.0$). | **SPOOFER REJECTED** |
| **Buddy Punching (At Home)** | Absent student receives session start notification on WhatsApp and tries to submit API call. | Absent phone cannot sniff in-room `SALT` beacon and has 0 physical neighbors in the room. | **REJECTED (Invalid Salt / 0 Peers)** |
| **Cross-Classroom Bleed** | Student in Room 102 hears whispers from Room 101 through thin wall. | **Session ID Prefix Filter:** Room 101 ignores all packets not starting with Room 101's 4-char session ID. | **DROPPED IN 0ms** |
| **Identity Theft** | Student A tries to submit attendance using Student B's roll number. | Backend extracts identity directly from `req.user.id` (cryptographically signed JWT). Body overrides are ignored. | **TAMPER-PROOF** |
| **Corner Desk Student** | Edge student dropped by a busy neighbor's Top-3 list. | **Triadic Closure ($S = 0.5$):** Edge student shares mutual bridge contacts with the classroom component. | **VERIFIED PRESENT** |
| **Offline Student (No Data)** | Student has no cellular balance or Wi-Fi in lecture hall. | **1-Hop Peer Relay:** Student broadcasts 20-byte voucher `[SESS][SELF][N1][N2][N3]`. Online neighbor uploads it. Teacher broadcasts `V:[SELF]:OK` to turn student screen green. | **VERIFIED PRESENT** |

---

## 5. End-to-End System Protocol & Sequence

```
[Time]       Teacher Phone              Online Students (Wi-Fi/5G)   Offline Students (No Data)   Backend Server
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 0.0s     BROADCAST (HIGH) ───────►  SCANNING                     SCANNING                     Listening
             "S:89F2:9381" (13B)         Receives session & salt      Receives session & salt
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 1.0s     IDLE / BROADCASTING        WHISPER PROBE (LOW)          WHISPER PROBE (LOW)          Waiting
    to                                  • Broadcast "89F2A4B1" (8B)  • Broadcast "89F2C2D3" (8B)
T = 6.0s     (60s Window Running)       • Scan for 1m desk neighbors • Scan for 1m desk neighbors
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 6.2s     IDLE                       SCANNING (For Relay)  ◄───── BROADCAST VOUCHER (HIGH)     Waiting
                                        Neighbor catches voucher     "89F2C2D3A4B1E5F6" (16B)
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 7.0s     IDLE                       HTTPS POST ─────────────────────────────────────────────► PROCESSES GRAPH
                                        Direct + Relayed Vouchers                                • Direct Mutuals
                                        Payloads via API                                         • Triadic Closure
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 60.0s    TAPS "End & Audit" ───────►TRIGGER AUDIT ──────────────────────────────────────────► FINALIZES AUDIT
             (Or timer expires)         Session finalized in cloud                               • Writes to Neon
─────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 60.5s    AUDIT COMPLETE ◄──────────────────────────────────────────────────────────────────── RETURNS RESULTS
             • Metric Dashboard (N)                                                              "Total Verified"
             • Full Scrollable List                                                              "Total Spoofers"
             • Export CSV / New Session
```

---

## 6. Codebase Architecture & File Responsibilities

### 6.1 Android Client (`app/src/main/java/com/attendance/ble/`)

```
app/src/main/java/com/attendance/ble/
├── MainActivity.kt                      # Activity lifecycle, permission guards, radio bindings, onResetSession
├── ble/
│   ├── BleConstants.kt                  # Base 16-bit UUID (0x1234) & runtime permission definitions
│   ├── BleAdvertiser.kt                 # Compact ServiceData advertiser (omits redundant ServiceUuid)
│   └── BleScanner.kt                    # Low-latency hardware-filtered scanner with noise rejection
├── network/
│   ├── ApiClient.kt                     # Retrofit instance with OkHttp logging (Render Cloud Backend)
│   ├── AttendanceApiService.kt          # REST API interface (Auth, Courses, Sessions, Audits, Submissions)
│   └── NetworkModels.kt                 # Pydantic-mirrored data classes (AttendanceRecordItem, Session, etc.)
├── ui/
│   ├── screens/
│   │   └── MainScreen.kt                # Jetpack Compose UI: Strict role-based Teacher & Student views
│   └── theme/
│       └── Color.kt                     # Modern Material 3 color system (Light theme & Cyber palette)
└── viewmodel/
    └── AttendanceViewModel.kt           # State container, role guard, 60s countdown, 5s probe window, cloud sync
```

#### Detailed Component Responsibilities:
1. **[`BleAdvertiser.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ble/BleAdvertiser.kt):** Configures `AdvertiseSettings` with `setConnectable(false)` and injects `addServiceData(ParcelUuid(TEST_UUID), token)`. Omits `addServiceUuid` to stay within the strict 31-byte legacy BLE limit.
2. **[`BleScanner.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ble/BleScanner.kt):** Uses hardware `ScanFilter` for `TEST_UUID` in `SCAN_MODE_LOW_LATENCY`. Filters out null/empty service data (hardware leakage) and calculates packet transit latency in milliseconds.
3. **[`AttendanceViewModel.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/viewmodel/AttendanceViewModel.kt):**
   - **Strict Role Guard:** `isTeacherMode = (currentUserRole == "TEACHER" && authToken != null)`. Non-teachers cannot enter teacher mode. Logging out immediately resets `isTeacherMode = false`.
   - **Session Reset:** `resetSession()` cancels any active timer, stops advertising, clears `finalizedRecords`, and clears status messages to start a clean new session.
   - **Teacher Engine:** Generates 4-char `sessionId` and 6-char `sessionSalt`. Conducts 60-second countdown timer and commands radio to transmit `S:<sessionId>:<sessionSalt>` at `ADVERTISE_TX_POWER_HIGH`.
   - **Student Engine:** Generates 4-char `studentShortId`. When `S:` beacon packet is detected, transitions state to `PROBING_NEIGHBORS`, broadcasts `[SESSION][SELF]` at `ADVERTISE_TX_POWER_LOW` for 5.0s, records classmate RSSIs, and filters to `topDeskNeighbours` with median $\ge -85\text{ dBm}$. Automatically submits neighbor graph to FastAPI backend upon probe completion.
4. **[`MainScreen.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/ui/screens/MainScreen.kt):**
   - **Strict Role-Locking:** There is **NO manual tab switcher**. Interface is locked strictly based on JWT authentication (`role == "TEACHER"`):
     - Teachers only see `TeacherSection`. Zero scanning buttons or options.
     - Students/Guests only see `StudentSection`.
   - **TopAppBar:** Shows live account privilege badge (`TEACHER` in purple, `STUDENT` in blue, `GUEST`), roll number/name, account settings dialog button, and Bluetooth permissions indicator.
   - **Teacher Section (Two Distinct Responsive Modes):**
     - *Setup/Broadcasting Mode (`finalizedRecords.isEmpty()`):* Course selector chips with "+ New Course" dialog, live broadcast hero card (pulsing dot, 60s countdown timer, monospace session token and in-room nonce), live BLE advertiser telemetry, cloud status sync banner, and tactile "Start 60s Session" / "End Session & Run Cloud Audit" button.
     - *Audit Results Mode (`finalizedRecords.isNotEmpty()`):* Collapses top card into a compact summary dashboard (Session ID, Course Code, "SAVED TO CLOUD" badge), 3 key metric cards (**Verified Present** in green, **Peer Witnessed** in blue, **Flagged/Spoofers** in red), quick actions ("Export CSV" and "+ New Session"), filter chips (`All`, `Verified`, `Witnessed`, `Flagged`), and a full-height scrollable `LazyColumn` showing student cards (avatar initials, monospace roll number, verification tag, affinity percentage score, remarks).
   - **Student Section:**
     - Dynamic hero status card with state-specific color coding (`STANDBY`, `LISTENING_FOR_TEACHER` in warm amber, `PROBING_NEIGHBORS` with electric blue countdown, and `VERIFIED_PRESENT` / `DISCOVERY_COMPLETE` with emerald green checkmark card).
     - Discovered desk neighbors telemetry list: RSSI signal meters (`📶 -64 dBm`, green for $\ge -75\text{ dBm}$, orange for $\ge -85\text{ dBm}$), packet counts, roll numbers.
     - Bottom Action Button: "Listen for Teacher" / "Stop Listening".
   - **Auth / Server Dialog (`AuthServerDialog`):** Full account settings, sign in, register (with Student vs Teacher role selection and faculty passcode), local roll number update, and custom backend URL config.
5. **[`MainActivity.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/MainActivity.kt):** Checks runtime permissions (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), checks Bluetooth power state, wires `onResetSession`, and binds ViewModel callbacks to hardware radio methods.

---

### 6.2 Backend Server (`backend/` - Python FastAPI & Neon Postgres)

```
backend/
├── requirements.txt                     # FastAPI, uvicorn, SQLAlchemy (asyncio), asyncpg, PyJWT, bcrypt
├── .env.example                         # Neon PostgreSQL connection template & JWT secrets
├── app/
│   ├── config.py                        # Pydantic Settings & environment config
│   ├── database.py                      # Neon asyncpg engine with PgBouncer pooling
│   ├── main.py                          # FastAPI app, CORS, router mounts & dynamic schema migration
│   ├── core/
│   │   └── security.py                  # Bcrypt hashing, JWT generation, & FastAPI auth dependencies
│   ├── models/
│   │   └── attendance.py                # SQLAlchemy ORM: User, Course, AttendanceSession, AttendanceRecord
│   ├── routers/
│   │   ├── auth.py                      # Register, Login, & User Profile endpoints
│   │   └── attendance.py                # Courses, Session lifecycle, & Mesh Audit endpoints
│   ├── schemas/
│   │   ├── auth.py                      # Pydantic request & response models for authentication
│   │   └── attendance.py                # Pydantic models for sessions, records, & mesh audits
│   └── services/
│       └── graph_engine.py              # Social-Mesh Audit Algorithm (Mutuals + Triadic Closure)
└── tests/
    └── test_backend.py                  # Pytest verification suite
```

#### Production Backend Deployment:
* **Hosting Platform:** Render Web Service (FastAPI running on Python 3.11 with Uvicorn).
* **Live API URL:** `https://ble-attendance-api-dbf1.onrender.com/`
* **Database Platform:** Neon.tech Serverless PostgreSQL (auto-scaling compute with connection pooling).
* **Automatic Startup Schema Migration:** `backend/app/main.py` runs `SCHEMA_SYNC_STATEMENTS` at startup to automatically ensure all columns (`reported_neighbors`, `remarks`, `affinity_score`, `total_enrolled`, `session_token`, etc.) are created safely on the Neon database without dropping existing records.

#### Database Schema (`Neon.tech` PostgreSQL):
```sql
-- 1. Users Table (Teachers & Students)
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(50) PRIMARY KEY,          -- Roll Number (e.g. "2024CS042") or Teacher ID
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL, -- Hashed with bcrypt (never plain text)
    role VARCHAR(10) NOT NULL CHECK (role IN ('TEACHER', 'STUDENT')),
    department VARCHAR(50),
    roll_number VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Courses Table
CREATE TABLE IF NOT EXISTS courses (
    id VARCHAR(50) PRIMARY KEY,
    course_code VARCHAR(20) NOT NULL,
    course_name VARCHAR(100) NOT NULL,
    teacher_id VARCHAR(50) REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. Sessions Table
CREATE TABLE IF NOT EXISTS attendance_sessions (
    id VARCHAR(50) PRIMARY KEY,
    course_id VARCHAR(50) REFERENCES courses(id),
    session_token VARCHAR(50) NOT NULL,
    total_enrolled INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    total_verified INT DEFAULT 0,
    total_spoofers INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP
);

-- 4. Attendance Records Table
CREATE TABLE IF NOT EXISTS attendance_records (
    id VARCHAR(50) PRIMARY KEY,
    session_id VARCHAR(50) REFERENCES attendance_sessions(id),
    student_id VARCHAR(50) REFERENCES users(id),
    ble_device_id VARCHAR(50),
    status VARCHAR(30) NOT NULL,         -- 'VERIFIED_PRESENT', 'FLAGGED_SPOOFER', 'ISOLATED'
    verification_method VARCHAR(50),     -- 'PEER_WITNESS', 'DIRECT_PROXIMITY', 'ISOLATED_REJECTED'
    affinity_score DOUBLE PRECISION DEFAULT 0.0,
    reported_neighbors TEXT,             -- JSON string array e.g. '["C3D1", "E5F2"]'
    remarks TEXT,                        -- Diagnostic string e.g. 'Mutual witness with 2 peers'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

    # Step 3: Classification & Affinity Scoring
    for student_id, claimed_neighbors in reports.items():
        mutuals = direct_mutuals.get(student_id, [])
        if len(mutuals) > 0:
            status = "VERIFIED_PRESENT"
            method = "PEER_WITNESS"
            score = 1.0
            reason = f"Mutual witness with {len(mutuals)} peer(s): [{', '.join(mutuals)}]"
        elif student_id in verified_set:
            status = "VERIFIED_PRESENT"
            method = "TRIADIC_CLOSURE"
            score = 0.5
            reason = "Triadic closure via shared bridge in classroom cluster"
        elif len(claimed_neighbors) == 0:
            status = "ISOLATED_FLAGGED"
            method = "ISOLATED_REJECTED"
            score = 0.0
            reason = "Zero desk neighbors detected"
        else:
            status = "FLAGGED_SPOOFER"
            method = "UNRECIPROCATED"
            score = 0.0
            reason = f"Unreciprocated link to [{', '.join(claimed_neighbors)}] (Hallway Spoofer)"

        results.append({
            "student_id": student_id,
            "status": status,
            "verification_method": method,
            "affinity_score": score,
            "remarks": reason,
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
2. **Device 1 (Teacher):**
   - Tap top-right **"ID / Server"** button $\to$ Log in with a **Teacher** account (or Register with Role = Teacher using faculty code).
   - The UI automatically locks into **Teacher Console** (zero scan buttons, no student switcher).
   - Select a Course chip (or tap "+ New Course" to add one, e.g. `CS101`).
   - Tap **"Start 60s Session"**. The phone immediately begins broadcasting the encrypted in-room rolling beacon at `TX_POWER_HIGH`.
3. **Devices 2 & 3 (Students):**
   - Open app $\to$ Defaults to **Student Attendance** (or log in with Student accounts).
   - Tap **"Listen for Teacher"** (Status turns orange: `LISTENING FOR TEACHER...`).
4. **Observe Autonomous Mesh Discovery Sequence:**
   - Devices 2 & 3 capture the `SESSION ID` and `ROOM NONCE` over the air within 500ms.
   - Devices 2 & 3 flash electric blue for 5 seconds (`DISCOVERING PEERS (5s)`).
   - Devices 2 & 3 whisper short-range probes at `TX_POWER_LOW`, detect each other at $\ge -85\text{ dBm}$, and display desk neighbors with signal strength tags (`-64 dBm`).
   - Devices 2 & 3 submit their neighbor reports to the Render cloud backend via HTTPS and transition to green cards: **"ATTENDANCE CONFIRMED"**.
5. **Teacher Cloud Audit & Results View:**
   - On Device 1 (Teacher), tap **"End Session & Run Cloud Audit"** (or let the 60s timer expire).
   - The server audits the mesh graph, confirms mutual witnessing, and updates Neon PostgreSQL.
   - The Teacher screen collapses the broadcast hero card into a compact 3-metric dashboard (**Verified Present**, **Peer Witnessed**, **Flagged Spoofers**).
   - The full viewport displays a scrollable `LazyColumn` of verified student cards with roll numbers, affinity scores, and audit remarks.
   - Tap **"Export CSV"** to share the attendance sheet or **"+ New Session"** to clear and start fresh.

### 8.3 Backend Server Launch (Local Development)
```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```
Visit `http://localhost:8000/docs` in any browser to access the interactive Swagger UI and test endpoints directly.
