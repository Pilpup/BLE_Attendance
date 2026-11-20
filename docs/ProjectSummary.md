# Autonomous Classroom Attendance Verification System
## Architectural Whitepaper, Multi-Layer Defense Theory, & System Specification

> **About this Document:**  
> This document is the comprehensive theoretical guide and architectural reference for the Rotom project. It explains the foundational physics, mathematical models, security defense layers, and algorithmic workflows that power the platform. It is written entirely in conceptual and theoretical terms without code snippets so that engineers, researchers, and academic administrators can immediately understand how the entire system functions.

---

## 1. Executive Summary & Core Mission

### 1.1 The Objective
The platform provides an **unspoofable, zero-hardware, zero-friction attendance verification system** designed for modern academic environments. By synthesizing **Bluetooth Low Energy (BLE) near-field proximity sensing**, **ultrasonic acoustic ranging**, **gamified cognitive attention telemetry**, and **deep facial biometrics** on standard student and instructor smartphones, the system guarantees physical in-room presence without requiring manual roll calls, QR codes, or dedicated hardware beacons.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   PHYSICAL PRESENCE DOMAIN (SMARTPHONE)                │
│                                                                        │
│   [RF Whisper Radar]       [Cognitive Focus]      [1:1 Face Biometrics]│
│   Discovers immediate      Verifies focused,      Confirms authentic   │
│   desk neighbors via       single-device human    student identity     │
│   low-power BLE mesh       interaction            using ArcFace 512-d  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Secure Cloud Transport (HTTPS)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   AUDIT & VERIFICATION DOMAIN (SERVER)                 │
│                                                                        │
│   [Graph Engine v3.1]      [Passive Enrollment]   [Two-Flag Taxonomy]  │
│   BFS connected components Multi-day reference    Final determination: │
│   & mutual reciprocity     adaptation & drift     VERIFIED or          │
│   quarantine spoofers      management             UNVERIFIED           │
└────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Core Architectural Principles
1. **Zero External Hardware:** Runs entirely on standard Android smartphones already carried by students and faculty. No wall beacons, ceiling scanners, or RFID turnstiles.
2. **Zero Manual Friction:** Students do not type codes, enter passwords, or scan distant projector screens. Devices perform proximity discovery and telemetry capture autonomously within a strict 60-second window.
3. **Physical vs. Transport Domain Separation:** Physical proximity sensing (radio frequency, acoustics, and optics) is strictly separated from data transport and verification auditing. Local sensors capture presence proofs; the cloud backend audits mathematical consistency.
4. **The Two-Flag Truth:** The verification engine evaluates every check-in against strict physical laws and classifies each student into a clear, unambiguous two-flag vocabulary:
   - **`VERIFIED`:** Presence corroborated by mutual physical links and validated biometrics.
   - **`UNVERIFIED`:** Lacks corroborating evidence or isolated; held for visual instructor review.

---

## 2. Threat Model & Multi-Layer Defense Matrix

Classroom attendance systems face distinct adversarial vectors ranging from casual proxy marking to sophisticated RF replay attacks. The platform enforces seven concentric defense layers to defeat every known attack vector.

```
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 1: DIGITAL IDENTITY & ROLE PERMISSIONS             │
│   Cryptographic session tokens and non-transferable student accounts   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 2: TIME-SYNCHRONIZED IN-ROOM SALT GATE             │
│   Dynamic rolling salt broadcast exclusively inside the physical room  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 3: LOW-POWER BLE WHISPER MESH RADAR                │
│   Near-field peer discovery and mutual reciprocity graph audit         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 4: COGNITIVE ATTENTION CHALLENGE                   │
│   Chimp memory test detects multi-device split attention & touch bots  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 5: PASSIVE BIOMETRIC FACE VERIFICATION             │
│   ArcFace 512-d closed-set 1:1 identity matching with drift adaptation │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 6: ULTRASONIC NEAR-FIELD ACOUSTIC RADAR            │
│   19.2 kHz acoustic pulses detect co-located phones on the same desk   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 7: OFFLINE PEER WITNESSING & AIR VERDICTS          │
│   In-degree crowd witnessing & compact BLE verdict broadcast           │
└────────────────────────────────────────────────────────────────────────┘
```

### Threat Defense Summary Table

| Attack Vector | Attacker Strategy | System Defense Layer | Algorithmic Verdict |
| :--- | :--- | :--- | :--- |
| **Hallway Spoofer** | Stands outside door trying to capture teacher beacon. | **Layer 3 (Mesh Graph):** Wall attenuation drops signal below threshold; interior students do not hear spoofer. | `UNVERIFIED` (Zero mutual links) |
| **Component-Riding Spoofer** | Sniffs an enrolled friend's ID and claims them as a neighbor. | **Layer 3 (Graph Engine):** Single-sided unreciprocated claims into the verified core are rejected. | `UNVERIFIED` (Component rider) |
| **Remote Buddy Punching** | Absent student at home attempts to check in via internet. | **Layer 2 (Salt Gate):** Remote device lacks the physical BLE salt broadcast inside the lecture room. | Rejected at API Gate (Forbidden) |
| **Multi-Phone Juggling** | One student operates two phones side-by-side to mark a friend. | **Layer 4 (Cognitive Challenge):** Memorizing and tapping sequences on two screens causes cognitive failure. | `UNVERIFIED` (Score under threshold) |
| **Same-Desk Co-Location** | Two phones placed side-by-side on one desk to pass proximity. | **Layer 6 (Ultrasonic Radar):** 19.2 kHz acoustic tones detect sub-20 cm proximity between devices. | Flagged for Same-Desk Review |
| **Face Impersonation** | Student takes selfie for an absent classmate. | **Layer 5 (1:1 Biometrics):** ArcFace embedding compares against enrolled student templates. | `UNVERIFIED` (Identity mismatch) |
| **Touch Macro / Bot** | Student uses automated script to beat the memory challenge. | **Layer 4 (Telemetry):** Superhuman response times and zero spatial variance trigger bot flags. | Rejected (Automated Script) |
| **Isolated Corner Student** | Honest student sitting alone at the edge of the classroom. | **Teacher Console:** Flagged as unverified; teacher confirms with a single tap on the review screen. | `VERIFIED` (Teacher Override) |
| **Offline Student** | Student in basement lecture hall with zero internet connection. | **Layer 7 (Peer Witnessing):** Surrounding verified online classmates witness the offline probe. | `VERIFIED` (Peer Witnessed) |

---

## 3. Physical & Algorithmic Theory Deep-Dive

### 3.1 Radio Frequency (BLE) Whisper Radar & Graph Engine v3.1

#### Why Simple Distance Estimation Fails in Classrooms
Traditional proximity systems attempt to calculate physical distance from Received Signal Strength Indication (RSSI) using free-space path loss models. In physical classrooms, this fails catastrophically due to:
- **RF Multipath Interference:** Signals bounce off steel-reinforced concrete walls, metal desk frames, and whiteboards, creating constructive and destructive interference patterns.
- **Human Body Attenuation:** The human body is approximately 70% water, absorbing 2.4 GHz RF energy. A person sitting between two phones causes an immediate 10 to 15 dB signal drop.
- **Hardware Discrepancies:** Smartphone antenna gains vary dramatically across manufacturers. An aluminum flagship phone with a low-noise amplifier reads signals up to 10 dB stronger than a budget plastic phone at the identical physical distance.

#### The Invariant Solution: Ordinal Preference Ranking
While absolute RSSI fluctuates across phone models, **ordinal preference ranking** remains invariant under physical topology: a desk neighbor sitting 1 meter away will **always rank higher** in signal strength than a student sitting 6 meters away.

During the active discovery window, student devices broadcast at reduced transmission power (`TX_POWER_LOW`). Each phone measures incoming signals, filters out ambient noise through median aggregation, and submits an ordered list of its top candidates.

```
       [Student A] ◄────── (1.2m) ──────► [Student B] ◄────── (1.2m) ──────► [Student C]
      (Desk Row 1)                         (Desk Row 1)                         (Desk Row 1)
                                                │
                                       (6m: Signal < -85 dBm)
                                                │
                                                ▼
                                         [Hallway Spoofer]
                                      (Fails Proximity Cutoff)
```

#### Graph Engine v3.1 Verification Rules
When submissions reach the server, the graph verification engine models the classroom as a directed graph where devices are nodes and neighbor reports are directed edges:

1. **Mutual Reciprocity Rule:** A valid peer link requires mutual confirmation: Device A must report Device B, and Device B must report Device A. Single-sided claims are discarded.
2. **Triangle Corroboration:** In dense classroom seating, students naturally form closed triangular clusters (A hears B, B hears C, C hears A). Triadic closure confirms high-density cluster integrity.
3. **Connected Component Isolation:** The engine partitions the graph into connected components using Breadth-First Search (BFS). The largest component represents the genuine classroom core. Detached satellite clusters (such as groups of spoofers standing together outside the room) are quarantined and marked `UNVERIFIED`.
4. **Component-Riding Rejection:** If an outside attacker sniffs a student's ID through a window and claims them, but the classroom student did not report the attacker, the engine flags the claim as an illegitimate component-riding attempt.
5. **The Two-Phone Test Rule (Rule 8):** In minimal test scenarios or small seminar groups (e.g., 2 phones), a verified mutual edge between both devices confirms proximity, while isolated devices with zero mutual confirmations are marked `UNVERIFIED` for instructor confirmation.

---

### 3.2 Gamified Cognitive Attention & Telemetry (Chimp Memory Test)

To prevent a single student from holding and checking in on multiple phones simultaneously, the system embeds an interactive cognitive challenge during the peer discovery window: the **Chimp Memory Challenge**.

```
┌────────────────────────────────────────┐
│            CHIMP MEMORY TEST           │
│                                        │
│         [ 1 ]       [ . ]              │
│                                        │
│         [ . ]       [ 2 ]       [ 3 ]  │
│                                        │
│  (Memorize numbers -> Tiles mask ->    │
│   Tap in ascending numerical order)    │
└────────────────────────────────────────┘
```

#### Cognitive Bottleneck Theory
Human cognitive processing operates under a strict working-memory bottleneck. While an individual can rapidly memorize and tap an ascending sequence of numbers on a single phone screen, **interleaving attention between two active devices simultaneously collapses performance**:
- **Visual Gaze Saccades:** Glancing back and forth between two independent screens introduces measurable reaction latency gaps.
- **Working Memory Interference:** Remembering the spatial arrangement of numbers on Screen A interferes with the numerical sequence on Screen B, leading to sequential recall errors.
- **Score Gate:** The challenge features up to 26 sequential stages. Students must achieve a minimum score of 15 to validate their check-in. Legitimate single-phone users easily pass within 20 seconds, while multi-phone proxy attempts consistently fail the threshold.

#### Bot & Macro Detection
Automated touch injectors and accessibility macros are identified through tap telemetry:
- Human taps exhibit natural spatial variance and normal distribution of reaction latencies.
- Synthetic clickers with near-zero spatial jitter or inhumanly fast reaction speeds are automatically flagged as automated scripts.

---

### 3.3 Facial Biometrics: ArcFace 512-d & Passive Enrollment

Biometric verification ensures that the person operating the phone matches the enrolled student account. Rather than relying on fragile open-set deduplication, the system uses a **closed-set 1:1 identity verification model powered by passive enrollment**.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   PASSIVE BIOMETRIC ENROLLMENT PIPELINE                │
│                                                                        │
│   Daily Check-In Selfie ──► Face Detection & Alignment (YuNet + SFace) │
│                                         │                              │
│                                         ▼                              │
│                         512-d Embedding Head (ArcFace)                 │
│                                         │                              │
│                     ┌───────────────────┴───────────────────┐          │
│                     ▼                                       ▼          │
│             [Cold Student]                          [Enrolled Student] │
│             Refs < 5 or Days < 2                    Refs >= 5 & Days >= 2│
│                     │                                       │          │
│                     ▼                                       ▼          │
│         Harvests Anchor Vectors                 1:1 Identity Match     │
│         (3-5 exemplars + EWMA)                  against student's own  │
│                                                 adaptive template      │
└────────────────────────────────────────────────────────────────────────┘
```

#### Why Open-Set 1:N Deduplication Was Replaced
Earlier systems compared every live selfie against every other student in the room (open-set 1:N search). If two classmates possessed naturally similar facial features (lookalikes), the system produced a false duplicate flag, marking honest students as proxies.

In the **1:1 closed-set model**, a student's capture is compared **only against their own stored identity template**. Classmates are never cross-compared, eliminating lookalike false-duplicate collisions by design.

#### The ArcFace 512-Dimensional Embedding Head
- **Detector & Alignment:** YuNet identifies the face bounding box and extracts five facial landmarks (pupils, nose tip, and mouth corners). SFace performs affine crop alignment to a normalized 112×112 format.
- **Embedding Vector:** The deep ArcFace neural network maps the aligned face into a 512-dimensional unit hypersphere. Unlike older 128-d spaces, the 512-d space provides substantially wider angular separation between distinct individuals, dramatically reducing false acceptance rates while tolerating natural intra-person variations.

#### Passive Enrollment Mechanics
The system does not require an explicit enrollment photo booth or initial registration photo. Enrollment occurs **passively and invisibly** during daily attendance:
1. **Cold Phase (Open Enrollment):** During the student's first few class days, high-quality, auto-verified captures are harvested into a reference set.
2. **Enrollment Flip Threshold:** Once a student accumulates at least **5 quality references across at least 2 distinct calendar days**, their account flips from open enrollment to closed-set 1:1 verification.
3. **Multi-Anchor Exemplars:** The system stores 3 to 5 diverse exemplar vectors representing different lighting conditions, angles, and facial expressions, alongside an Exponentially Weighted Moving Average (EWMA) centroid vector.
4. **Drift Adaptation:** Natural physical changes (new glasses, haircuts, facial hair) are accommodated by slowly updating the running centroid once per day using verified captures.
5. **Anti-Poisoning Defenses:**
   - **Outlier Rejection:** A capture that deviates significantly from the student's existing cluster is rejected from ingestion to prevent template corruption.
   - **Teacher Override Protection:** When a teacher manually marks an unverified student as present, that selfie is **never ingested as a biometric reference**. This ensures suspect captures can never poison the enrolled model.
6. **Privacy & Data Protection:**
   - Raw facial photographs are processed in volatile memory and immediately discarded. No student photos are ever saved to disk.
   - Stored biometric templates consist exclusively of abstract numerical embedding vectors (approx. 2 KB per student).
   - Biometric vectors carry an automatic 180-day time-to-live (TTL) and are permanently purged upon expiration.

---

### 3.4 Ultrasonic Near-Field Acoustic Ranging

To detect when two devices are placed side-by-side on the exact same desk (< 20 to 25 cm), the system utilizes near-ultrasound acoustic ranging.

```
       [Phone 1: Emitter] ═══════ (19.2 kHz Hann-Windowed Tone) ══════► [Phone 2: Listener]
      (Emits 80ms pulses)                                               (Goertzel Filter)
                                                                                │
                                                                                ▼
                                                                        [Distance Estimate]
                                                                        (< 20cm = Same Desk)
```

1. **Acoustic Tone Synthesis:** When two devices detect each other via BLE at extremely close range, one device acts as an acoustic emitter while the other acts as an acoustic listener. The emitter outputs imperceptible 19.2 kHz tone bursts smoothed by a Hann window to prevent speaker clicking transients.
2. **Goertzel Spectral Energy Analysis:** The listener samples the microphone input and executes a 5-point Goertzel filter centered at 19.2 kHz, alongside four guard bands, calculating real-time Signal-to-Noise Ratio (SNR) and sound power attenuation.
3. **Dual-Domain Sensor Fusion:** Neither sensor alone can trigger a co-location flag. Both physical domains must independently confirm extreme proximity:
   - High RF signal power (BLE median RSSI exceeding threshold), AND
   - High acoustic power (estimated distance under 25 cm with robust SNR).
4. **Reciprocal Confirmation:** Ultrasonic claims are buffered as claims rather than definitive verdicts. Both devices must reciprocally claim proximity to confirm co-location, preventing griefing or one-sided false positives.

---

### 3.5 Offline Attendance & BLE Air Verdicts

Classrooms located in basement facilities or dense concrete buildings frequently suffer from cellular blackouts and unreliable campus Wi-Fi. The platform provides a robust offline verification mechanism.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   OFFLINE ATTENDANCE & VERDICT FLOW                    │
│                                                                        │
│   [Offline Student]                                                    │
│   (No cellular data)                                                   │
│          │                                                             │
│          │ 1. Broadcasts low-power BLE probe                           │
│          ▼                                                             │
│   [Surrounding Online Classmates]                                      │
│   (Capture probe & include offline ID in neighbor lists)               │
│          │                                                             │
│          │ 2. Submits to Cloud Backend via cellular                    │
│          ▼                                                             │
│   [Cloud Backend Audit Engine]                                         │
│   (In-Degree Witnessing: 2+ verified classmates confirm presence)      │
│          │                                                             │
│          │ 3. Marks offline student as VERIFIED                        │
│          ▼                                                             │
│   [Teacher Phone]                                                      │
│   (Receives finalized audit from backend)                              │
│          │                                                             │
│          │ 4. Broadcasts Air Verdicts via BLE: "V:<ShortID>:OK"        │
│          ▼                                                             │
│   [Offline Student Phone]                                              │
│   (Receives BLE Air Verdict -> Screen turns GREEN)                     │
└────────────────────────────────────────────────────────────────────────┘
```

#### In-Degree Crowd Witnessing
When an offline student cannot contact the server, their phone continues to broadcast local BLE whisper probes. Surrounding classmates who have active internet connections capture these probes and upload them in their candidate neighbor lists. If at least two verified classroom peers independently corroborate witnessing the offline student, the backend audit engine marks the offline student as `VERIFIED`.

#### The 31-Byte Legacy BLE Advertising Constraint
Once the audit engine confirms attendance, the teacher's phone broadcasts confirmation verdicts over the air so offline students receive visual confirmation on their screens:
- **The Physical Limit:** Standard Bluetooth Low Energy advertising packets have a strict total capacity of 31 bytes. After subtracting mandatory advertising flags and service UUID headers, **only 23 to 24 bytes remain available for custom payload data**.
- **The UUID Failure Mode:** Standard 36-character hexadecimal UUIDs exceed this packet limit. If an application attempts to broadcast a full UUID, smartphone Bluetooth chipsets silently drop the packet, preventing offline devices from ever receiving confirmation.
- **The Short Identifier Solution:** The system broadcasts compact identifiers (the student's 6-character short device ID or roll number), easily fitting within the 24-byte payload. Offline student devices scan for their short identifier and update their UI to green upon receipt.

---

## 4. End-to-End Operational Lifecycle

The lifecycle of an attendance session progresses through synchronized phases anchored on authoritative server timestamps:

```
[Time]      Teacher Smartphone             Student Smartphones            Backend Server
──────────────────────────────────────────────────────────────────────────────────────────────────
T = 0s      Starts 60s Session             Idle                           Session Activated
            • Generates rolling salt                                      • Anchors schedule
            • Broadcasts high-power BLE                                   • game_starts_at
              beacon with salt                                            • game_ends_at
                                                                          • audit_run_at
──────────────────────────────────────────────────────────────────────────────────────────────────
T = 1s-20s  Displays countdown             Captures salt & beacon         Awaits submissions
                                           • Enters discovery mode
                                           • Plays Chimp Memory Test
                                           • BLE whisper mesh scan
──────────────────────────────────────────────────────────────────────────────────────────────────
T = 20s-25s Idle                           Completes memory test          Staging Buffer
                                           • Captures check-in selfie     • Ingests check-ins
                                           • Uploads neighbors + telemetry • Buffers telemetry
──────────────────────────────────────────────────────────────────────────────────────────────────
T = 60s     Session Timer Elapses          Awaiting Verdict               Automated Audit
            (or Teacher taps End)                                         • Runs Graph Engine v3.1
                                                                          • Evaluates 1:1 faces
                                                                          • Resolves offline peers
                                                                          • Neon PostgreSQL commit
──────────────────────────────────────────────────────────────────────────────────────────────────
T = 61s+    Teacher Review Dashboard       Screens Turn Green             Session Finalized
            • Top: Unverified (review)     (Online via API polling,       • RAM embeddings cleared
            • Mid: Present (verified)       Offline via BLE Air Verdicts)  • Flagged pairs available
            • Bot: Absent                                                 • CSV ready for export
```

### Teacher Review Experience
When the audit completes, the instructor console presents a clean, actionable hierarchy:
1. **Unverified Students (Top):** Any student with insufficient mutual links, failed memory scores, or unverified biometrics is placed prominently at the top with an orange card. The teacher can visually confirm their presence and tap once to grant an override.
2. **Present Students (Middle):** Confirmed, verified students appear in the middle section with animated placement.
3. **Absent Students (Bottom):** Enrolled students who never submitted check-ins appear at the bottom.
4. **One-Tap CSV Export:** The finalized roster can be exported immediately to institutional spreadsheets.

---

## 5. Architectural Directory Map

The codebase is organized into modular Android and backend components:

### Android Client Modules (`ble_attendance/`)
- `app/`: Application entry point, navigation shell, user profiles, and dynamic dashboard card registry.
- `core/model/`: Domain models for user identities, roles (`STUDENT`, `TEACHER`, `STAFF`, `DEVELOPER`), and authentication states.
- `core/network/`: Session management, automatic Bearer JWT injection, and network interceptors.
- `core/ui/`: Shared UI components, retro profile cards, and developer diagnostic dialogs.
- `feature/attendance/`:
  - `ble/`: Low-power BLE advertisers, scanners, and packet parsers.
  - `game/`: Chimp memory challenge engine and cognitive telemetry analyzers.
  - `audio/`: 19.2 kHz Hann-windowed synthesizer and Goertzel acoustic detector.
  - `network/`: Attendance API client, network models, and DTOs.
  - `viewmodel/`: Session state machine orchestrating radios, camera, and game flows.
  - `TeacherSection.kt`: Instructor review UI with status-driven categorization.
- `feature/library/`: Auxiliary campus module demonstrating shared role-based architecture.

### Backend Server Architecture (`backend/`)
- `app/models/attendance.py`: Database models for users, courses, attendance sessions, attendance records, and passive face enrollment templates.
- `app/routers/attendance.py`: REST endpoints for session lifecycle, neighbor submissions, audits, flagged biometric reviews, and teacher manual overrides.
- `app/services/`:
  - `graph_engine.py`: Graph Engine v3.1 implementing BFS connected components, mutual reciprocity, satellite isolation, and Rule 8 verification.
  - `face_engine.py`: YuNet face detector, SFace alignment/fallback, and ArcFace 512-d neural embedding extractor.
  - `face_enrollment.py`: Passive enrollment pipeline managing anchor vectors, running EWMA centroids, adaptive threshold calculation, and eviction policies.
  - `session_audit.py`: Event-driven background scheduler executing automated audits anchored on server timestamps.
  - `submission_buffer.py`: Thread-safe in-memory staging buffer for aggregating asynchronous student submissions prior to audit.

---

## 6. Institutional Integration & Privacy Assurance

When deploying within university infrastructure, administrators require rigorous privacy and reliability guarantees:

1. **Zero High-Risk PII Storage:** The platform does not require or store government identification numbers, home addresses, phone numbers, or academic grades. Authentication requires only student institutional email and roll number.
2. **Biometric Privacy Safeguards:**
   - Facial photographs are processed transiently in server RAM and immediately discarded.
   - Stored biometric profiles consist solely of non-invertible numerical embeddings (mathematical points in a 512-dimensional vector space). Raw images cannot be reconstructed from these vectors.
   - All biometric templates are subject to an automatic 180-day retention limit, ensuring graduating or departing students are purged automatically.
3. **No Campus Infrastructure Investments:** Because verification relies entirely on student and teacher mobile devices, institutions achieve military-grade attendance integrity with zero capital expenditure on beacons, scanners, or specialized classroom hardware.
