# 60-Second Attendance Session Timeline (Final)

## 60-second timer

| t | Teacher phone | Server | Student phone |
|---|---|---|---|
| **0** | Tap Start → `createSession`. Beacon ON (LOW_LATENCY ~100ms, HIGH TX), carries session ID + wire salt only (no countdown suffix). 60s timer starts | Create session row. Set **`game_starts_at = started_at + 10`**, **`game_ends_at = started_at + 37.5`**. Server clock = schedule owner | **LISTENING** (opened before class): scan LOW_LATENCY for beacon only, watchdog on 12s silence. Peer probes ignored (phase gate). Wi-Fi never touched by code |
| **~3-5 (latch)** | Beacon continues (plain, unchanged) | **`POST /sessions/{id}/join`** (preflight): verify session ACTIVE, salt/token, JWT, enrollment/eligibility. Success → `{ok, game_starts_at, server_time}`. Reject → `{ok:false, reason}` | Beacon heard → 2s peak-RSSI candidate window (cross-class separation) → latch strongest ≈t=3-4, learns session ID + salt. Join call immediately. **Block on verdict (fail closed).** Reject → stop scan/broadcast/camera/watchdog/wake lock, never gather, never play, show reason, back to standby. Success → `offset = server_time − local_now`; render `remaining = game_starts_at − (local_now + offset)` ("Starting in k…") |
| **5** (or latch, whichever later, and only after join OK) | — | **Idle** (no writes) | **Gather starts**: scan HIGH + probe broadcast TX_LOW (`sessionId+shortId`). Camera starts, passive frame ranking begins |
| **10** | Beacon continues (plain) | **Idle**. Game start is a schedule event only — no server action | **Round 1 starts, server-slotted** at `game_starts_at`. Anyone latching/joining after this joins in progress (schedule-aware rounds) |
| **10→37.5** | Screen: countdown only. No live counts | **Idle** — receives nothing | Game runs 27.5s (4 rounds, fixed slots 4.4/5.8/7.8/9.5). **Gather continues whole time** (scan HIGH + broadcast) for maximal mutuality. Camera ranks frames |
| **37.5** | — | **Idle** | Game over. **Scan + broadcast stop immediately.** Best face frame locked + encoded, camera released |
| **39→45** | — | **Burst receive only** (no engine). Each arrival: validate (salt, eligibility, rate, face dedup, score bounds), upsert one record (list + close list + face + score). Late beyond 45 handled as straggler | **One POST per student**: list + close list + face + score. Retries inside window; small randomized send jitter (0-3s) to spread AP burst. Upload success → **BLE off**, poll for verdict. Upload fail/offline → BLE stays **BALANCED**, keep retrying |
| **45** | — | **Engine runs once**: graph v3.1 on full committed graph + face matching + score gate (≥15, schema ≤26) → VERIFIED/UNVERIFIED; ABSENT backfill for silent enrolled. **No re-runs** | — |
| **≈46** | Fetch roster, display verdicts. BLE air-verdict broadcast `\<ID>` for VERIFIED (backup channel) | Verdicts committed, counters set | Poll returns verdict. **VERIFIED** → radio all off, done. **UNVERIFIED** → light poll + scan **BALANCED** (await flip); air-verdict backup if offline |
| **46→60** | Review window: flip UNVERIFIED → server updates row, student poll sees it live, teacher re-broadcasts flipped `\<ID>`. Stragglers arrive | **Stragglers (post-45):** O(1) per arrival — mutual-with-already-VERIFIED + score ≥15 + no violation → VERIFIED, else UNVERIFIED "Teacher check" (no engine re-run). Re-broadcast support for flips | UNVERIFIED students keep light poll + BALANCED scan. VERIFIED stay off |
| **60** | Timer ends → finalize call (idempotent fallback) | **Server-scheduled LOCK**: final counters recompute, session → COMPLETED. **No second sweep.** Post-60 submissions → 400 "session finalized" | — |
| **+150s** (game_starts_at+150) | — | — | **Teardown (latched):** cancel every job — scan, broadcast, poll, watchdog, wake lock. Radio/camera fully off regardless of state |
| **10 min** (never latched) | — | — | **LISTENING cap:** no beacon heard → scan off, standby, "no session found" |

## Constants (one line each, tunable)

| Constant | Value | Notes |
|---|---|---|
| `PRESTART` | 10s | `game_starts_at = started_at + 10` |
| `GATHER_START` | `max(5, latch)` | After join OK; before 5 room is deaf to probes |
| `GAME_LEN` | 27.5s | 4 fixed rounds (4.4/5.8/7.8/9.5); `game_ends_at = started_at + 37.5` |
| `UPLOAD_WINDOW` | 39→45 | One POST/student (list, close list, face, score), retries, 0-3s jitter |
| `ENGINE_RUN` | 45 | Once. Full graph + face match + score gate. No re-runs |
| `STRAGGLER_RULE` | O(1) direct-mutual | Post-45: mutual-with-VERIFIED + score≥15 + clean → VERIFIED, else Teacher check |
| `LOCK` | 60 | `started_at + 60`, server-scheduled. Counters + COMPLETED only. Post-60 → 400 |
| `TEARDOWN` | +150s | `game_starts_at + 150`; all radios/camera/jobs die |
| `LISTENING_CAP` | 10 min | Never-latched fallback → standby |
| `SCORE_GATE` | ≥15, schema ≤26 | True max 26 (5+6+7+8) |

## Layers

| Layer | When | Cost |
|---|---|---|
| L1 salt / join preflight | Latch → join call | 1 tiny HTTP; returns authoritative schedule; aborts ineligible before radio/camera/game |
| L2 mesh (mutual claims) | Gather 5→37.5 (scan HIGH + broadcast) | Radio only |
| L3 face dedup | Camera after join OK→37.5, ranked; dedup at burst receive | Passive |
| L4 ultrasonic | — | Disabled |
| L5 chimp (attention lock) | Game 10→37.5, server-slotted | 27.5s fixed |
