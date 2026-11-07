# BLE Autonomous Classroom Attendance Verification System
## Comprehensive Technical Specification, RF Theory, Defense Architecture, & Developer Context

> **Note for Future AI Assistants & Developers:**  
> This document is the **single source of truth** for the BLE Attendance project located at `/home/wink/Projects/ble_attendance`. It contains the complete architectural context, exact mathematical formulations, empirical physical failure analyses, RF packet byte budgets, code mappings, multi-layer defense specifications, backend API contracts, and threat models. Read this thoroughly before modifying any code.

---

## 1. Project Overview & Core Mission

### 1.1 The Objective
Build an **unspoofable, zero-friction, peer-to-peer classroom attendance verification system** combining **Bluetooth Low Energy (BLE) near-field proximity sensing**, **ultrasonic acoustic distance measurement**, and **ephemeral computer-vision biometric deduplication** on Android smartphones with a **high-throughput Cloud Backend (Python FastAPI + Neon.tech Serverless PostgreSQL)**.

### 1.2 Core Architectural Axiom: Physical Sensor Separation vs. Cloud Transport
The system strictly separates local physical environment sensing from data transport and cryptographic audit:

```
┌────────────────────────────────────────────────────────────────────────┐
│                      DOMAIN 1: PHYSICAL PRESENCE                       │
│                                                                        │
│  [RF Whisper Radar]       [Ultrasonic Acoustic]    [Vision Biometrics] │
│  "Am I within 1-2m of     "Are two phones on the   "Is one person      │
│   Desk Neighbor B?"        same desk (< 20 cm)?"    holding 2 phones?" │
│   ==> BLE TX_POWER_LOW     ==> 19.2 kHz Goertzel    ==> YuNet + SFace  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   DOMAIN 2: DATA TRANSPORT & AUDIT                     │
│  "Authenticate accounts, aggregate mesh graph, run audit algorithms"   │
│   ==> HTTPS POST via Python FastAPI + Neon.tech Serverless PostgreSQL   │
└────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Non-Negotiable System Requirements
1. **Zero External Hardware:** No wall beacons, ESP32 microcontrollers, or Raspberry Pis. Operates exclusively on existing teacher and student smartphones.
2. **Zero Manual User Friction:** 
   - No typing roll numbers, room codes, or passwords during class.
   - No QR code hunting across the lecture hall.
   - Student phones verify presence autonomously while resting on desks or in hand.
3. **Zero Prior Room Calibration:** Neither teacher nor students input room geometry, seat numbers, or grid coordinates. The system autonomously discovers seating topology via mesh graph connectivity.
4. **Multi-Layer Anti-Spoofing & Proxy Defense:** Must reliably detect and reject:
   - **Hallway Spoofer:** Standing outside the door attempting to piggyback on in-room BLE beacons.
   - **Buddy Puncher (Remote):** Absent student at home trying to submit attendance via API.
   - **Same-Desk Proxy (One Student, Multiple Phones):** A student carrying a friend's phone to class.
   - **Divided Attention / Bot Automation:** Multi-device simultaneous operation or automated accessibility scripts.
5. **Graceful Offline Safety Net (In-Degree Peer Witnessing):** Students with zero cellular balance or bad reception in basement lecture halls are witnessed by surrounding verified online classmates.
6. **Strict Role-Based Access Control (RBAC):**
   - **Teacher:** Orchestrates sessions, generates rolling session salts, reviews flagged proxies, triggers cloud audits, and exports CSV attendance. Teachers never scan student probes.
   - **Student:** Listens for teacher beacons, discovers immediate desk neighbors, completes cognitive challenge, and stages verification packets. Students never access teacher controls.
   - **Staff:** Manages institutional inventories and catalogues (e.g. Library module).
   - **Developer:** Full diagnostic access, seed/clear testing capabilities, and live role-switching via the profile card badge without polluting standard user UI.

---

## 2. The Evolutionary History of Physical & Algorithmic Failures

Understanding why earlier approaches failed under real classroom conditions is essential to avoid re-introducing flawed assumptions.

```
       [EVOLUTION OF ARCHITECTURAL ATTEMPTS]

  1. 2-Beacon Linear Differential (commit ebfa77a)
     └── FAILED: Collinear symmetry / hyperbolic contour ("The Doorway Trap").
         Inside (1m) and outside (1m) produced identical ratios (+10 dB).

  2. 3-Beacon Dynamic Triangulation (commit 405b923)
     └── FAILED: Metal wall waveguide reflections in classrooms inverted RSSI.
         Back row: -70 dBm (constructive wall reflections).
         Center room: -80+ dBm (destructive multipath + human body shielding).
         Radial minByOrNull mathematically elected the middle student as C1!

  3. Naive Mesh Reciprocity (4-Phone Field Test)
     └── FAILED: 3 distinct RF bugs:
         a) Probes at TX_POWER_HIGH reached 8m (-73 dBm), passing loose cutoffs -> Far Guy verified!
         b) take(2) ranking truncation forced Middle to drop Front -> Front marked as spoofer!
         c) Teacher verdict sent in a 400ms flash -> Middle phone missed packet, stayed unverified.

  4. Component-Riding & Fake Mutual Cluster Attacks
     └── FAILED: Hallway spoofers claiming 1 classroom node gained admission via naive triadic closure;
         groups of spoofers outside formed their own mutual cluster and passed naive reciprocity.
         Solved by: Connected component quorum isolation & component-riding detection.

  5. One-Sided Ultrasonic Desk Claims
     └── FAILED: Malicious or prankster students claiming "co-located" with an innocent classmate
         could unilaterally vandalize that classmate's record.
         Solved by: Strict reciprocal mutual confirmation gate at session finalization.

  6. Current Final Paradigm: 6-Layer Multi-Sensor Fusion & Cloud Audit
     └── SUCCESS:
         • BLE strictly used as near-field radar (< 2.5m, TX_POWER_LOW, median RSSI >= -85 dBm).
         • 19.2 kHz Hann-windowed ultrasonic acoustic radar flags co-located phones (< 20-25 cm).
         • Ephemeral OpenCV YuNet + SFace deduplication catches same-person photo submissions.
         • 24-target cognitive challenge with MCD latency oscillation flags multi-device split attention.
         • In-memory session staging buffer with snapshot union merges asymmetric discovery windows.
         • Dominant connected component graph audit quarantines satellite clusters and component riders.
```

### 2.1 Failure 1: The 2-Beacon Linear Ambiguity ("The Doorway Trap")
* **Concept:** Teacher phone at front podium ($y = 0\text{m}$), one student phone elected as Child Beacon at back wall ($y = 8\text{m}$). The differential ratio $\Delta = \text{RSSI}_T - \text{RSSI}_C$ was used to estimate depth.
* **Why It Failed:** Distance is a scalar norm $\|x - x_{\text{beacon}}\|$ with no directional sign.
  - Student 1m inside room in front of Teacher: $d_T = 1\text{m}, d_C = 7\text{m} \implies \text{Ratio} = 7.0 \implies \Delta \approx +16.9\text{ dB}$.
  - Hallway spoofer 1m outside in hallway behind Teacher: $d_T = 1\text{m}, d_C = 9\text{m} \implies \text{Ratio} = 9.0 \implies \Delta \approx +19.1\text{ dB}$.
  - The theoretical difference was only $2.2\text{ dB}$, completely drowned out by indoor multipath fading ($\pm 4\text{ to }6\text{ dB}$). Both spots sat on the exact same hyperbolic signal contour. The spoofer was indistinguishable from an enrolled student.

### 2.2 Failure 2: 3-Beacon Triangulation & The Metal Wall Resonant Cavity
* **Concept:** Elect Child 1 at back-right and Child 2 at back-left to form a 2D bounding triangle enclosing the room.
* **Why It Failed:** Classrooms with metal studs, steel-reinforced concrete, whiteboards, and steel desk frames act as **resonant RF waveguides**:
  - **Back row students received $\approx -70\text{ dBm}$** from Teacher due to constructive reflections along perimeter walls.
  - **Center room students received $\approx -80+\text{ dBm}$** from Teacher due to destructive multipath phase cancellation and human body attenuation (70% water absorbing 2.4 GHz).
  - The election logic executed `val farthest = reports.minByOrNull { it.rssi }`. Because $-80+\text{ dBm} < -70\text{ dBm}$, `minByOrNull` mathematically selected the **middle student as the "farthest" beacon every single time**, inverting the coordinate system.

### 2.3 Failure 3: The 4-Phone Field Test Pathology
In testing with 4 physical phones (Front, Middle, Back, and an outsider 5m away):
1. **The Far Guy Got Verified:** Student probes broadcasted at `ADVERTISE_TX_POWER_HIGH` ($+1\text{ to } +4\text{ dBm}$). At 5m Line-of-Sight, RSSI was $-73.4\text{ dBm}$. Because the filter cutoff was set loosely to $\ge -78\text{ dBm}$, the distant student was treated as a close neighbor and formed mutual links with the middle phone.
2. **The Front Guy Got Marked as a Spoofer:** The code enforced `take(2)` on neighbor reporting. The Middle phone heard Back ($-62\text{ dBm}$), Far Guy ($-73\text{ dBm}$), and Front ($-75\text{ dBm}$). Truncating to 2 forced Middle to drop Front ($F$). Front reported $M$, but $M$ did not report $F$. The Teacher saw an unreciprocated link and falsely branded Front as a spoofer.
3. **The 400ms Verdict Drop:** The Teacher broadcasted `VERDICT:<ID>:VERIFIED` for only 400ms. In BLE, 400ms transmits only 3–4 packets. Channel hopping and scan latency caused the Middle phone to miss the burst, leaving its screen unverified.

### 2.4 Failure 4: Component-Riding & Fake Mutual Cluster Attacks
* **Component-Riding Attack:** An attacker in the hallway sniffs the roll number of an enrolled student sitting by the window. The attacker submits an unreciprocated claim pointing to that student. Under early triadic closure rules, single-bridge claims could exploit loose graph boundaries. In the current engine, claiming a verified classroom node without reciprocation is explicitly branded as a component-riding hallway spoofer (`SPOOFER_REJECTED`).
* **Fake Mutual Cluster Attack:** Three friends standing outside in the hallway all claim each other, forming a mutual clique $A \leftrightarrow B \leftrightarrow C$. A naive reciprocity check would mark all three verified! The BFS Connected Component algorithm solves this by identifying the **dominant classroom component** and isolating any detached satellite cluster that lacks sufficient incoming anchor edges from the classroom core.

### 2.5 Failure 5: Unreciprocated Ultrasonic Desk Griefing
* If a student sitting alone maliciously or mistakenly submits `co_located_peer_id = "B4C2"`, an eager server-side evaluation would penalize student `B4C2` with `CO_LOCATED_SAME_DESK_PROXY`.
* **Solution:** Ultrasonic claims are buffered as **claims, never verdicts**. The server requires **direct two-way reciprocity** at session finalization: student $A$ must claim student $B$ AND student $B$ must claim student $A$. One-sided claims are discarded and logged, completely preventing griefing and false-positive proxy penalties.

---

## 3. Mathematical Formulations & Verification Algorithms

```
       [FRONT (F)] ─────── (1.2m) ─────── [MIDDLE (M)] ─────── (1.2m) ─────── [BACK (B)]
                                                   │
                                           (5m) [TX_POWER_LOW: Signal < -90 dBm]
                                                   │
                                             [FAR GUY (X)]
                                         (Outside Proximity Fence)
```

### 3.1 Principle 1: Hardware-Gain Invariant Ordinal Preference Ranking
* **The Problem:** Phone antennas vary widely. An aluminum unibody flagship with an LNA receiver reads signals 10 dB hotter than a budget plastic phone.
* **The Solution:** Ranking is an **ordinal invariant**. Regardless of antenna calibration, the desk neighbor sitting $1\text{ meter}$ away will **always rank higher** in RSSI than a student sitting 5 meters away.
* Every student phone records peer RSSIs and applies **median filtering** ($\text{medianRssi} \ge -85\text{ dBm}$), submitting the Top 3 closest peers.

---

### 3.2 Social-Mesh Graph Engine Formulations ([`graph_engine.py`](file:///home/wink/Projects/ble_attendance/backend/app/services/graph_engine.py))

Let $V$ be the set of all reporting student devices, and let $P(u) \subseteq V$ denote the ordered candidate neighbor list submitted by device $u \in V$.

#### Step 1: Mutual Reciprocity Graph Construction
A directed edge exists from $u$ to $v$ if $v \in P(u)$. A bidirectional mutual edge exists if and only if:
$$(u, v) \in E_{\text{mutual}} \iff v \in P(u) \land u \in P(v)$$

#### Step 2: Connected Component Discovery via Breadth-First Search (BFS)
Using $E_{\text{mutual}}$, the graph partitions into connected components $\mathcal{C} = \{C_0, C_1, C_2, \dots\}$, sorted in descending order of node count:
$$|C_0| \ge |C_1| \ge |C_2| \ge \dots$$
$C_0$ represents the primary **classroom core component**.

#### Step 3: Satellite Cluster Isolation & Anchor Quorum
For any secondary cluster $C_k$ ($k \ge 1$), the engine computes incoming anchor claims from the verified classroom core $C_0$:
$$\text{incoming\_anchors}(C_k) = \sum_{m \in C_0} \mathbf{1}[P(m) \cap C_k \neq \emptyset]$$
$$\text{in\_degree\_req} = \begin{cases} 2 & \text{if } |C_0| \ge 12 \\ 1 & \text{otherwise} \end{cases}$$
$$\text{max\_satellite\_size} = \max\left(6, \lfloor 0.35 \cdot |C_0| \rfloor\right)$$

If $C_k$ has zero links to $C_0$, or $\text{incoming\_anchors}(C_k) < \text{in\_degree\_req}$ while $|C_k| \le \text{max\_satellite\_size}$, the cluster is classified as a **Disconnected Satellite Cluster** outside the classroom:
$$\forall u \in C_k \implies \text{status} = \text{ISOLATED\_FLAGGED}, \quad \text{affinity} = 0.00$$

#### Step 4: Individual Node Verdict & Affinity Function
For each reporting student node $u \in V$:
$$\text{Affinity}(u) = \begin{cases}
1.00 & \text{if } u \in \text{VerifiedSet} \land |P(u) \cap P^{-1}(u)| \ge 1 & (\text{VERIFIED\_PRESENT, DIRECT\_MUTUAL}) \\
0.50 & \text{if } |P(u) \cap \text{VerifiedSet}| \ge 2 & (\text{ISOLATED\_FLAGGED, TRIADIC\_CLOSURE}) \\
0.25 & \text{if } |P(u) \cap \text{ActiveSubmissions}| \ge 1 \land |P(u) \cap \text{VerifiedSet}| = 0 & (\text{ISOLATED\_FLAGGED, PEER\_UNCONFIRMED}) \\
0.00 & \text{if } |P(u)| = 0 & (\text{ISOLATED\_FLAGGED, ZERO\_NEIGHBORS}) \\
0.00 & \text{if } |P(u) \cap \text{VerifiedSet}| = 1 & (\text{SPOOFER\_REJECTED, COMPONENT\_RIDER}) \\
0.00 & \text{if } P(u) \cap \text{ActiveSubmissions} = \emptyset & (\text{SPOOFER\_REJECTED, GHOST\_CLAIMS})
\end{cases}$$

#### Step 5: Offline Student In-Degree Witnessing
Let $W(w)$ be the set of verified classroom reporters who witnessed offline device $w \notin V$:
$$W(w) = \{u \in \text{VerifiedSet} \mid w \in P(u)\}$$
$$\text{Verdict}(w) = \begin{cases}
\text{VERIFIED\_PRESENT} \quad (\text{affinity} = 0.85, \text{method} = \text{PEER\_WITNESS}) & \text{if } |W(w)| \ge 2 \\
\text{ISOLATED\_FLAGGED} \quad (\text{affinity} = 0.00, \text{method} = \text{NONE}) & \text{if } |W(w)| = 1
\end{cases}$$

---

### 3.3 Ultrasonic Acoustic Distance & Inverse-Square Propagation ([`GoertzelDetector.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/audio/GoertzelDetector.kt))

To detect physical co-location on the same desk ($< 20\text{ to }25\text{ cm}$), the system employs near-ultrasound acoustic chirps.

#### 1. Hann-Windowed Tone Synthesis ([`UltrasonicToneSynthesizer.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/audio/UltrasonicToneSynthesizer.kt))
- **Frequency:** $f_0 = 19.2\text{ kHz}$ (imperceptible to adult ears, within standard 48 kHz smartphone DAC bandwidth).
- **Duration:** $80\text{ms}$ pulse train with $120\text{ms}$ interval (30 bursts over 3.6s).
- **Hann Window:** Eliminates speaker click transients:
  $$w[n] = \frac{1}{2}\left(1 - \cos\left(\frac{2\pi n}{N - 1}\right)\right)$$
  $$x[n] = A_{\text{peak}} \cdot w[n] \cdot \sin\left(\frac{2\pi f_0 n}{f_s}\right), \quad A_{\text{peak}} = 0.85 \cdot \text{Short.MAX\_VALUE}$$

#### 2. Simultaneous 5-Point Goertzel Algorithm
At $f_s = 48\text{ kHz}$ and $f_0 = 19.2\text{ kHz}$:
$$\text{coeff}_{19.2\text{kHz}} = 2 \cos\left(\frac{2\pi \cdot 19200}{48000}\right) = 2 \cos\left(\frac{4\pi}{5}\right) = -1.618033988749895 \quad (-\phi \text{, exact negative golden ratio})$$

For each audio block, Goertzel recurrence computes energy at $f_0$ and 4 guard bands ($18.4, 18.8, 19.6, 20.0\text{ kHz}$):
$$s[n] = (2 \cdot w[n] \cdot \text{sample}[n]) + \text{coeff} \cdot s[n-1] - s[n-2]$$
$$\text{Power} = s[N-1]^2 + s[N-2]^2 - (\text{coeff} \cdot s[N-1] \cdot s[N-2])$$

#### 3. Acoustic Distance Formula & Sensor Fusion
The Signal-to-Noise Ratio (SNR) and sound power attenuation in decibels:
$$\text{SNR}_{\text{dB}} = 10 \log_{10}\left(\frac{P_{19.2\text{kHz}}}{\text{mean}(P_{18.4}, P_{18.8}, P_{19.6}, P_{20.0})}\right)$$
$$\text{Power}_{\text{dB}} = 10 \log_{10}(P_{19.2\text{kHz}})$$
Using the calibrated baseline ($P_{\text{ref}} = 98.0\text{ dB}$ at $d_{\text{ref}} = 20.0\text{ cm}$):
$$d_{\text{cm}} = d_{\text{ref}} \times 10^{\frac{P_{\text{ref}} - \text{Power}_{\text{dB}}}{20}} = 20.0 \times 10^{\frac{98.0 - \text{Power}_{\text{dB}}}{20}}$$

**Strict Dual-Domain Sensor Fusion Rule:**
$$\text{CoLocated} \iff (\text{BLE Median RSSI} \ge -55\text{ dBm}) \land (d_{\text{cm}} < 25.0\text{ cm}) \land (\text{SNR} \ge 6.0\text{ dB})$$
Neither sensor alone can flag a student; both physical domains must independently confirm extreme co-location.

---

### 3.4 Ephemeral Face Biometric Deduplication ([`face_engine.py`](file:///home/wink/Projects/ble_attendance/backend/app/services/face_engine.py))

Prevents a single student from holding two phones and submitting attendance for both using selfie cameras.

```
Raw Camera Frame
      │
      ▼
CLAHE Contrast Rescue ──► YuNet Face Detection ──► 5-Point Landmark Align ──► SFace Embedding (128-d)
(L-channel Lab space)     (Score >= 0.55, NMS 0.3) (Eyes, nose, mouth corners) (Unit L2-norm vector)
                                                                                       │
                                                                                       ▼
                               BLAS SIMD Matrix Cosine Matching ◄──────────────────────┘
                               • Local Desk Cluster Threshold: 0.363 (99.9% accuracy)
                               • Classroom-Wide Threshold:     0.700
```

1. **Exposure Rescue (CLAHE):** Contrast Limited Adaptive Histogram Equalization applied to the L-channel of Lab color space rescues backlit/dim faces without lowering detector score thresholds.
2. **Deep Facial Embedding:** OpenCV YuNet detects face bounding boxes; OpenCV SFace extracts an aligned 128-dimensional unit vector $\vec{q} \in \mathbb{R}^{128}$ ($\|\vec{q}\|_2 = 1$).
3. **Cluster-Aware Adaptive Cosine Matching:**
   Given the session embedding matrix $\mathbf{M} \in \mathbb{R}^{N \times 128}$, scores are computed in parallel: $\vec{s} = \mathbf{M} \vec{q}$.
   $$\text{Threshold}_i = \begin{cases}
   0.363 & \text{if device } i \in \text{DeskCluster}(q) \quad (\text{Standard SFace match, } \text{FAR} < 0.1\%) \\
   0.700 & \text{if device } i \notin \text{DeskCluster}(q) \quad (\text{Classroom-wide conservative threshold})
   \end{cases}$$
4. **Zero Persistent Biometric Storage:** Raw images are never saved to disk. Embeddings are kept strictly in RAM and cleared immediately when the session is finalized or after 2 hours TTL. Flagged pairs (max 50) are exposed to the instructor via `GET /api/v1/attendance/sessions/{id}/flagged-faces` then purged.

---

### 3.5 Gamified Cognitive Focus & Tap Telemetry ([`GameTelemetryEngine.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/game/GameTelemetryEngine.kt))

Students play a 24-target orb reaction test during peer discovery ($t = 0\text{ to } 20\text{s}$). Telemetry metrics verify focused single-device human operation.

#### 1. Mean Absolute Consecutive Difference (MCD) & Latency Oscillation Index
Measures erratic latency changes between consecutive taps:
$$\text{MCD} = \frac{1}{N - 1} \sum_{i=1}^{N-1} |L_{i+1} - L_i|$$
$$\text{Oscillation Index} = \frac{\text{MCD}}{\mu_L}$$

#### 2. Threat Classifications:
- **Divided Attention Proxy (`DIVIDED_ATTENTION_PROXY`):** When one student attempts to operate two phones simultaneously, their attention alternates back and forth, producing a high sawtooth oscillation index and elongated inter-tap pauses:
  $$\text{Divided Attention} \iff (\text{Oscillation Index} \ge 0.75 \land \text{Max Inter-Tap Gap} \ge 3800\text{ms})$$
- **Automated Script / Bot (`AUTOMATED_SCRIPT`):** Synthetic touch injectors exhibit inhuman consistency:
  $$\text{Bot Flag} \iff (\sigma_L < 15\text{ms} \land \sigma_{\text{spatial}} < 0.02) \lor \left(\frac{\text{Taps} < 130\text{ms}}{N} > 0.25\right)$$

---

## 4. The 6-Layer Security & Defense Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 1: DIGITAL IDENTITY & ROLE RBAC                    │
│   • Cryptographically signed JWT Bearer token                          │
│   • Non-spoofable account binding (req.user.id & roll_number)          │
│   • Faculty passcode validation for teacher privileges                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│            LAYER 2: IN-ROOM TIME-SYNCHRONIZED SALT GATE                │
│   • Dynamic 4-char session salt broadcast exclusively via teacher BLE  │
│   • Remote API calls missing salt rejected with HTTP 403 Spoofer Trap  │
│   • Per-(session, account) submit rate limiting                        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│          LAYER 3: EPHEMERAL FACE BIOMETRIC DEDUPLICATION               │
│   • OpenCV YuNet face detection with CLAHE exposure rescue             │
│   • 128-d SFace cosine matching (threshold: 0.363 cluster, 0.70 room)  │
│   • RAM-only storage; purged on finalization (zero persistent PII)     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│            LAYER 4: ULTRASONIC NEAR-FIELD ACOUSTIC RADAR               │
│   • 19.2 kHz Hann-windowed tone & 5-point Goertzel filter              │
│   • Inverse-square acoustic distance (< 20-25 cm same-desk detection)  │
│   • Dual-domain fusion (RSSI >= -55 dBm AND acoustic < 25 cm)          │
│   • Two-way reciprocal confirmation gate at finalize (anti-griefing)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│      LAYER 5: CONTINUOUS COGNITIVE FOCUS & TAP TELEMETRY               │
│   • 24-target gamified cognitive challenge during discovery            │
│   • MCD latency oscillation index detects multi-device split attention │
│   • Rejects zero-variance robotic scripts and superhuman latencies     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               LAYER 6: SOCIAL-MESH GRAPH AUDIT ENGINE                  │
│   • BFS connected component isolation: quarantines satellite clusters  │
│   • Direct mutual reciprocity matching (1.00 affinity)                 │
│   • Component-riding attack detection (single-bridge spoofers rejected)│
│   • In-degree multi-witnessing for offline students (0.85 affinity)    │
│   • Teacher manual override control for corner/isolated desks          │
└────────────────────────────────────────────────────────────────────────┘
```

### Threat Defense Matrix

| Attack Vector | Attacker Strategy | System Defense Layer | Verdict / Outcome |
| :--- | :--- | :--- | :--- |
| **Hallway Spoofer (Outside Door)** | Sniffs teacher beacon through doorway, submits API check-in. | **Layer 6 (Mesh Audit):** Wall attenuation ($> 20\text{ dB}$) + `TX_POWER_LOW` ensures zero interior students hear spoofer $\ge -85\text{ dBm}$. Spoofer has 0 mutual links. | `SPOOFER_REJECTED` (Affinity 0.00) |
| **Component-Riding Hallway Spoofer** | Sniffs 1 enrolled friend's roll number by window and reports them. | **Layer 6 (Mesh Audit):** Claiming 1 verified classroom node without reciprocation is flagged as a component rider. | `SPOOFER_REJECTED` (Affinity 0.00) |
| **Remote Buddy Punching (At Home)** | Absent student receives WhatsApp notification and submits API call. | **Layer 2 (Salt Gate):** Remote student cannot sniff the in-room BLE beacon salt. | **HTTP 403 Forbidden** (`SALT_REJECTED`) |
| **Same-Desk Proxy (2 Phones, 1 Desk)** | Student carries absent friend's phone to class and places on desk. | **Layer 4 (Ultrasonic Radar):** 19.2 kHz acoustic Goertzel radar + BLE $\ge -55\text{ dBm}$ detects $< 20\text{ cm}$ co-location. Reciprocated pair flagged. | `CO_LOCATED_SAME_DESK_PROXY` |
| **Multi-Phone Juggling (1 Person)** | Student holds two phones side by side, playing games on both. | **Layer 5 (Tap Telemetry):** Alternating attention produces sawtooth latency oscillation ($\text{Oscillation Index} \ge 0.75, \text{Gap} \ge 3.8\text{s}$). | `DIVIDED_ATTENTION_PROXY` |
| **Same-Person Selfie Proxy** | Student submits selfie photo for friend's check-in. | **Layer 3 (Face Deduplication):** SFace cosine similarity $\ge 0.363$ matches desk neighbor's face embedding. | `DUPLICATE_FACE_PROXY` |
| **Zero-Variance Auto-Clicker** | Student runs accessibility bot or macro to beat cognitive challenge. | **Layer 5 (Tap Telemetry):** Zero latency variance ($\sigma_L < 15\text{ms}$) or superhuman speed ($< 130\text{ms}$) flagged. | `AUTOMATED_SCRIPT` |
| **Single-Witness Corner Student** | Honest student sitting at isolated perimeter desk with 1 neighbor. | **Layer 6 + Teacher Console:** Flagged for visual review (`ISOLATED_FLAGGED`); teacher verifies and marks with 1 tap. | `VERIFIED_PRESENT` (Via Teacher Override) |
| **Offline Student (No Cellular Plan)** | Student in basement lecture hall with no internet connection. | **Layer 6 (Peer Witnessing):** Surrounding verified students detect offline whisper probe; $\ge 2$ witnesses verify student. | `VERIFIED_PRESENT` (`PEER_WITNESS`, 0.85) |

---

## 5. End-to-End System Protocol & Sequence

```
[Time]      Teacher Phone               Online Student (Wi-Fi/5G)        Offline Student (No Data)       Backend Server
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 0.0s    POST /sessions ───────────► Creates active session ────────────────────────────────────────► ACTIVE Session
            Starts BLE Beacon (HIGH)     (session_id: "89F2", salt: "9381")                               (salt: "9381")
            "S:89F2:9381" (13 bytes)
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 0.5s    Broadcasting beacon         Captures beacon & extracts salt  Captures beacon & extracts salt Waiting
                                        Transitions to PROBING           Transitions to PROBING
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 1.0s    Displays countdown (60s)    WHISPER RADAR (LOW, 5s)          WHISPER RADAR (LOW, 5s)         Waiting
    to                                  • Broadcasts "[89F2][A4B1]"      • Broadcasts "[89F2][C2D3]"
T = 6.0s                                • Discovers peers >= -85 dBm     • Discovers peers >= -85 dBm
                                        • Cognitive Challenge (24 orbs)  • Cognitive Challenge (24 orbs)
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 6.2s    IDLE                        ULTRASONIC RADAR (If RSSI >= -68 dBm)                            Waiting
                                        • Emitter emits 19.2 kHz chirps
                                        • Listener runs Goertzel check
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 7.0s    IDLE                        Takes selfie (Face dedup)        Cannot upload                   Stages submission
                                        POST /submit-neighbors ────────────────────────────────────────► In-Memory Buffer
                                        (salt, neighbors, ultra, face)                                   (union-merged)
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 60.0s   Taps "End Session & Audit"                                                                   AUDIT ENGINE
            POST /audit-and-finalize ──────────────────────────────────────────────────────────────────► • Pop buffer
                                                                                                         • Reciprocal ultra
                                                                                                         • BFS Components
                                                                                                         • Leniency seal
                                                                                                         • Commit Neon DB
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 60.5s   Audit Complete ◄──────────────────────────────────────────────────────────────────────────── Returns Session
            • Metrics Dashboard                                                                          • COMPLETED
            • Verified & Spoofer Cards                                                                   • Purge RAM faces
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T = 61.0s   Air Verdict Broadcast ───►  Polls GET /sessions/{id}         Listens for Air Verdict         90s Grace Window
            "V:C2D3:OK" (HIGH)          Screen turns GREEN               Screen turns GREEN              for late inflight
```

---

## 6. Codebase Architecture & File Responsibilities

### 6.1 Android Client — Multi-Module Gradle Tree (6 Modules)

```
ble_attendance/
├── app/src/main/java/com/attendance/ble/
│   ├── MainActivity.kt                                      # Permissions, Bluetooth prompt, radio bridge, AppNavigation host
│   ├── ui/theme/Color.kt                                    # Shell theme & color palette
│   └── shell/
│       ├── AppNavigation.kt                                 # Destination routing; registers DashboardCards
│       ├── HomeScreen.kt                                    # Header, Profile Card, Features island, Dynamic Registry loop
│       └── MockUsers.kt                                     # Mock user fixtures (alice/profSmith/sarah/alex/sam)
├── core/model/src/main/java/com/college/core/model/
│   ├── Role.kt                                              # STUDENT(1), STAFF(2), TEACHER(3), DEVELOPER(4); capability helpers
│   ├── User.kt                                              # primaryRole = maxBy(priority); hasRole, canTakeAttendance, isDeveloper
│   └── SessionState.kt                                      # LoggedOut, Loading, Authenticated(user, token)
├── core/network/src/main/java/com/college/core/network/
│   ├── SessionManager.kt                                    # StateFlow<SessionState> singleton holder; session hydration
│   └── AuthInterceptor.kt                                   # Automatic Bearer JWT injection; 401 handling
├── core/ui/src/main/java/com/college/core/ui/
│   ├── DashboardCard.kt                                     # Card priority, navigation route, role visibility contract
│   ├── DashboardRegistry.kt                                 # Deduplicated registry: cardsFor(user)
│   └── components/AppComponents.kt                          # Retro Profile Card, formatDepartmentName, DevActingDialog
├── feature/attendance/src/main/java/com/college/feature/attendance/
│   ├── AndroidManifest.xml                                  # BLUETOOTH_SCAN, ADVERTISE, CONNECT, RECORD_AUDIO, CAMERA
│   ├── audio/
│   │   ├── GoertzelDetector.kt                              # 19.2 kHz 5-point Goertzel filter & acoustic distance formula
│   │   ├── UltrasonicProximityManager.kt                    # AudioRecord UNPROCESSED capture, burst tracker, live radar
│   │   ├── UltrasonicToneSynthesizer.kt                     # 19.2 kHz Hann-windowed tone synthesis & AudioTrack playback
│   │   └── NativeAcousticEngine.kt                          # Optional ARM NEON C++ acceleration bridge
│   ├── ble/
│   │   ├── BleAdvertiser.kt                                 # 24-byte compact packet advertiser (omits service UUID)
│   │   ├── BleScanner.kt                                    # Low-latency ScanFilter scanner with hardware RSSI filtering
│   │   └── BleConstants.kt                                  # UUIDs, power levels, packet prefixes
│   ├── game/
│   │   └── GameTelemetryEngine.kt                           # 24-target cognitive challenge, MCD oscillation, bot detection
│   ├── telemetry/
│   │   └── TapTelemetryModels.kt                            # TapEvent, CognitiveFocusReport, FocusClassification
│   ├── network/
│   │   ├── AttendanceApiClient.kt                           # Retrofit singleton with AuthInterceptor
│   │   ├── AttendanceApiService.kt                          # /sessions, /submit-neighbors, /audit-and-finalize, /records/override
│   │   └── NetworkModels.kt                                 # DTOs: SubmitNeighborsRequest, SessionCreateRequest, etc.
│   ├── viewmodel/AttendanceViewModel.kt                     # Session state machine, BLE/acoustic/game coordination
│   └── AttendanceScreen.kt                                  # Teacher Console / Student Radar UI, Audit results, CSV export
└── feature/library/src/main/java/com/college/feature/library/
    ├── model/{Book,LibraryTab}.kt                           # Catalogue, Staff Inventory, Dev Maintenance models
    ├── network/{LibraryApiService,LibraryApiClient}.kt      # Library REST API client
    ├── viewmodel/LibraryViewModel.kt                        # Role-gated catalogue management & live count store
    └── LibraryScreen.kt                                     # 3-tier UI: Browse/Reserve, Staff Inventory, Dev Tools
```

#### Key Client Highlights:
1. **Profile Card & Developer Role Switcher ([`AppComponents.kt`](file:///home/wink/Projects/ble_attendance/core/ui/src/main/java/com/college/core/ui/components/AppComponents.kt)):** Monospace retro terminal styling (14sp), accent indicator dot, 70dp avatar box aligned with 3 info lines (Full Name, Roll/Email, Department). Department acronyms are dynamically expanded via `formatDepartmentName` (`CSE` $\to$ `Computer Science`, `ME` $\to$ `Mechanical Engineering`, etc.). Clicking the orange role badge for a Developer opens `DevActingDialog` to toggle acting roles on the fly without cluttering the main screen.
2. **Dual-Domain Sensor Coordination ([`AttendanceViewModel.kt`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/viewmodel/AttendanceViewModel.kt)):** When peer RSSI exceeds $-68\text{ dBm}$, the lexicographically lower student ID acts as ultrasonic emitter, while the higher ID acts as listener. If $\text{RSSI} \ge -55\text{ dBm}$ and acoustic distance $< 25\text{ cm}$, `ultrasonicProximityDetected` is staged.
3. **In-Flight Grace Window Polling:** Students poll `GET /sessions/{id}` for up to 3 minutes; submissions arriving during the 90-second post-finalize grace window are evaluated against existing verified peers via `IN_FLIGHT_GRACE_PEER`.

---

### 6.2 Backend Server (`backend/` - Python FastAPI & Neon Postgres)

```
backend/
├── requirements.txt                                         # FastAPI, uvicorn, SQLAlchemy, asyncpg, opencv-python-headless
├── app/
│   ├── config.py                                            # Pydantic Settings & environment variables
│   ├── database.py                                          # Async SQLAlchemy engine with PgBouncer connection pooling
│   ├── main.py                                              # Lifespan startup, single-roundtrip schema sync, CORS
│   ├── core/security.py                                     # Password hashing, JWT creation/validation, role dependencies
│   ├── models/attendance.py                                 # SQLAlchemy ORM: User, Course, AttendanceSession, AttendanceRecord, EmailOTP
│   ├── routers/
│   │   ├── auth.py                                          # User registration, JWT login, profile endpoint, email OTP
│   │   └── attendance.py                                    # Session lifecycle, check-in staging, mesh audit, overrides
│   ├── schemas/
│   │   ├── auth.py                                          # Pydantic auth schemas
│   │   └── attendance.py                                    # Pydantic session, check-in, audit, and lab schemas
│   └── services/
│       ├── face_engine.py                                   # OpenCV YuNet + SFace 128-d deduplication, CLAHE rescue
│       ├── graph_engine.py                                  # BFS connected components, satellite isolation, mutual audit
│       ├── rate_limiter.py                                  # Per-(session, account) submit rate limiting
│       ├── submission_buffer.py                             # Thread-safe in-memory check-in staging with snapshot union
│       ├── book_lookup.py                                   # OpenLibrary ISBN metadata lookup service
│       └── email.py                                         # SMTP email delivery for OTP codes
└── tests/
    ├── test_backend.py                                      # Unit & integration pytest suite (security, audit, routes)
    └── test_e2e_simulation.py                               # 30-student simulated classroom mesh verification
```

#### Database Schema Highlights (`models/attendance.py`):
- `attendance_sessions`: `session_token`, `session_salt` (4-char), `status` (`ACTIVE`, `COMPLETED`, `CANCELLED`), `total_verified`, `total_spoofers`.
- `attendance_records`: `status` (`VERIFIED_PRESENT`, `SPOOFER_REJECTED`, `ISOLATED_FLAGGED`, `CO_LOCATED_SAME_DESK_PROXY`, `DUPLICATE_FACE_PROXY`, `DIVIDED_ATTENTION_PROXY`, `NO_FACE_DETECTED`), `verification_method` (`DIRECT_MUTUAL`, `PEER_WITNESS`, `TRIADIC_CLOSURE`, `ULTRASONIC_ACOUSTIC_MATCH`, `FACE_BIOMETRIC_MATCH`, `MANUAL_OVERRIDE`, `NONE`), `affinity_score`, `reported_neighbors`, `remarks`.
- `email_otps`: `email`, `otp_code`, `purpose`, `temp_payload`, `attempts`, `expires_at`.

#### REST API Endpoints Inventory:
| Method | Endpoint | Access Role | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Public | Account registration (faculty passcode required for Teacher) |
| `POST` | `/api/v1/auth/login` | Public | Authenticate user & issue signed JWT Bearer token |
| `GET` | `/api/v1/auth/me` | Authenticated | Retrieve authenticated user profile |
| `POST` | `/api/v1/attendance/courses` | Teacher | Create academic course offering |
| `GET` | `/api/v1/attendance/courses` | Authenticated | List all available course offerings |
| `GET` | `/api/v1/attendance/courses/{id}/sessions` | Authenticated | Session history for a specific course |
| `GET` | `/api/v1/attendance/sessions/history` | Teacher | Session history for authenticated instructor |
| `POST` | `/api/v1/attendance/sessions` | Teacher | Start attendance session with conflict check for duplicate tokens |
| `POST` | `/api/v1/attendance/sessions/{id}/cancel` | Teacher | Cancel session and purge staged submissions |
| `POST` | `/api/v1/attendance/sessions/{id}/submit-neighbors` | Student | Stage student check-in (salt, neighbors, ultrasonic claim, face selfie) |
| `POST` | `/api/v1/attendance/sessions/{id}/finalize` | Teacher | Finalize session using client-provided records and merge staged check-ins |
| `POST` | `/api/v1/attendance/sessions/{id}/audit-and-finalize` | Teacher | Execute server-side `graph_engine.py` audit, evaluate ultrasonic reciprocity, persist records |
| `GET` | `/api/v1/attendance/sessions/{id}` | Authenticated | Retrieve session details, audit metrics, and attendance records |
| `GET` | `/api/v1/attendance/sessions/{id}/flagged-faces` | Teacher / Dev | Inspect duplicate face selfie pairs flagged during session |
| `PATCH` | `/api/v1/attendance/records/{id}/override` | Teacher | **Manual Override:** Instructor confirms attendance for isolated/corner student |
| `POST` | `/api/v1/attendance/lab/face-check` | Developer | Test YuNet/SFace feature extraction on raw image |
| `POST` | `/api/v1/attendance/lab/face-compare` | Developer | Compare two face images and calculate cosine similarity |
| `GET` | `/health` | Public | Healthcheck and deployment environment status |

---

## 7. College Administration Data Integration Checklist

When integrating with university IT directories, **explicitly reassure administrators that zero sensitive PII is needed** (no phone numbers, physical addresses, birthdates, or grades).

### Standard CSV Integration Schema:
1. **Students (`students.csv`):**
   - `student_id` / Roll Number (Primary Key)
   - `name` (Full Name)
   - `email` (Institutional email used for authentication)
   - `department` (e.g. `Computer Science`)
   - `semester` / Year
   - `section` (e.g. `Section A`)
2. **Teachers (`teachers.csv`):**
   - `teacher_id` / Employee ID (Primary Key)
   - `name` (Full Name)
   - `email` (Institutional email)
   - `department`
3. **Courses & Enrollments (`courses.csv` & `enrollments.csv`):**
   - `course_code` (e.g. `CS301`), `course_name`, `teacher_id`
   - Mapping: `course_code` $\leftrightarrow$ `student_id`

---

## 8. Developer Quick-Start & Testing Guide

### 8.1 Android Build & Install
Compile and install using Gradle (do not launch emulator unless testing UI):
```bash
# Debug build:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release build:
./gradlew assembleRelease
# Output located at: app/build/outputs/apk/release/app-release.apk
```

### 8.2 Physical Multi-Device Verification Sequence
1. **Device 1 (Teacher):**
   - Log in with Teacher account (or select `profSmith` from Mock Users).
   - Select Course chip (e.g. `CS101`).
   - Tap **"Start 60s Session"**. Phone broadcasts rolling beacon `S:<SESSION_ID>:<SALT>` at `TX_POWER_HIGH`.
2. **Devices 2 & 3 (Students):**
   - Log in with Student accounts (or select `alice` and `bob`).
   - Tap **"Listen for Teacher"**.
   - Capture in-room beacon within 500ms and extract salt.
   - Complete the 24-target cognitive challenge while low-power BLE whisper mesh runs in background.
   - If phones are placed on the same desk, ultrasonic acoustic check triggers automatically.
   - Camera captures check-in selfie for ephemeral biometric deduplication.
   - Screen displays "Attendance Staged; Confirming with Server".
3. **Device 1 (Teacher Finalize):**
   - Tap **"End Session & Run Cloud Audit"** (or allow 60s timer to elapse).
   - Server runs social-graph audit, validates ultrasonic reciprocity, and writes finalized records to Neon PostgreSQL.
   - Teacher dashboard displays metrics cards (**Verified Present**, **Flagged Proxies**, **Isolated Nodes**) and scrollable record list.
   - Tap **"Export CSV"** to share attendance sheet.
