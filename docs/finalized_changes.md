# Finalized Implementation Action Plan: `ble_attendance`

This document reflects the finalized, mutually agreed-upon action plan and execution guardrails established between all technical reviews.

---

## Execution Guardrails & Caveats

1. **Strict Area Exemptions:**
   - **Do NOT touch HOD / Leave workflows:** Domain logic, permissions, and database constraints in these areas are active and sensitive.
   - **Do NOT touch Ultrasonic subsystems:** Layer 4 acoustic proximity was intentionally disabled in Nov 2026. Code remains inert; touching it adds churn with zero user value.
   - **Defer `NEEDS_REVIEW` schema change:** The standard mobile check-in flow runs on `POST /audit-and-finalize` (which persists directly on the server without client payload validation). It is unaffected by `SessionFinalizeRequest` validation; do not touch until legacy override endpoints are formally audited.
   - **Exclude Roll Format / Teacher End Button / Student Retry Button:** Explicitly removed from implementation scope per user instruction.
2. **Database Care:**
   - `User.department` column resizing requires an explicit Alembic migration (`ALTER TABLE users ALTER COLUMN department TYPE VARCHAR(100)`) in addition to model updates to avoid production schema desync.
3. **Quality Gates:**
   - Run backend test suite (`.venv/bin/pytest tests`) and Android build verification after **each batch** before moving to the next.

---

## Batch 1: P0 (Do Now — High Value Correctness)

| Task | Component | File Path & Lines | Problem & Required Fix |
| :--- | :--- | :--- | :--- |
| **P0-1** | Backend Router | [`backend/app/routers/library.py:310-325`](file:///home/wink/Projects/ble_attendance/backend/app/routers/library.py#L310-L325) | **Guard `cancel_reservation` on deleted/missing book:** If `book is None`, `await db.refresh(book)` crashes with SQLAlchemy `ArgumentError`. Guard `if book:` before refresh/return. |
| **P0-2** | Core Network | [`core/network/src/main/java/com/college/core/network/AuthInterceptor.kt:15-20`](file:///home/wink/Projects/ble_attendance/core/network/src/main/java/com/college/core/network/AuthInterceptor.kt#L15-L20) | **Do not strip token for `/auth/logout`:** Backend `/api/v1/auth/logout` strictly requires `@current_user: User = Depends(get_current_user)`. Exempt `/logout` so Bearer token is retained. |
| **P0-3** | Feature Library | [`feature/library/src/main/java/com/college/feature/library/viewmodel/LibraryViewModel.kt:132-144`](file:///home/wink/Projects/ble_attendance/feature/library/src/main/java/com/college/feature/library/viewmodel/LibraryViewModel.kt#L132-L144) | **Roll back optimistic reserve on transport failure (`-1`):** Rollback was skipped when `failureCode == -1`. Ensure local `availableCopies` decrements are reverted on both HTTP errors and transport failures so the UI cannot lie. |
| **P0-4** | Feature Attendance | [`feature/attendance/src/main/java/com/college/feature/attendance/viewmodel/AttendanceViewModel.kt:1361-1380`](file:///home/wink/Projects/ble_attendance/feature/attendance/src/main/java/com/college/feature/attendance/viewmodel/AttendanceViewModel.kt#L1361-L1380) | **Queue air-verdict broadcasts:** Rapid instructor verifications currently trigger `airVerdictJob?.cancel()`, dropping earlier student broadcasts. Replace with a sequential queue so in-flight IDs are never lost. |

> **Verification Gate 1:** Run `.venv/bin/pytest tests` and compile check before proceeding to Batch 2.

---

## Batch 2: P1 (Do Next — Integrity & Schema Sync)

| Task | Component | File Path & Lines | Problem & Required Fix |
| :--- | :--- | :--- | :--- |
| **P1-1** | Backend Router | [`backend/app/routers/library.py:251-295`](file:///home/wink/Projects/ble_attendance/backend/app/routers/library.py#L251-L295) | **Duplicate active reservation check:** Check for an existing `ACTIVE` reservation on `(book_id, user_id)` before decrementing copies to prevent a single user monopolizing a title. |
| **P1-2** | Backend Models | [`backend/app/models/attendance.py:25`](file:///home/wink/Projects/ble_attendance/backend/app/models/attendance.py#L25) | **Widen `User.department` to `String(100)`:** Generate Alembic migration + update model to resolve the mismatch against `Course` and `LeaveApplication` (`String(100)`). |

> **Verification Gate 2:** Run integration tests and full mobile build.

---

## Excluded / Status of Other Items

- **Allow normal student roll numbers (`AuthScreen.kt`):** Excluded per user instruction.
- **TeacherSection "End & Finalize" button:** Excluded per user instruction.
- **StudentSection "Check Status / Retry" button:** Excluded per user instruction.
- **`AttendanceScreen.kt` Student-Only Auth Dialog Reachability:** Resolved / verified.
- **`NEEDS_REVIEW` Status Mismatch:** Deferred per caveat (standard `/audit-and-finalize` flow unaffected).
- **Ultrasonic Native C++ / Audio Drivers:** Untouched per caveat.
- **HOD / Leave Workflows:** Untouched per caveat.
