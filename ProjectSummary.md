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

### 3.2 Principle 2: Triadic Closure / Shared Mutual Friends & Pure In-Degree Peer Witnessing
Instead of brittle 1-to-1 binary reciprocity ($A \leftrightarrow B$ only) or fragile 1-hop multi-packet relay broadcasting, the system models the room as a social graph and evaluates **Bilateral Affinity ($S_{A,B}$)** and **Directed In-Degree Witnessing**:
$$S(A, B) = \begin{cases} 
1.00 & \text{if } A \in P(B) \text{ and } B \in P(A) & \text{(Direct Mutual Match)} \\
0.85 & \text{if witnessed by } \ge 2 \text{ verified peers} & \text{(Pure Graph In-Degree Peer Witness)} \\
0.50 & \text{if } |P(A) \cap \text{Verified}| \ge 2 & \text{(Triadic Closure via } \ge 2 \text{ Shared Bridges)} \\
0.00 & \text{if witnessed by } \le 1 \text{ verified peer} & \text{(Flagged for Teacher Manual Override)} \\
0.00 & \text{otherwise} & \text{(Isolated Node / Potential Hallway Spoofer)}
\end{cases}$$

* **Pure In-Degree Graph Witnessing for Offline Students:**
  Rather than requiring offline phones to switch radio modes and broadcast multi-hop relay packets (which cause radio collisions and timing sync failures), every phone (online or offline) broadcasts the exact same uniform 8-byte whisper probe `[SESSION][SELF]` at `ADVERTISE_TX_POWER_LOW`.
  Online desk neighbors within $2.5\text{m}$ naturally detect the offline student's whisper at $\ge -85\text{ dBm}$ and include them in their top-3 candidate lists uploaded to the cloud.
  When the backend executes the graph audit, any student with no direct HTTP upload who has an in-degree from **$\ge 2$ independent verified classmates** is automatically granted **`VERIFIED_PRESENT`** with verification method **`PEER_WITNESS`** ($0.85$ affinity).
* **Single-Witness Corner Defense:**
  If an offline student was witnessed by only 1 verified classmate (e.g., sitting on a perimeter corner desk where only 1 neighbor is online), the system classifies them as **`ISOLATED_FLAGGED`** ($0.00$ affinity) to prevent single-witness buddy punching. The teacher simply confirms their seating visually and uses the **Manual Override** control (`PATCH /api/v1/attendance/records/{record_id}/override`) to mark them verified.

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
| **Student Whisper Probe** | `[SESSION_ID (4)][SELF_ID (4)]` | **8 bytes** | Student (Online & Offline) | `TX_POWER_LOW` | 5-second near-field desk whisper |
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
| **Corner / Edge Student (1 Neighbor)** | Edge student with only 1 adjacent neighbor or isolated node. | **Teacher Manual Intervention:** Surfaced in Teacher Dashboard as flagged/isolated; instructor confirms in-person seating and marks verified via `PATCH /records/{record_id}/override`. | **VERIFIED VIA TEACHER OVERRIDE** |
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

### 6.1 Android Client — Multi-Module Gradle Tree (6 Modules)

Since the staged refactor (Steps 1–5), the client is a six-module Gradle
project. Feature modules never depend on each other; `:app` is the only
module that sees every feature (the assembler).

```
ble_attendance/
├── app/src/main/java/com/attendance/ble/                    # the shell & assembler
│   ├── AndroidManifest.xml                                  # INTERNET only — BLE perms merge in from :feature:attendance
│   ├── MainActivity.kt                                      # thin host: BLE permission gate, radio bridge, CSV share
│   ├── ui/theme/Color.kt                                    # shell palette
│   └── shell/
│       ├── AppNavigation.kt                                 # destination routing; registers every DashboardCard once
│       ├── HomeScreen.kt                                    # profile header, dev mock role-switcher, registry card loop
│       └── MockUsers.kt                                     # 1-tap mock sessions (alice/profSmith/sarah/alex/sam)
├── core/model/src/main/java/com/college/core/model/         # zero-dependency contracts
│   ├── Role.kt          # STUDENT(1) STAFF(2) TEACHER(3) DEVELOPER(4); capability helpers; fromBackendName()
│   ├── User.kt          # primaryRole = maxBy(priority); hasRole/canTakeAttendance/canAccessStaffTools/isDeveloper
│   └── SessionState.kt  # LoggedOut / Loading / Authenticated(user, token)
├── core/network/src/main/java/com/college/core/network/
│   ├── SessionManager.kt # StateFlow holder; companion `default`; startSession/endSession/markLoading
│   └── AuthInterceptor.kt# Bearer injection; 401 → endSession (only when a token was sent)
├── core/ui/src/main/java/com/college/core/ui/
│   ├── DashboardCard.kt      # id/priority/NAV_ROUTE/isVisibleFor + Content contract
│   └── DashboardRegistry.kt  # dedupe-by-id registry, cardsFor(user)
├── feature/attendance/src/main/java/com/college/feature/attendance/
│   ├── AndroidManifest.xml                   # ALL BLE/location permissions; merged into :app by AGP
│   ├── ble/{BleAdvertiser,BleScanner,BleConstants}.kt    # hardware engine (migrated Step 4A)
│   ├── AttendanceScreen.kt                   # full attendance UI (migrated from old :app MainScreen), role-adaptive
│   ├── AttendanceDashboardCard.kt            # home card (hidden for pure STAFF)
│   ├── network/{AttendanceApiService,NetworkModels,AttendanceApiClient}.kt  # NO manual @Header auth
│   ├── viewmodel/AttendanceViewModel.kt      # session-beacon engine; identity from SessionManager
│   └── ui/theme/Color.kt                     # module-local palette (→ :core:ui later)
└── feature/library/src/main/java/com/college/feature/library/
    ├── model/{Book,LibraryTab}.kt            # Book (isAvailable); CATALOGUE/STAFF_INVENTORY/DEV_MAINTENANCE
    ├── network/{LibraryApiService(+DTOs),LibraryApiClient}.kt    # /api/v1/library routes pending
    ├── LibraryScreen.kt                      # 3-tier UI: Catalogue, Staff Inventory, Dev Maintenance
    ├── LibraryDashboardCard.kt               # LIVE subtitle: "N books catalogued · M available"
    ├── viewmodel/LibraryViewModel.kt         # role gates via SessionManager; LibraryCatalogStore object
    └── ui/theme/Color.kt                     # module-local palette (→ :core:ui later)
```

#### Detailed Component Responsibilities:
1. **[`BleAdvertiser.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/ble/BleAdvertiser.kt) / [`BleScanner.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/ble/BleScanner.kt) (`:feature:attendance`):** Compact service-data advertiser (“omits redundant ServiceUuid”, 31-byte budget) and low-latency `ScanFilter` scanner with noise rejection and packet-latency measurement. Migrated into the feature module in Step 4A; ALL Bluetooth/location permissions ship with its `AndroidManifest.xml` and are merged into `:app` by AGP.
2. **`AttendanceApiClient` + `AttendanceApiService` (`:feature:attendance/network`):** Feature-local Retrofit built on `:core:network` — every request automatically carries `Authorization: Bearer` via `AuthInterceptor(SessionManager.default)`. The service declares **zero** `@Header("Authorization")` params; guest students submit anonymously.
3. **[`AttendanceViewModel.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/viewmodel/AttendanceViewModel.kt) (`:feature:attendance`):**
   - **Identity from the shared session (Step 4C):** `isTeacherMode = SessionManager.default.currentUser?.canTakeAttendance() == true`. There is NO local `authToken`/role-switcher state left in this class. `login()`/`logout()` write through to `SessionManager.default`; the prefs mirror matters only for app-restart hydration (`hydrateSessionFromPrefs`).
   - **Session Reset:** `resetSession()` cancels any active timer, stops advertising, clears `finalizedRecords`, and clears status messages to start a clean new session.
   - **Teacher Engine:** Generates 4-char `sessionId` and a **4-char `sessionSalt`** (required by `SessionCreateRequest`; legacy clients without it get 422), conducts a 60-second countdown and transmits `S:<sessionId>:<sessionSalt>` at `ADVERTISE_TX_POWER_HIGH`.
   - **Student Engine:** `studentShortId` = uppercase last-4 of the roll number (seeded from the backend profile at login; BLE packets keep using roll numbers so backend matching stays consistent). `S:` beacon detection transitions `LISTENING_FOR_TEACHER → PROBING_NEIGHBORS`, broadcasts `[SESSION][SELF]` at `TX_POWER_LOW` for 7s, records classmate RSSIs, and submits the top-3 desk neighbors (median ≥ −90 dBm) with the detected salt. A **403 from the salt guard** drives the red student status message (server taunt "Nice try lil bro") instead of attendance.
4. **[`AttendanceScreen.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/AttendanceScreen.kt) (`:feature:attendance`):** The one real attendance UI (the old `:app` `MainScreen.kt` was fully migrated then deleted). Role-adaptive: the teacher console renders when `user.canTakeAttendance()`, the student radar when `user.hasRole(Role.STUDENT)`. Carries course selection, the event hero card, audit results (metric cards, filter chips, CSV), the Auth/Server dialog (login, register + faculty passcode, roll number, custom backend URL), and a back arrow. `AttendanceScreenCallbacks` is the ONLY `:app`-owned surface: permission gating, radio start/stop, CSV share, reset.
5. **[`MainActivity.kt`](file:///home/wink/Projects/ble_attendance/app/src/main/java/com/attendance/ble/MainActivity.kt) (`:app`):** Owns the `RequestMultiplePermissions` launcher, the Bluetooth-enable prompt, and the hardware bridge (wires the ViewModel radio callbacks to `BleAdvertiser`/`BleScanner`), builds the `AttendanceScreenCallbacks`, renders `AppNavigation(viewModel, callbacks)`. No legacy shell remains — the dashboard's attendance card IS the real app.
6. **[`LibraryViewModel.kt`](file:///home/wink/Projects/ble_attendance/feature/library/src/main/java/com/college/feature/library/viewmodel/LibraryViewModel.kt) + `LibraryCatalogStore` (`:feature:library`):** Role gates read `SessionManager.default`: everyone browses/searches/filters/reserves; **STAFF & DEVELOPER** get the Staff Inventory tab (`addBook`/`editBook`/`removeBook` gated on `canAccessStaffTools()`); **DEVELOPER only** gets Dev Maintenance (`seedSampleData`/`clearCatalogueCache` gated on `isDeveloper()`). Catalogue state lives in object-scoped `LibraryCatalogStore` (shared with the dashboard card for live counts). Mutations commit to the in-memory catalogue first — the backend `/api/v1/library` routes are not deployed yet, so a best-effort cloud sync runs in parallel and an offline-fallback notice surfaces on failure.
7. **[`LibraryScreen.kt`](file:///home/wink/Projects/ble_attendance/feature/library/src/main/java/com/college/feature/library/LibraryScreen.kt) (`:feature:library`):** Three tiers over one catalogue: search + derived category chips + "Available Only" + reserve in **Catalogue**; intake/edit/delete with a floating "Add Book" in **Staff Inventory**; seed/clear + diagnostics in **Dev Maintenance** (purple `DEV` chip in the top bar plus a dev-only Tools dropdown), and a blue cloud-status banner.

---

### 6.2 Backend Server (`backend/` - Python FastAPI & Neon Postgres)

```
backend/
├── requirements.txt                     # FastAPI, uvicorn, SQLAlchemy (asyncio), asyncpg, PyJWT, bcrypt, aiosqlite, httpx
├── .env.example                         # Neon PostgreSQL connection template & JWT secrets
├── app/
│   ├── config.py                        # Pydantic Settings & environment config
│   ├── database.py                      # Neon asyncpg engine with PgBouncer pooling & connection cleanup
│   ├── main.py                          # FastAPI app, CORS, single-roundtrip schema sync, & sanitized error handler
│   ├── core/
│   │   └── security.py                  # Bcrypt hashing, JWT generation, & FastAPI auth dependencies
│   ├── models/
│   │   └── attendance.py                # SQLAlchemy ORM: User, Course, AttendanceSession, AttendanceRecord, SessionSubmission
│   ├── routers/
│   │   ├── auth.py                      # Register, Login, & User Profile endpoints
│   │   └── attendance.py                # Courses, Session lifecycle, check-in staging, & Mesh Audit endpoints
│   ├── schemas/
│   │   ├── auth.py                      # Pydantic request & response models for authentication
│   │   └── attendance.py                # Pydantic models for sessions, records, mesh audits, & overrides
│   └── services/
│       └── graph_engine.py              # Social-Mesh Audit Algorithm (Mutuals + Triadic Closure + Peer Witness)
└── tests/
    ├── test_backend.py                  # Pytest verification suite (security, graph engine, routes, schema, error sanitization)
    └── test_e2e_simulation.py           # 30-student end-to-end classroom mesh simulation test
```

#### Production Backend Deployment & Architectural Hardening:
* **Hosting Platform:** Render Web Service (FastAPI running on Python 3.11 with Uvicorn).
* **Live API URL:** `https://ble-attendance-api-dbf1.onrender.com/`
* **Database Platform:** Neon.tech Serverless PostgreSQL (auto-scaling compute with connection pooling).
* **Single-Roundtrip Atomic Schema Migration:** `backend/app/main.py` executes schema migrations inside an atomic PostgreSQL `DO $$ BEGIN ... END $$;` block at startup. This eliminates 35+ sequential WAN roundtrips on lifespan startup, reducing cold-start latency from ~3.5s to under ~200ms.
* **Multi-Worker Concurrency & Ephemeral DB Check-In Staging (`session_submissions`):**
  Instead of fragile in-memory dictionaries, student Wi-Fi check-ins and offline peer vouchers are written directly to the `session_submissions` table in PostgreSQL. This guarantees process isolation across multiple Uvicorn workers (`--workers 4`), provides crash resilience, and automatically cleans up records upon session finalization or cancellation.
* **Information Disclosure Protection:**
  The global exception handler (`@app.exception_handler(Exception)`) logs full tracebacks server-side via Python `logging`, while strictly returning sanitized error JSON to clients in production (`settings.APP_ENV != "development"`), preventing file path and credential leakage.
* **Teacher Manual Intervention:**
  An explicit endpoint `PATCH /api/v1/attendance/records/{record_id}/override` allows instructors to confirm and override attendance for corner-desk students or isolated nodes who failed the automated $\ge 2$ bridge triadic closure threshold.

#### Database Schema (`Neon.tech` PostgreSQL):
```sql
-- 1. Users Table (Teachers & Students)
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    hashed_password VARCHAR(255) NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    roll_number VARCHAR(50) UNIQUE,
    role VARCHAR(50) DEFAULT 'STUDENT',  -- 'STUDENT', 'TEACHER', 'DEVELOPER'
    department VARCHAR(50),
    program VARCHAR(20),
    semester INTEGER,
    section VARCHAR(10),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. Courses Table
CREATE TABLE IF NOT EXISTS courses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_code VARCHAR(30) UNIQUE NOT NULL,
    course_name VARCHAR(200) NOT NULL,
    teacher_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 3. Sessions Table
CREATE TABLE IF NOT EXISTS attendance_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id UUID REFERENCES courses(id),
    teacher_id UUID REFERENCES users(id),
    session_token VARCHAR(32) NOT NULL,
    status VARCHAR(50) DEFAULT 'ACTIVE', -- 'ACTIVE', 'COMPLETED', 'CANCELLED'
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    ended_at TIMESTAMP WITH TIME ZONE,
    total_enrolled INTEGER DEFAULT 0,
    total_verified INTEGER DEFAULT 0,
    total_spoofers INTEGER DEFAULT 0
);

-- 4. Attendance Records Table
CREATE TABLE IF NOT EXISTS attendance_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES attendance_sessions(id) ON DELETE CASCADE,
    student_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ble_device_id VARCHAR(50) NOT NULL,
    status VARCHAR(50) DEFAULT 'ABSENT',              -- 'VERIFIED_PRESENT', 'SPOOFER_REJECTED', 'ISOLATED_FLAGGED', 'ABSENT'
    verification_method VARCHAR(50) DEFAULT 'NONE',   -- 'DIRECT_MUTUAL', 'TRIADIC_CLOSURE', 'PEER_WITNESS', 'MANUAL_OVERRIDE', 'NONE'
    affinity_score NUMERIC(3, 2) DEFAULT 0.00,
    bridge_device_id VARCHAR(50),
    reported_neighbors JSONB DEFAULT '[]'::jsonb,
    remarks TEXT,
    recorded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 5. Session Submissions Table (Ephemeral multi-worker staging)
CREATE TABLE IF NOT EXISTS session_submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES attendance_sessions(id) ON DELETE CASCADE,
    student_id UUID REFERENCES users(id) ON DELETE SET NULL,
    device_key VARCHAR(50) NOT NULL,
    reported_neighbors JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_session_submission_device UNIQUE (session_id, device_key)
);
CREATE INDEX IF NOT EXISTS ix_session_submissions_session_id ON session_submissions(session_id);
```

#### Social-Mesh Audit Algorithm (`graph_engine.py`):
```python
def audit_classroom_mesh(reports: dict[str, list[str]]) -> list[dict]:
    """
    Evaluates mutual reciprocity, triadic closure (>= 2 bridges), and offline peer witness vouchers.
    reports = { "studentId": ["neighborId1", "neighborId2"] }
    """
    direct_mutuals: dict[str, list[str]] = {}
    verified_set: set[str] = set()
    verification_methods: dict[str, str] = {}
    affinity_scores: dict[str, float] = {}
    bridge_map: dict[str, str] = {}
    all_nodes = set(reports.keys())

    # Step 1: Map direct mutual pairs (A in P(B) and B in P(A))
    for student_id, claimed_neighbors in reports.items():
        mutual_peers = [
            peer for peer in claimed_neighbors
            if student_id in reports.get(peer, [])
        ]
        direct_mutuals[student_id] = mutual_peers
        if len(mutual_peers) > 0:
            verified_set.add(student_id)
            verification_methods[student_id] = "DIRECT_MUTUAL"
            affinity_scores[student_id] = 1.00

    # Phase 2: Triadic Closure / Multi-hop Propagation
    # For remaining unverified nodes, check if they share at least 2 verified bridge neighbors.
    # Strictly isolates hallway spoofers who only have <= 1 weak or unreciprocated link.
    changed = True
    while changed:
        changed = False
        for student_id, claimed_neighbors in reports.items():
            if student_id not in verified_set:
                shared_bridges = set(claimed_neighbors) & verified_set
                if len(shared_bridges) >= 2:
                    verified_set.add(student_id)
                    verification_methods[student_id] = "TRIADIC_CLOSURE"
                    affinity_scores[student_id] = 0.50
                    bridge_map[student_id] = sorted(list(shared_bridges))[0]
                    changed = True

    # Phase 3: Offline Peer Witness Vouchers
    # Offline peers who were observed by at least 2 verified cluster nodes
    witness_counts: dict[str, int] = {}
    for student_id in verified_set:
        for peer in reports.get(student_id, []):
            if peer not in verified_set and peer not in all_nodes:
                witness_counts[peer] = witness_counts.get(peer, 0) + 1

    for offline_peer, count in witness_counts.items():
        if count >= 2:
            verified_set.add(offline_peer)
            verification_methods[offline_peer] = "PEER_WITNESS"
            affinity_scores[offline_peer] = 0.85
            all_nodes.add(offline_peer)

    # Phase 4: Build Verdicts & Surface Isolated Nodes for Teacher Manual Override
    results = []
    for node in all_nodes:
        if node in verified_set:
            status = "VERIFIED_PRESENT"
        elif len(reports.get(node, [])) == 0:
            status = "ISOLATED_FLAGGED"
        else:
            status = "SPOOFER_REJECTED"

        results.append({
            "ble_device_id": node,
            "status": status,
            "verification_method": verification_methods.get(node, "NONE"),
            "affinity_score": affinity_scores.get(node, 0.00),
            "bridge_device_id": bridge_map.get(node),
            "reported_neighbors": reports.get(node, []),
            "remarks": "Manual teacher confirmation available for flagged/isolated students"
        })

    return results
```

#### REST API Endpoints Inventory:
| Method | Endpoint | Access Role | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Public | Student/Teacher account registration with faculty secret validation |
| `POST` | `/api/v1/auth/login` | Public | Authenticate user & issue signed JWT access token |
| `GET` | `/api/v1/auth/me` | Authenticated | Retrieve authenticated user profile |
| `POST` | `/api/v1/attendance/courses` | Teacher | Create a new course offering |
| `GET` | `/api/v1/attendance/courses/{id}/sessions` | Authenticated | Retrieve session history for a specific course |
| `POST` | `/api/v1/attendance/sessions` | Teacher | Create attendance session with `409 Conflict` guard against duplicate active sessions |
| `POST` | `/api/v1/attendance/sessions/{id}/cancel` | Teacher | Cancel an active session and discard staged submissions |
| `POST` | `/api/v1/attendance/sessions/{id}/submit-neighbors` | Student/Guest | Submit discovered desk neighbors & offline relay vouchers to `session_submissions` |
| `POST` | `/api/v1/attendance/sessions/{id}/audit-and-finalize` | Teacher | Merge BLE + cloud reports, execute `graph_engine.py`, persist records, and mark `COMPLETED` |
| `GET` | `/api/v1/attendance/sessions/{id}` | Authenticated | Get full session details and loaded attendance records |
| `PATCH` | `/api/v1/attendance/records/{id}/override` | Teacher | **Teacher Manual Intervention:** Override status for isolated or edge students (`MANUAL_OVERRIDE`) |
| `GET` | `/health` | Public | Healthcheck and deployment environment indicator |
| `GET` | `/debug-db` | Public | Run atomic schema sync and test connection to Neon database |


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
