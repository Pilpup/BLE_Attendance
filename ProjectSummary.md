# BLE Autonomous Classroom Attendance Verification System
## Comprehensive Technical Architecture, Mathematical Foundations, & Engineering Specification

---

## 1. Executive Summary & Core Objective

### 1.1 The Problem
Traditional classroom attendance tracking suffers from two extremes:
1. **Manual / QR / PIN Systems:** High friction, wasted lecture time, and trivial proxy attendance (students screenshotting QR codes or sharing 4-digit codes via WhatsApp to friends in bed or in the hallway).
2. **Hardware Beacon Systems:** Expensive infrastructure requiring fixed battery-powered beacons (Estimote, iBeacons) or dedicated ESP32/Raspberry Pi scanners mounted on classroom walls, which require IT maintenance, battery replacements, and physical room calibration.

### 1.2 Our Solution
A **100% phones-only, zero-friction, zero-typing, anti-spoofing attendance verification system** running on native Android (Kotlin / Jetpack Compose / BLE):
- **Phones Only:** Operates purely between the teacher's smartphone and student smartphones. No external beacons, dongles, or server triangulation hardware.
- **Zero Typing:** Neither teacher nor students type student IDs, passwords, room codes, or room dimensions. The network autonomously discovers topology, elects anchors, and verifies presence.
- **Hardware-Agnostic Anti-Spoofing:** Cancels out smartphone antenna differences (cheap plastic phones vs. flagship metal chassis) and detects hallway spoofers standing outside the door.

---

## 2. BLE Advertising Protocol & Payload Optimization

### 2.1 The Legacy BLE Advertising Constraint (31-Byte Limit)
BLE Advertising operates across three primary advertising channels: **Channel 37 (2402 MHz)**, **Channel 38 (2426 MHz)**, and **Channel 39 (2480 MHz)**.
To ensure compatibility across 100% of Android devices (including older and budget chipsets that lack Android 8.0+ BLE 5.0 Extended Advertising support), all transmission must strictly adhere to the **Legacy Advertising PDU payload limit of 31 bytes**.

#### Packet Byte Breakdown:
```
Total Capacity: 31 Bytes
├── Flags AD Structure: 3 Bytes (0x02, 0x01, 0x06 - LE General Discoverable, BR/EDR Not Supported)
├── Available for Payload: 28 Bytes
```

### 2.2 The UUID Optimization Problem & Strategy
A standard 128-bit UUID consumes **16 bytes**. When packed into an advertising data structure:
- Length byte: 1 byte
- AD Type (`0x07` - Complete List of 128-bit Service UUIDs): 1 byte
- 128-bit UUID: 16 bytes
- **Total consumed: 18 bytes!**

If an 18-byte UUID structure is used:
$$\text{Remaining Payload} = 28 - 18 = \mathbf{10\text{ bytes}}$$
10 bytes is insufficient to transmit dynamic tokens such as `REPORT:B7A1:-75dBm` or `ELECT_C2:B7A1`.

#### Our Solution:
1. **Manufacturer Specific Data (`0xFF`) / Service Data Packing:**
   We register a compact 16-bit identifier or use `ServiceData` mapped to a single dedicated 16-bit Service UUID (`0xFEAA` or custom `TEST_UUID` prefix), which frees up to **23–24 bytes** of arbitrary ASCII/binary data per advertisement.
2. **Compact Token Encoding:**
   Protocol commands are engineered as compact ASCII tokens:
   - `TEACHER_PING` (12 bytes)
   - `REPORT:<ID>:<RSSI>` (14–16 bytes, e.g. `REPORT:A3F1:-68`)
   - `ELECT_C1:<ID>` (13 bytes)
   - `C1_PING` (7 bytes)
   - `REPORT2:<ID>:<Score>` (16–18 bytes)
   - `ELECT_C2:<ID>` (13 bytes)
   - Steady-state beacons: `TEACHER` (7 bytes), `CHILD_1` (7 bytes), `CHILD_2` (7 bytes)
3. **Hardware Filtering without High Battery Drain:**
   Android scanners normally incur high CPU overhead if scanning without filters. By configuring `ScanFilter.Builder().setServiceUuid(...)` matching our custom attendance UUID, the Android Linux Bluetooth kernel subsystem discards non-attendance ambient packets (smartwatches, TVs, fitness trackers) in hardware before waking the CPU.

---

## 3. The RF Physics & Mathematical Foundations

### 3.1 The Log-Distance Path Loss Model
In indoor environments, 2.4 GHz radio wave propagation obeys the Log-Distance Path Loss formula:
$$\text{RSSI}(d) = P_0 - 10 \cdot n \cdot \log_{10}\left(\frac{d}{d_0}\right) + X_\sigma$$
Where:
- $d$: Distance between transmitter and receiver (meters).
- $d_0$: Reference distance (calibrated at $1.0\text{ m}$).
- $P_0$: Received power at 1 meter (typically $-55\text{ dBm}$ to $-65\text{ dBm}$ for phones).
- $n$: Path loss exponent ($n \approx 2.0$ for free space, $n \approx 2.2\text{--}3.0$ in indoor classrooms).
- $X_\sigma$: Zero-mean Gaussian random variable modeling shadow fading ($\sigma \approx 3\text{--}6\text{ dB}$).

### 3.2 Why Absolute RSSI Fails for Attendance
Many naive attendance apps set a static threshold like:
$$\text{If } \text{RSSI} > -75\text{ dBm} \implies \text{Present}$$
**This fails catastrophically in production because:**
1. **Hardware Gain Differences:** A Samsung Galaxy S24 with high-end antenna LNA might measure $-62\text{ dBm}$ at 4 meters, while a budget Motorola phone with plastic casing measures $-78\text{ dBm}$ at the exact same spot.
2. **Body Shielding:** A student holding their phone behind their chest attenuates 2.4 GHz RF by $8\text{ to }12\text{ dB}$ (human bodies are 70% water, absorbing 2.4 GHz resonance).
3. **Environmental Fading:** Opening a classroom door or students walking across the room shifts multipath constructive/destructive interference by $\pm 6\text{ dB}$.

### 3.3 The Differential RSSI ($\Delta$) Principle
To eliminate receiver hardware bias, our system computes the **Differential RSSI ($\Delta$)** between two beacons ($A$ and $B$) measured by the same receiving phone $i$:
$$\Delta_i = \text{RSSI}_A(i) - \text{RSSI}_B(i)$$

Expanding with the path-loss model:
$$\text{RSSI}_A(i) = P_{\text{Tx}, A} + G_{\text{Rx}}(i) - 10 n \log_{10}(d_{A, i})$$
$$\text{RSSI}_B(i) = P_{\text{Tx}, B} + G_{\text{Rx}}(i) - 10 n \log_{10}(d_{B, i})$$

Subtracting the two equations:
$$\Delta_i = (P_{\text{Tx}, A} - P_{\text{Tx}, B}) + \underbrace{[G_{\text{Rx}}(i) - G_{\text{Rx}}(i)]}_{= 0} + 10 n \log_{10}\left(\frac{d_{B, i}}{d_{A, i}}\right)$$

$$\Delta_i = 10 n \log_{10}\left(\frac{d_{B, i}}{d_{A, i}}\right) \quad (\text{when } P_{\text{Tx}, A} = P_{\text{Tx}, B})$$

#### Key Mathematical Takeaway:
1. **Receiver Antenna Bias $G_{\text{Rx}}(i)$ completely cancels out.** Whether the student has an iPhone 16 Pro or a \$50 Android phone, their measured $\Delta$ is identical.
2. **Baseline transmit power cancels out.** The calculation depends solely on the **ratio of distances** $\left(\frac{d_B}{d_A}\right)$.

---

## 4. Failure Analysis of the 2-Beacon System (The "Doorway Trap")

### 4.1 The Real-World Experiment
In physical classroom testing, the Teacher beacon was placed at the front near the door ($y = 0\text{m}$), and a Child beacon was placed at the back ($y = 8\text{m}$).
- Spot 1 (1m inside the room in front of Teacher): $\Delta \approx \mathbf{+10\text{ dB}}$
- Spot 2 (1m outside the room in the hallway behind Teacher): $\Delta \approx \mathbf{+10\text{ dB}}$

The system **failed to detect the hallway spoofer**.

### 4.2 The Mathematical & Geometric Cause: Collinear Symmetry
Distance is a scalar norm $\|x - x_{\text{beacon}}\|$; it has no directional sign:
- At 1m inside: $d_{\text{Teacher}} = 1.0\text{m}$, $d_{\text{Child}} = 8 - 1 = 7.0\text{m} \implies \text{Ratio} = \frac{7}{1} = 7.0$
- At 1m outside: $d_{\text{Teacher}} = 1.0\text{m}$, $d_{\text{Child}} = 8 - (-1) = 9.0\text{m} \implies \text{Ratio} = \frac{9}{1} = 9.0$

Computing the theoretical difference:
$$\Delta_{\text{inside}} = 20 \log_{10}(7.0) = \mathbf{16.9\text{ dB}}$$
$$\Delta_{\text{outside}} = 20 \log_{10}(9.0) = \mathbf{19.1\text{ dB}}$$
$$\text{Difference} = 19.1 - 16.9 = \mathbf{2.2\text{ dB}}$$

Because BLE indoor multipath noise fluctuates by $\pm 3\text{ to }6\text{ dB}$, a **$2.2\text{ dB}$ difference is completely indistinguishable from background noise**. 

Two beacons on a line create **hyperboloids of revolution (Apollonian spheres)**. Standing 1m in front of Teacher or 1m behind Teacher puts the phone on the exact same signal contour.

### 4.3 Why Delta Could Not Read $+40\text{ dB}$
To achieve $\Delta = +40\text{ dB}$:
$$20 \log_{10}\left(\frac{d_{\text{Child}}}{d_{\text{Teacher}}}\right) \ge 40 \implies \frac{d_{\text{Child}}}{d_{\text{Teacher}}} \ge 100$$
For Teacher at 1m, Child would have to be **100 meters away**. In an 8-meter classroom, $+40\text{ dB}$ violates basic geometry.

---

## 5. The Finalized 3-Beacon 2D Bounding Architecture

To resolve the 1D mirror ambiguity, the system upgrades to a **3-Anchor 2D Enclosure Model** (`TEACHER`, `CHILD_1`, `CHILD_2`).

```
                    [FRONT WALL]
             [TEACHER] (Podium / Front)
               ╱                    ╲
             ╱                        ╲
           ╱      CLASSROOM INTERIOR    ╲   <-- 2D Convex Polygon
         ╱          (Enclosed Hull)       ╲
       ╱                                    ╲
[CHILD_2] (Back-Left) ──────────────── [CHILD_1] (Back-Right)
                    [BACK WALL]
```

### 5.1 Autonomous 2-Stage Election (Zero Typing)

1. **Teacher Stage ($T = 0.0\text{s} \rightarrow 4.0\text{s}$):**
   - Teacher broadcasts `TEACHER_PING` for 1.5s.
   - All students record $\text{RSSI}_T$ and broadcast back `REPORT:<ID>:<RSSI_T>`.
   - Teacher selects the student with $\min(\text{RSSI}_T)$ (farthest back corner) as **CHILD 1**.
   - Teacher broadcasts `ELECT_C1:<ID>`.

2. **Child 1 Stage ($T = 4.0\text{s} \rightarrow 7.5\text{s}$):**
   - Child 1 activates and broadcasts `C1_PING` for 1.5s.
   - All remaining students record their RSSI to Child 1 ($\text{RSSI}_{C1}$).
   - Each student calculates their composite **SpreadScore**:
     $$\text{SpreadScore} = \text{RSSI}_T + \text{RSSI}_{C1}$$
   - Students report `REPORT2:<ID>:<SpreadScore>`.
   - Since RSSI is negative, the student with the **most negative sum** is physically farthest from **both** Teacher and Child 1 (the opposite back corner!).
   - Teacher broadcasts `ELECT_C2:<ID>`.

3. **Steady-State Operation ($T = 9.5\text{s}+$):**
   - Teacher broadcasts `TEACHER`.
   - Child 1 broadcasts `CHILD_1`.
   - Child 2 broadcasts `CHILD_2`.
   - All other students scan all 3 signals continuously.

---

### 5.2 Dynamic Self-Alignment for Any Teacher Position
Regardless of where the teacher stands, the selection algorithm automatically spans the classroom:
- **Teacher Front-Left:** Child 1 is chosen at Back-Right; Child 2 is chosen at Back-Left.
- **Teacher Center-Podium:** Child 1 is chosen at Back-Left; Child 2 is chosen at Back-Right (forming a symmetric isosceles triangle).
- **Teacher Front-Right:** Child 1 is chosen at Back-Left; Child 2 is chosen at Back-Right.

---

### 5.3 Mathematical Proof: How 3 Beacons Catch the Door Spoofer

Let Teacher be at $(0, 0)$, Child 2 at $(0, 8)$, and Child 1 at $(8, 8)$:
- Baseline $d(T, C_2) = 8.0\text{m}$
- Room Diagonal $d(T, C_1) = \sqrt{8^2 + 8^2} = 11.31\text{m}$

#### Case A: Student 1m Inside at $(0, 1)$
- $d_T = 1.0\text{m}$
- $d_{C2} = 7.0\text{m}$
- $d_{C1} = \sqrt{8^2 + 7^2} = 10.63\text{m}$

1. **Collinear Door Gate Check:**
   $$d_T + d_{C2} = 1.0\text{m} + 7.0\text{m} = \mathbf{8.0\text{m}} = \text{Baseline}(T, C_2) \quad \implies \mathbf{PASS}$$
2. **Room Diagonal Check:**
   $$d_{C1} = 10.63\text{m} < 11.31\text{m} \quad \implies \mathbf{PASS}$$

#### Case B: Hallway Spoofer 1m Outside at $(0, -1)$
- $d_T = 1.0\text{m}$
- $d_{C2} = 9.0\text{m}$
- $d_{C1} = \sqrt{8^2 + 9^2} = \mathbf{12.04\text{m}}$

1. **Collinear Door Gate Violation:**
   $$d_T + d_{C2} = 1.0\text{m} + 9.0\text{m} = \mathbf{10.0\text{m}} \gg 8.0\text{m} \quad (+25\% \text{ overshoot!}) \implies \mathbf{REJECT}$$
2. **Room Diagonal Violation:**
   $$d_{C1} = \mathbf{12.04\text{m}} > 11.31\text{m} \quad (\text{Physically outside classroom boundaries}) \implies \mathbf{REJECT}$$

Both gates independently flag and reject the spoofer outside the door.

---

## 6. Real-World RF Hardening & Timing Constraints

1. **Packet Collision Avoidance (ALOHA Mitigation):**
   When 30+ students respond to pings, all packets transmitting simultaneously on channels 37–39 cause packet collision. 
   - **Solution:** A randomized pre-broadcast jitter delay of $0\text{ to }350\text{ ms}$ is inserted on each student phone before advertising begins.
2. **Ping Debouncing:**
   At 10 Hz advertising rate, a 1.5s ping sends 15 packets. Without debouncing, a student phone spawns 15 duplicate handler loops. 
   - **Solution:** An atomic `isHandlingPing` guard latches the first valid packet and ignores subsequent pings in the same cycle.
3. **RSSI Smoothing:**
   Single BLE packets fluctuate due to fast fading. 
   - **Solution:** Each beacon maintains a rolling 10-packet FIFO ring buffer (`teacherRssiBuffer`, `child1RssiBuffer`, `child2RssiBuffer`). All math operations execute on the moving averages.
4. **Graceful Fallback:**
   If attendance is run in an empty room or with only 1 student:
   - 0 students detected $\rightarrow$ Teacher runs continuous standalone broadcast.
   - 1 student detected $\rightarrow$ System falls back to 2-beacon linear differential mode.
   - 2+ students detected $\rightarrow$ Full 3-beacon 2D triangle activates.

---

## 7. Current Project Status & Phased Roadmap

| Milestone | Description | Status |
| :--- | :--- | :--- |
| **Stage 1: MVVM Architecture** | BleAdvertiser, BleScanner, Compose Screens, ViewModel separation | **DONE** |
| **Stage 2: 2-Beacon Protocol** | Auto-handshake timing, teacher ping, farthest student election | **DONE** |
| **Stage 3: Physical Field Testing** | Verified delta inside room; identified collinear doorway ambiguity | **DONE** |
| **Chunk 1: 3-Beacon Telemetry** | Extend ViewModel for 3 beacons (`T`, `C1`, `C2`) and new packet routing | **DONE** |
| **Chunk 2: Handshake Orchestrator** | 9.5s multi-stage election with jitter in `MainActivity.kt` | **DONE** |
| **Chunk 3: 2D Bounding Math Engine**| Implement Door Gate and Diagonal Bound validation | **DONE** |
| **Chunk 4: Debugger Tooling & HUD** | 3-beacon telemetry, gate status indicators, and live security verdict | **DONE** |
