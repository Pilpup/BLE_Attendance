# Future Plans

Living notes for work deferred or proposed beyond the completed hardening/KAZE items. Each item needs a measured baseline and explicit acceptance criteria before implementation (perf/accuracy claims require receipts).

---

## F1 — Face enrollment + 1:1 verification (passive enrollment, amended 2026-11-17)

**Status:** implemented (backend, 2026-11-18), **not calibrated** — the design is
live but every similarity threshold is still an uncalibrated placeholder pending
the KAZE #2 corpus. No accuracy claim until that measurement lands. Replaces the
previous register-screen-enrollment draft before implementation started. Key
change from that draft: enrollment is **passive** — no capture flow at the
register screen. The engine harvests embeddings from normal daily submissions,
then flips each student to 1:1 verification against their own reference set once
enough quality references exist.

### Why (vs current)
- **Current:** live capture judged in isolation / dedup heuristics + guessed
  threshold; no knowledge of what a specific student looks like → sensitive to
  light/pose/glasses/camera quality (higher false-reject/false-accept). The worst
  field symptom: two honest lookalike students collide in the desk-cluster
  duplicate gate because every live capture is cross-compared against every other.
- **1:1 closed-set:** compare live capture against that student's own enrolled
  references. Honest submissions are never cross-compared with classmates, so the
  lookalike false-"duplicate" mode is removed architecturally, not just tuned.
  Proxy fraud inverts into identity fraud: live face B vs claimed identity A's
  references → mismatch → flagged.
- **Passive enrollment vs capture flow:** zero enrollment friction; references
  keep improving every week (drift: glasses, haircut, beard) instead of freezing
  at enrollment day; students who skip class are handled per-student, not by a
  calendar cutoff.

### Design

1. **Embedding head upgrade (prerequisite)**
   - Keep YuNet detection + landmark alignment; replace the SFace 128-d embedding
     with an ArcFace-class 512-d ONNX model (e.g. insightface `buffalo_l`
     recognition head, or AdaFace if low-quality desk selfies dominate) via the
     already-wired `onnxruntime` path, aligned 112x112 crop.
   - Reason: SFace's 128-d space cannot reliably separate "similar different
     people" from "same person, second device" — that overlap IS the false-dupe
     bug. ArcFace widens the same/different gap to a usable margin.
   - All three existing thresholds (0.363 / 0.70 / 0.50) must be re-derived for
     the new space; never port them verbatim.

2. **Passive harvest (reference forging)**
   - During normal submissions, extract and buffer the embedding of every capture
     that passes BOTH ingestion gates:
     a. **Quality gate:** existing sharpness/pose scoring (server-side equivalent
        of `BestFrameAnalyzer`; sharp, front-facing, single face).
     b. **Trust gate:** capture was auto-verified by the face check with a good
        score, OR was confirmed by the teacher without a face-related flag.
     **A teacher override on a face-flagged submission must NEVER write a
     reference** — that channel also clears suspect (proxy) captures, and folding
     one in permanently poisons the student's template.
   - Ingestion also rejects captures farther than an outlier margin from the
     student's existing references (second line against poisoning).
   - Images are extracted server-side and immediately discarded; only embeddings
     are stored (see Privacy).

3. **Reference store (exemplars, not a single forged vector)**
   - Per student: keep **up to 5 quality-gated exemplar embeddings** spanning
     distinct sessions/days/conditions, plus a slowly-updated centroid used only
     as a secondary score. Averaging varied-angle embeddings into one "perfect
     vector" blurs it; exemplars + best-of-N keep same/different separable.
   - Store: `FaceEnrollment(student_id, exemplar embeddings, centroid, ref
     quality/condition metadata, consent timestamp, term expiry)`.

4. **Per-student flip + bounded adaptation**
   - A student switches to 1:1 when they hold **>= 5 quality references across
     >= 2 distinct days**; below that they stay on the current open-set path,
     clearly labeled as fallback in verification status/remarks.
   - Adaptation (handles appearance drift): fold new quality-gated captures into
     the exemplar set at bounded weight (EWMA-style), gated on a strong match
     against the current set, with set-size cap and outlier rejection. Never
     hard-replace the whole set on a single capture.

5. **Matching path after flip**
   - Live capture → detect → align → embed → best-of-N cosine against the
     student's exemplars → calibrated threshold → VERIFIED / flagged for review.
   - Desk-cluster dedup gate and all open-set heuristics remain ONLY as the
     fallback path for below-minimum/unenrolled students, labeled as such.
     Duplicate/identity-fraud verdicts are remark/teacher-review grade, never
     hard auto-reject.

6. **Privacy (unchanged requirements, stricter medium)**
   - Embeddings only (~2 KB per reference; 3-5 refs ≈ 6 KB/student); the source
     JPEG is never persisted to disk or DB.
   - Consent recorded (who, when, purpose); term-scoped retention with auto-expiry
     and on-request delete; encrypted at rest; never in logs/git.
   - Institution/jurisdiction policy on biometric descriptors confirmed before
     shipping (legal/product sign-off outside this doc).

7. **Threshold calibration (gate for shipping any accuracy claim)**
   - Labeled corpus (consented same/different pairs, varied conditions; floor
     ~15 identities x ~5 images) → measure false-accept/false-reject BEFORE on
     the current engine, calibrate, re-measure AFTER. No corpus = no claim.

### Explicit non-goals
- **No custom model training / fine-tuning on student faces.** Pretrained
  embeddings + harvested references + calibrated threshold first.
- No enrollment capture UI at the register screen (superseded by passive
  harvest); no forced cutover date (per-student flip by reference count).
- No hardware-proximity reintroduction (mic layer stays disabled).

### Implementation notes (2026-11-18)
- ArcFace 512-d ONNX head (SFace retained for alignment + fallback); engine is
  dimension-agnostic (`engine.embed_dim`). InsightFace weights are
  non-commercial-research licensed — AdaFace (MIT) is the production substitute.
- Reference records carry `{vec, day, quality, yaw, exposure, created_at}`.
  No picture cap: all 4-6 daily class sessions may harvest; only the stored set
  is bounded at 5. Eviction removes the most redundant ref, ties broken by lower
  quality then older.
- Centroid EWMA α=0.10 runs **once per UTC day** using the previous day's
  best-quality capture (not once per capture — 6 classes/day would otherwise
  move half the centroid daily).
- Per-student adaptive gate: `T = clamp(mu - 2.0*sigma, 0.45, min(0.80, 0.45 +
  0.05))` over the 10 pairwise cosines among that student's refs, with sigma
  floored at 0.03. The +0.05 uplift cap stops tight-ref students from being
  gated at ~0.80 while the absolute gate is still uncalibrated; it follows the
  global floor when calibration raises it. `k`, floor, uplift, and the fixed
  gates (0.25 outlier / 0.60 strong match) are placeholders. Adaptation
  requires `max(T, 0.60)` — a capture that fails that student's verification
  can never enter the template.
- Mismatch verdict is remark-grade: `IDENTITY_MISMATCH_REVIEW`, `face_verified:
  false`, frozen in the audit engine, never a 403. No flip TTA, no image storage,
  no hard semantic slots, no multi-frame upload yet.

### Open questions
- Corpus calibration of k, clamps, outlier/strong-match gates, and whether
  per-condition (yaw/glasses/exposure) thresholds help.
- Eviction quality weighting once the corpus defines "quality" (currently
  bounding-box area; blur/yaw/exposure are recorded but not gated).
- Retention period exact value and deletion UX (align with institution policy).
- ArcFace weight: resolved — 174 MB ONNX is gitignored; host operators run
  `backend/scripts/fetch_arcface_weight.py` (pinned SHA-256, atomic write) before
  startup. No Git LFS installed in this repo; commit would permanently bloat
  history.

### Dependencies / prerequisites
- KAZE #2 baseline corpus + FA/FR measurement (replay corpus, outside repo).
- Product/legal confirmation of consent and retention policy for biometric data.
- ArcFace 512-d weight fetched to `backend/app/models/weights/` by
  `backend/scripts/fetch_arcface_weight.py` (not vendored — 174 MB; checksum
  pinned). License check: insightface models are for non-commercial research —
  verify before production use; AdaFace MIT-licensed weights are the fallback.

### Rough size (from planning conversation)
Embedding head swap ~1 session · harvest + store + flip logic ~1.5 sessions ·
matching-path rewire ~0.5 session · retention/consent plumbing ~0.5 session ·
calibration corpus + before/after measurement ~1 session. Client work: none
beyond surfacing fallback/verified labels.


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
