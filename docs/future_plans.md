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

## KAZE performance program (reference)

Detailed in `~/Projects/KAZE_PLAN.md` (outside repo). Status snapshot: **#1 done, #5 done, #4 done (baseline-measured, structural after), #2 next but corpus-gated, #3 pending (golden-corpus-gated, doubles-only DSP)**. Order 1→5→4 done; remaining 2→3.
