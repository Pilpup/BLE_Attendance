# Future Plans

Living notes for work deferred or proposed beyond the completed hardening/KAZE items. Each item needs a measured baseline and explicit acceptance criteria before implementation (perf/accuracy claims require receipts).

---

## F1 — Face enrollment + 1:1 verification (Option A, recommended over current)

**Status:** proposed (not started). Preferred accuracy path over current open-set/heuristic face check; no custom model training.

### Why (vs current)
- **Current:** live capture judged in isolation / dedup heuristics + guessed threshold; no knowledge of what a specific student looks like → sensitive to light/pose/glasses/camera quality (higher false-reject/false-accept).
- **Option A:** compare live capture against that student's own enrolled reference(s) (1:1 verify). Same-identity matching is an easier problem → materially fewer wrong rejects/accepts when paired with a good embedding + calibrated threshold.
- **Tradeoffs:** requires enrollment flow, consent, storage, and delete/expiry; holds biometric/reference data (higher responsibility). References can drift (appearance changes) — mitigate with 2–3 varied shots and re-enrollment.

### Scope (high level)
1. **Enrollment capture (first use / start of term)**
   - Capture 2–3 reference photos per student (consented): varied angle/lighting, face clearly visible. Quality gate via existing sharpness scoring (`BestFrameAnalyzer`).
   - Allow re-enrollment (glasses/haircut/poor initial capture).
   - Students not enrolled fall back to current (weaker) path — must be explicit in UX and in audit/verification status.
2. **Reference storage**
   - Store reference as **embedding(s)** (preferred, smaller) and/or a bounded reference image — decision TBD; either way treat as biometric/sensitive data.
   - Consent recorded (who, when, purpose). Defined retention (e.g. semester/term) with auto-expiry + on-request delete path. Encrypted at rest, access-limited, never in git/logs, minimal retention.
   - Institution/jurisdiction policy on student biometric data must be confirmed before shipping (legal/product sign-off outside this doc).
3. **Matching path**
   - Detector → aligned 112×112 crop → pretrained ONNX embedding (backend already has `onnxruntime`; evaluate existing `face_recognition_sface.onnx`) → cosine similarity against enrolled reference(s) → **calibrated threshold** (not guessed).
   - 1:1 verify (live vs this student) for enrolled users; retain current dedup/heuristics only as fallback for unenrolled, clearly labeled.
   - Keep 640px / 4MB caps and fail-closed on weight-load (no silent degrade to fake embeddings).
4. **Threshold calibration (ties to KAZE #2)**
   - Labeled corpus (consented same/different pairs, varied conditions) → measure **false-accept / false-reject BEFORE** on current engine, calibrate threshold, re-measure **AFTER**. No corpus = no claim. Floor for meaningful numbers: ~15 identities × ~5 images each.

### Explicit non-goals
- **No custom model training / fine-tuning on student faces.** Pretrained embeddings + enrollment references + calibrated threshold first. Training requires large ongoing labeled data, retraining on roster churn, and a much higher privacy/ops burden — revisit only if A + calibration cannot meet the target error rate, and only with an explicit data/retention policy.

### Open questions
- Embedding-only vs reference image stored (and where: backend DB vs object storage) — privacy/size/re-enrollment tradeoff.
- Exact retention period and deletion UX/API (align with institution policy).
- Fallback behavior and teacher-visible status when a student is unenrolled.
- Multi-reference aggregation (best-of-N / mean embedding) for robustness.

### Dependencies / prerequisites
- KAZE #2 baseline corpus + FA/FR measurement (replay corpus, outside repo).
- Product/legal confirmation of consent and retention policy for biometric data.

---

## F2 — Student attendance heatmap (GitHub-contribution style)

**Status:** proposed (not started). Student-facing calendar view of personal attendance over time.

### Concept
- GitHub-contribution-like heatmap on the student dashboard: each day = one cell.
- **Color scale (by attendance density that day):** darker green = fewer classes/labs attended that day, lighter/bright green = more attended. (Empty/no-class days = neutral/no-color — distinguish "absent" from "no session".)
- **Interaction:** tap/click a day → detail sheet listing what the student attended that day (subjects, labs, session times, status/verification), and what was missed if applicable.

### Scope (high level)
1. **Data/API**
   - Endpoint(s) returning per-day aggregates for the authenticated student over a range (e.g. semester/last N months): `{date, attended_count, total_sessions_count, sessions:[{id, course/subject, type(lecture/lab), time, status}]}`.
   - Derive "density" from attended/total (or attended count) so color is meaningful even when day schedules differ. Source of truth = finalized attendance records (respect existing verification statuses).
2. **UI**
   - Calendar/heatmap grid (week columns × day rows, scrollable by month/term) with the dark→light green scale and a legend.
   - Day detail view: list of sessions for that day (subject/lab name, type, time, present/absent/flagged). Handle days with zero scheduled sessions distinctly.
   - Student-only view of own data (no cross-student leakage); consider role gating consistent with existing auth.
3. **Edge cases**
   - Timezone/day boundary (server vs device) — use consistent day bucketing.
   - Days with only labs vs mixed lecture+lab (density definition).
   - Partial/flagged/unverified records — decide how they color the cell and how they display in detail.

### Open questions
- Exact color mapping (thresholds/buckets) and accessibility (colorblind-safe palette + numeric/label fallback).
- Range default (current semester vs rolling year) and pagination/lazy load.
- Whether "missed but scheduled" sessions appear in the day detail (requires knowing the schedule/expected sessions, not just recorded attendance).

### Dependencies
- Stable per-student attendance history API (builds on existing records/sessions models). No dependency on KAZE #2–#4.

---

## F3 — Adaptive chimp-game difficulty (skill-based leveling)

**Status:** proposed (not started). Strengthens game telemetry beyond the fixed hard cap (score 15) and makes dual-phone play progressively harder.

### Why (vs current)
- **Current:** round item counts are hardcoded (`ChimpGridGame.countForRound`: 5-6-7-8) and the pass gate is a flat 15. Skilled honest players saturate the cap → score distribution compresses → telemetry loses discriminative power; the game stops being a challenge for them.
- **Adaptive leveling:** track how well a student plays at their current level; when they are consistently very good, raise their level so rounds show 6-7-8-9 (then 7-8-9-10, etc.). Honest students develop skill slowly over the term, scores stay mid-band (richer telemetry), and a higher per-round attention demand makes the "play two phones desynced" workaround measurably harder.
- **Cheat-detection unlock:** with per-student level history, the strongest signal becomes a **personal-baseline anomaly** — compare a student to their OWN history (assigned level, accuracy, reaction-time distribution), not to classmates. "Plays level 6 at ~90% accuracy normally; today level 2 at 55% with 2× median RT" → remark for instructor review. Comparing to self, not peers, keeps the false-flag rate low.

### Design constraints (non-negotiable, learned from the face-dedup false-positive round)
1. **Pass threshold must scale with level.** A fixed 15 against larger counts would fail honest weak students → false "unverified". Rule: pass = fixed fraction of achievable at the assigned level (e.g. ~50%), floor 15. Leveling must never be able to fail an honest player.
2. **Level must be server-assigned.** Round config derived client-side is forgeable (a modified APK pins level 1 forever). Level comes from the server's per-student history; round sequences derive deterministically from the existing server-seeded `seed` param so submitted telemetry can be validated against the schedule.
3. **New signals are remark-grade only** (remarks / NEEDS_REVIEW / anomaly flags), never hard auto-reject verdicts.

### Scope (high level)
1. **Per-student skill profile (server):** from finalized records' chimp telemetry — level, per-round accuracy, reaction-time mean/variance, hit/miss pattern. Exponentially-weighted history per student.
2. **Level assignment rule:** promotion after N consecutive sessions above a performance band; demotion (or hold) after prolonged underperformance so honest bad days never trap a student at an unpassable level.
3. **Client:** round counts and pass line read from the server-assigned level (deterministic from seed); UI shows the level subtly (no shaming).
4. **Anomaly pass at finalize:** score/RT far below personal baseline + soft signals (see anti-correlation companion note) → remark on the record, instructor decides.
5. **Threshold calibration:** measure before/after — pass rate of honest students must be unchanged; dual-phone difficulty measured by attempt success rate dropping with level.

### Explicit non-goals
- No per-period day-splitting of leave semantics, no new sensors/permissions (mic layer is disabled; do not reintroduce hardware proximity).
- No custom model training; personal baselines are simple statistics, not ML.

### Open questions
- Promotion cadence (per-session vs weekly) and whether level resets each term.
- Minimum history before baselining (new students start at level 1 with the current counts).
- How leveling interacts with the grace-window path (late arrivals with a legitimate score).
- Whether reaction-time telemetry is already persisted per round or only the final score (may need a `challenge_report` schema extension — remark-grade, so self-reported is acceptable initially).

### Dependencies
- Phase-locked game rounds (server-issued absolute start epoch) — the personal-baseline and telemetry-validation signals assume a common schedule across the room.
- Finalized-record chimp telemetry retention (game_score is persisted; round-level detail TBD, see open questions).

---

## KAZE performance program (reference)

Detailed in `~/Projects/KAZE_PLAN.md` (outside repo). Status snapshot: **#1 done, #5 done, #4 done (baseline-measured, structural after), #2 next but corpus-gated, #3 pending (golden-corpus-gated, doubles-only DSP)**. Order 1→5→4 done; remaining 2→3.
