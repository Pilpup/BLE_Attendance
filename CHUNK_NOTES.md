# Chunked implementation log — honest working notes

Phased rollout of the verified findings from `CODEBASE_REVIEW.md` (§0.0 verdict: facts ~95% true;
two remedies rejected; rotating salts demoted to optional P2). Working protocol:

- One chunk at a time, smallest surgical diff, independently shippable.
- Every chunk ends with **receipts** (pytest count / build exit / artifact size) before the user commits.
- The user commits everything themselves; this assistant produces diffs only.
- Every red test is root-caused before the fix, and discoveries — including the assistant's own
  mistakes — are recorded in this file, not smoothed over.

---

## Before chunk 1 — state of the repo

- Manual latch-race fix was already live (beacon candidate pool, 2s strongest-RSSI latch, epoch guard
  at 4 reset sites). Android "BUILD SUCCESSFUL", APK 3,783,897 bytes.
- First review's verified fixes already committed by user (`92195b8 "bug fixes"`), backend at 47 tests.
- Second review independently verified claim-by-claim, annotated inline in `CODEBASE_REVIEW.md`.
- Production serves `APP_ENV=development` today (Render `env: development` in /health) — several
  guards are production-gated by design and this is documented in the relevant chunks below.

---

## Chunk 1 — Login hardening ✅

**Scope:** per-account login limiter + loose per-gateway floor; event-loop bcrypt offloaded to a
worker thread; dummy-hash timing equalizer; tests.

**Receipts:** pytest **51 passed** (47 pre-existing + 4 new) in 24.29s · diff: 4 files,
+144 / −30 (`rate_limiter.py` rewrite, `security.py` +dummy hash, `auth.py` route rework, tests).

**Notes (honest):**

- **Classroom NAT reality drove the design.** A plain per-IP limiter (the reviewer's remedy) would
  429 an entire classroom logging in through one gateway. Decision: per-account 10/5min (attempts
  count *regardless of success* — that is the point) + a per-gateway floor of 150/5min, calibrated
  above a 70-student shared-NAT login burst. A dedicated calibration test asserts the gateway floor
  stays `> 70` so nobody can silently shrink it into a classroom-killer later.
- **Caveat recorded in code, not just here:** behind Render (no `--proxy-headers` yet) all requests
  share the edge proxy address, so the gateway budget currently acts as a *global* login-burst cap.
  Still far above classroom scale. Chunk 10 restores true per-gateway semantics with no code change.
- **Timing enumeration closed properly:** unknown emails burn a full bcrypt check against
  `DUMMY_BCRYPT_HASH` (burned once at import) — same status, same message, same CPU budget. Only two
  sync bcrypt call sites existed (`login`, `register`), both verified by grep, both now
  `asyncio.to_thread`.
- The submit limiter's `(session_id, identity)` API was deliberately preserved so no caller changes
  with the engine rewrite (`SubmitRateLimiter` subclass over the new generic `SlidingWindowRateLimiter`).
- Limiter unit tests use **private instances on purpose** — touching the shared module-level
  limiters would bleed budget into `test_library.py`'s real-route login flow.

---

## Chunk 2 — Access-control triage ✅

**Scope:** flagged-faces owner gate (IDOR), syllabus auth, library reserve hoarding cap.

**Receipts:** pytest **53 passed** (+2 new: owner-deny integration test, cap lifecycle test) in
24.75s · the one existing anonymous syllabus call (`test_syllabus_schema.py:189`) now authenticates.

**Notes (honest):**

- **Compiler-checked consistency over novelty:** the flagged-faces owner gate copies the exact
  in-repo idiom from `:731/:1338` — `teacher_id != current_user.id and user_role not in ("DEVELOPER",
  "ADMIN")` — so an auditor role path behaves identically across all session-owner routes.
- **Verified the fix can't break the app before shipping it:** grep across all Kotlin sources showed
  the app never calls `syllabus` or `flagged-faces` over the network (TeacherSection's syllabus is
  local data) — so adding auth to syllabus has zero app-flow impact. Anonymous syllabus reads now get
  FastAPI's standard bearer rejection, same as every other guarded route here.
- **Reserve cap value is calibrated to two constraints:** 3 active holds keeps the pre-existing
  decrement test green (it legitimately holds 2 then stock-exhausts), and stays above realistic
  concurrent needs. Documented honestly in code: the cap is a *hoarding guardrail*, best-effort
  under concurrency; inventory truth stays on the locked `Book` row. Cancelling genuinely frees
  budget (the lifecycle test proves it).
- **Mistake made and fixed (recorded, not hidden):** the cap test initially cancelled via
  `{LIBRARY_URL}/reservations/{id}/cancel` — a guessed URL; the route actually lives at
  `/api/v1/library/reservations/{id}/cancel` (router prefix is `/library`, not under `/books`).
  One red test → verified the real path → fixed. No app code was involved.
- New owner test seeds flagged pairs through `face_registry._flagged_pairs` directly — private
  attribute access is already this suite's established practice (`registry._session_matrices`).

---

## Chunk 3 — Secrets guard v2 ✅

**Scope:** placeholder deny-list + 32-char minimum in the production guard; compose placeholder
hygiene.

**Receipts:** pytest **57 passed** (+4 guard tests) in 25.43s · compose YAML parses clean
(7 env vars intact) · diff: `config.py` +48, compose +8 comments/one value, tests +210.

**Notes (honest):**

- **Escalation order is load-bearing, and a test pins it.** The committed faculty default is *exactly*
  32 chars — a length floor alone would pass it; only the equality branch catches it. The guard checks
  empty → committed-default equality → length → deny-list, in that order, and the catalogue test
  asserts the faculty value specifically.
- **Deny-list is history-proof:** it covers today's compose placeholder idioms (`change_this`,
  `college_faculty_passcode`) *and* values recoverable from git history
  (`ble-mesh-attendance-secret-key-super-secure`, `super-secure`) — so re-pasted old secrets can't
  slide past the equality check.
- **Compose hygiene:** faculty placeholder renamed to `change_this_faculty_signup_passcode`
  (self-describing *and* deny-listed even if the token list is never updated again); header comments
  now state plainly that un-replaced placeholders + `APP_ENV=production` = the guard refuses to serve.
  Old compose files stay covered because the old faculty string remains in the deny-list.
- **Scope boundary stated:** `DATABASE_URL`'s embedded `change_this_secure_password` is *still outside
  the guard's reach* — deferred deliberately to chunk 10 (Docker hardening) rather than bolted on here.
- **The guard is production-gated and that is a documented decision:** development (Render today)
  boots with placeholder-ish values by design; a test pins this permissiveness so a future
  "why doesn't dev fail?" question has a receipt.
- **Process mistake made and fixed:** the guard tests were first appended to `test_library.py` by an
  anchor slip — wrong file *and* would have NameError'd on the missing `secrets` import. Caught
  immediately, reverted by exact-match, relocated to `test_backend.py`. One dead assertion line
  (`... is not None or True` — always true) was drafted with them and removed in the same pass.

---

## Chunk 4 — Race guards R1/R3/R4 ✅

**Scope:** grace-replay 500 → accepted; mock-context fabrication gated; register duplicate → 409;
course auto-create race → adopt the winner.

**Receipts:** pytest **61 passed** (+4 race tests) in 26.23s · diff: `auth.py` +24,
`attendance.py` +60, tests +310.

**Notes (honest):**

- **R1 was surgically narrowed** to the in-flight-grace insert (`:1308-1310`) — the only submission
  insert reachable by a *valid retrying* post-finalize student. The replayed claim returns the same
  `{"status": "success", ...}` shape; only the server log distinguishes duplicates. The 500 in the
  old path explains real "student got kicked out" reports after finalize.
- **R3 gate went inside the helper, not at the call sites** — `_get_or_create_mock_context` now
  returns `(None, None)` outside test env, protecting every current and future caller at one choke
  point. The debug-session caller (`:111`) was already test-gated upstream; the real leak was
  `list_courses` calling it unconditionally in prod.
- **R4a got a pre-check AND belt-and-braces:** insert-time duplicate re-check gives a precise 409 and
  consumes the now-void OTP record (a doomed retry loop prevented); `IntegrityError` at commit is
  caught anyway for the TOCTOU window the SELECT-check cannot see.
- **Discovery recorded mid-chunk (and respected in the test design):** `register_send_otp` bulk-purges
  prior REGISTER OTPs (`auth.py:122-128`). Two *sequential* HTTP registrations can never produce the
  double-verify window — the real race is the interleaved DELETE/INSERT of two *concurrent* send
  requests, whose survivor is a second still-valid pending OTP. The test seeds exactly that survivor
  (with backdated `created_at` so the verify-OTP "latest record" ordering stays deterministic).
  Guessing around this would have been testing an imaginary bug.
- **R4b's helper extraction was necessity, not scope creep:** SQLite/asgi test harnesses can't
  interleave two HTTP requests into one connection's SELECT→COMMIT gap. `_resolve_or_create_course`
  exposes the adopt-the-winner logic for a deterministic unit test (stale first SELECT, then a losing
  `commit` raising `IntegrityError`, then a real re-select finding the committed winner). The route
  body *shrank* with the extraction.
- One **discipline note**: the "adopt or re-raise" branch re-raises if no winner is visible after
  rollback — an unexplained constraint violation must still fail loudly, not be silently swallowed.

---

## Chunk 4b — R2 finalize boundary gate ✅

**Scope:** the stage/pop race — a submission landing while finalize is *in flight* (already drained
its staging buffer, not yet committed) read the still-ACTIVE status, got a plain 200 success, and was
silently orphaned moments later. Fixed with a per-session gate held by finalize across its **entire
body** and by the submit route across its **stage + committed-status re-read**.

**Receipts:** red test on the un-fixed tree proved the loss deterministically — `submit_res 200:
{"status":"success",...}` with **zero attendance records** — then green after the gate.
pytest **62 passed** (+1 new) in 28.48s · diff: `submission_buffer.py` +20, `attendance.py` +45/test-file +118.

**Notes (honest):**

- **Discovery during the red-test design (worth the receipt):** my first red attempt patched
  `clear_session` to stall — but the test hits the *audit* finalize route, which has pop-only (no
  late clear), so that stalling point was never reached and the straggler got graced anyway on the
  un-gated tree. A **false-positive green** that would have shipped a fake receipt. Root-caused by
  reading the failure: the drain (`pop_submissions`) is the true coordination point; re-patched to
  stall immediately *after* the real pop — instant deterministic red (`0 == 1` records), then honest
  green after the gate.
- **Two finalize routes exist and share the mechanism** (learned mid-chunk, not from the review):
  `finalize_attendance_session` (:754, drain `:864`, clear `:968`, commit after) and
  `audit_and_finalize_session` (:1373, pop `:1409`, commit much later). Both got the gate — via a
  **request-scoped FastAPI dependency** (`_session_gate_dependency`) rather than re-indenting 150+
  lines of body: acquired before the body starts, released in the dependency's `finally` even on
  exceptions — so no early 4xx, audit crash, or aborted commit can strand a submission.
- **Ordering now provable:** any stage either precedes finalize's drain (and is drawn with it) or
  happens after finalize commits (and the existing post-stage re-read routes it honestly through the
  grace path). There is no third state to lose. The manual latch-race fix pattern (epoch guard)
  already lived in this territory; 4b completes it instead of re-implementing it.
- **Honest costs:** submissions arriving during finalize *queue* for the gate (finalize body can run
  seconds with face/biometrics heavy work) and respond afterward via the grace path instead of
  getting a staged-success that evaporates. Latency traded for a correctness guarantee;
  documented in the buffer's comments. Gates are never pruned (bounded by session lifetime — a few
  hundred bytes each); noted in code.
- **Deadlock reasoning recorded:** single event loop, and every lock path takes the gate → inner
  buffer lock in the same order (no route acquires them in reverse) — no cycle.
- **Still true and untouched:** the cancel route's boundary needs no gate (an orphan stage after a
  cancelled session gets an explicit `CANCELLED` rejection, not a silent drop — cancel's clear and
  commit are adjacent), and the buffer's inner `self._lock` already made each individual op atomic.

---

## Chunk 5 — Honesty statuses ✅

**Scope:** `face_verified` bound to the check outcome; lab routes report real HTTP errors instead of
a 200 carrying an error string; finalize counters recounted from committed DB rows; backend body-size
gate; face-engine error text honesty (`"3MB"` with a 4MB constant).

**Receipts:** pytest **66 passed** (+4 new) in 39.96s — **and a non-obvious one: the expected
assertion churn did not happen.** All pre-existing `total_verified` assertions — including the 70-student
e2e sim's `== 25` — stayed green against the recount, i.e. the old computed tallies and committed rows
agreed everywhere the suite exercises them. The recount is not "correcting numbers"; it is removing a
drift *class* that the current suite cannot catch. Diff: `attendance.py` +112/−20, `main.py` +18,
`face_engine.py` ±4, tests +325.

**Notes (honest):**

- **`face_verified` was presence-computed (`True if payload.face_image_base64 else None`), not
  outcome-computed.** Trace-level honesty: in the *current* call graph every non-passing face outcome
  is intercepted above the return (no-face → 403, duplicate → 403), so the old line accidentally
  agreed with the check — the dishonesty was in *what the code claims* (payload presence), not in what
  it usually computes. The line now reads `(has_human_face and not is_duplicate) if face_check_result
  else None` — a future skip-path can no longer claim `True` silently. The new contract test covers
  all three branches: verified face → `True`, no face → `None`, non-decodable face → 403 (never a
  successful "verified" response).
- **Lab routes (`/lab/face-check`, `/lab/face-compare`) previously swallowed every exception into a
  200 with `remarks="Processing error: …"`** — a logging/monitoring lie. They now log server-side and
  raise 500 (successes unchanged). **Honest caveat:** the Kotlin app *does* expose both endpoints
  (`AttendanceApiService.kt:75/:80`), so diagnostics users will now see network errors on failure
  instead of a remarks string — that is the honest surface, and success flows are untouched.
- **Finalize counters are recounted from durable rows on BOTH finalize routes** (route A additionally
  gained the flush route B already had, and both spoofers/verified counts were converged onto the same
  query shape). The discriminating test: a prior VERIFIED row present in the DB but absent from the
  finalize payload yields `total_verified == 2` (old code: 1). The increments from grace-path
  (`:1323`) can no longer be clobbered by later finalize rewrites.
- **Body cap is deliberately two-layered and the honest scope is documented in code:** the new
  middleware gate (`MAX_REQUEST_BODY_BYTES = 6_000_000`) rejects declared `Content-Length` bodies
  *before* framework buffering — the face engine's per-field `MAX_PAYLOAD_BYTES` (4MB) remains the
  byte-authoritative bound for decoded content. A chunked (no Content-Length) requestPayload is not
  stopped by the middleware gate; engine cap still applies. Test covers 413 + a control pass-through.
- **"3MB" error text now renders from the constant** (`({MAX_PAYLOAD_BYTES // 1_000_000}MB)` → 4MB);
  both copy-pump sites single-sourced.
- Dead vars (`verified_count`/`spoofer_count`) remain in the finalize bodies post-recount — harmless
  byproducts; noted to avoid confusion in the next live read of those functions.

---

## Chunk 6 — Android build security ✅

**Scope:** release builds no longer leak secrets to logcat — OkHttp header logging gated on
`BuildConfig.DEBUG`; R8 strips `Log.v/d/i` from release dexes; release-on-CI fails fast without real
signing configuration.

**Receipts:**

- `./gradlew :app:assembleRelease` → **exit 0**, APK **3,783,893 bytes** (4 bytes lighter than the
  pre-chunk 3,783,897; a size delta is *not* the real receipt here — the dex grep is).
- **Dex-level strip proof:** the release dex no longer contains the `Log.i` literal from
  BleAdvertiser ("Radio broadcasting started successfully" → **ABSENT**) while the `Log.w` literal
  ("BluetoothLeAdvertiser not available or permissions missing") remains **PRESENT** — exactly the
  v/d/i-strip / w-e-keep contract.
- **CI fail-fast proof:** `CI=true ./gradlew :app:assembleRelease` → BUILD FAILED, **exit 1**, at
  configuration time, with the message "Release build on CI requires signing configuration: set
  KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD environment variables."
  (`/tmp/opencode/ci_fail.log`). Local (no CI var) still builds via the debug-keystore fallback.
- **Precondition verified, not assumed:** `isMinifyEnabled = true` (`app/build.gradle.kts:61`) —
  `assumenosideeffects` is inert without R8, so the strip's liveness was checked before claiming it.

**Notes (honest):**

- **AGP 8.5.2 defaults `buildConfig` off** — no module in this project had the flag, so
  `BuildConfig.DEBUG` could not even resolve in `core/network` until
  `buildFeatures { buildConfig = true }` was added. Discovered during recon, not from the review.
- **The actual leak shaped the fix:** the OkHttp interceptor ran at `Level.HEADERS` *unconditionally*,
  printing the `Authorization` bearer token (and every request header) into logcat on release
  devices — attacker-adjacent on shared devices and a gift to anyone with `adb logcat`. Debug keeps
  full header inspection; release logs nothing; R8 is the second guard, not the primary one.
- `BuildConfig` resolves same-package (`com.college.core.network.BuildConfig`) — no import needed;
  the exit-0 build plus the level-gate code confirm the wiring.
- **ProGuard keeps `Log.w/e` deliberately:** Kotlin sources use them widely (`BleAdvertiser`,
  `BleScanner`, `AttendanceViewModel:763/:776`) and they matter for crash triage in the wild.
- **Signing fail-fast fires at configuration time** (`org.gradle.api.GradleException` thrown in the
  release block — the CI log shows the failure before any task runs), and a `require` catches a
  *half-configured* environment (keystore path exists but a password/alias missing), which
  previously surfaced as an obscure signing failure late in the build. `CI` counts as truthy unless
  blank or exactly `false`, matching GitHub Actions/GitLab conventions while still letting a local
  developer opt out explicitly. Local machines keep the debug fallback so `assembleRelease` remains
  buildable without secrets in the repository.
- **Process mistake made and fixed (receipt integrity):** the first CI-fail run piped Gradle into
  `tail` and echoed `GRADLE_EXIT=$?` — which reported *tail's* exit code (0), masquerading as "CI
  fail-fast did not trigger". Corrected with a file redirect + `$?` on the raw command → honest
  exit 1. Lesson recorded: never read `$?` behind a pipe for build receipts.

---

## Chunk 7 — Auth lifecycle ✅

**Scope:** tokens become server-controllable — `token_version` claim checked on every request,
`is_active` actually enforced, and a real `POST /logout` that revokes every token the account has
ever been issued. Deploy-safe by contract: missing claim == version 0.

**Receipts:**

- **Red-first:** on the un-fixed tree, 3 of 4 new tests failed exactly at the designed loss shapes —
  logout route **absent** (404), deactivated account's `/me` → **200**, stale-version token → **200**
  — and the deploy-safety guard **passed before the fix, by design**: "legacy tokens keep working"
  is the current behavior the chunk must preserve, so its test is expected green on both sides.
- pytest **70 passed** (+4) in 35.90s · diff: 5 files **+252** (security +18, auth +18 incl. logout
  route, models +5, DDL +1, tests +210).
- **Issuance sites exhaustively covered:** grep confirms exactly two `create_access_token` call sites
  (`register_verify_otp :255`, `login_verify_otp :400`); both now embed `"tv"`.
- **Kotlin app untouched and unbroken:** the app's `logout()` (`AttendanceViewModel:510`) is local
  clearing only — no network logout call exists today, so the new `/logout` route is purely additive;
  app-side wiring is chunk 8's SessionManager work.

**Notes (honest):**

- **The `is_active` finding had a sharper shape than "missing check":** `get_current_user`'s 401 for
  unknown users *claimed* "User not found or account deactivated" while the `User.is_active` column
  and its DDL line existed long before this chunk — a message asserting enforcement that never ran.
  The deactivated test proved a deactivated user's token worked until expiry. The check now sits
  before the version check with the same honest message.
- **The deploy-safety semantics is the careful part of the diff.** The boundary is not
  "token without claim is invalid" — it is `(payload.get("tv") or 0) != (user.token_version or 0)`:
  a claim-LESS token (pre-deploy mint) vs a migrated account (server default 0) → match → valid; an
  explicit stale claim (0 vs bumped account) → revoked. Two different tests pin the two sides of that
  boundary (`legacy_token_without_version_claim_is_v0`, `stale_version_token_rejected`), and the
  distinction is documented in both code and tests — a naive `payload["tv"] != user.token_version`
  would have been a mass-logout bomb on deploy day.
- **Logout is account-wide revocation, not per-device:** bumping `token_version` kills every token
  the account has ever held, including the caller's. The simplest correct primitive for this
  codebase's threat model (stolen device copy dies with one tap); per-device session tracking was
  never in review scope. Chunk 8 wires the app's local `logout()` to call this route.
- **Re-login equilibrium is tested end-to-end, not assumed:** the lifecycle test proves logout →
  `/login` (OTP dance reading the LOGIN OTP from the test DB) → `/login/verify-otp` → `/me` 200. This
  is exactly where revocation implementations fail silently — issue the new token at the OLD version
  and every subsequent request 401s, discovered in production.
- **DDL follows the block's house style:** `ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version
  INTEGER NOT NULL DEFAULT 0;` — idempotent, sibling of `is_active`. This is the chunk's single
  schema change; Alembic remains the standing deferral.
- **Mock-token test path untouched:** the `is_test_environment()`-gated shortcut (returns first
  TEACHER/user without JWT decode) predates this chunk and stays as-is.

---

## Chunk 8 — SessionManager fail-closed + lifecycle polish ✅ (+ bug R8-B found & fixed)

**Scope:** four review findings — encrypted-prefs fail-closed, permission-launcher UX, the
Activity-bridge `onCleared` leak, `realUser` as state — **plus one P1 bug the second review missed**
(plaintext JWT shadow-store in the ViewModel), plus the chunk-7 promise: app logout now calls the
backend route.

**Receipts:**

- `./gradlew :app:assembleRelease` → **exit 0** (final run), APK **3,783,887 bytes**.
- **Plaintext-token write sweep:** `grep 'putString("auth_token"` across all Kotlin sources →
  **0 hits**. `college_auth_fallback_prefs` is referenced only as the wipe target.
- Diff: 6 files **+184/−45** (SessionManager, MainActivity, HomeScreen, AppNavigation,
  AttendanceViewModel, AttendanceApiService).

**The four review items:**

- **§1.7.2 fail-closed**: `createEncryptedPreferences` no longer returns a plaintext prefs handle on
  Keystore failure — it returns null after wiping any legacy plaintext data, and every write/read
  path (already null-safe) yields a RAM-only session for that process. The old branch silently
  parked JWTs + user JSON in `college_auth_fallback_prefs` on any corrupt-vendor-keystore/OS-upgrade
  event, with no telemetry and no degradation notice.
- **§1.7.5 permission UX**: the launchers' callbacks were literal `{}`. Results now flow into a
  pending-action replay that runs through the **full gate** (permissions → Bluetooth-adapter check);
  a denial shows an explicit rationale toast instead of silently doing nothing.
- **§0.2 bridge leak**: `onCleared()` now nulls all four Activity-wired scanning/broadcasting
  lambdas; verified first that every call site is `?.invoke()` (grep), so a nulled bridge is a
  safe no-op — the cleared VM no longer pins the Activity.
- **§3.3 `realUser`**: re-parsed the user JSON from prefs with Gson **on every read** and was not
  observable — HomeScreen read it during composition (stale developer-badge risk + per-frame
  deserialization). Now a StateFlow; `actingRole` (same finding class, read in the same dialog) got
  the same treatment. HomeScreen collects via `collectAsState` following the AppNavigation idiom;
  side effect: the dev-acting dialog's checkmarks now update live while open.

**Bug R8-B — found during this chunk, unlisted by the review (it audited SessionManager, not the
ViewModel's own prefs):** `AttendanceViewModel.syncLocalSession` — called after every successful
login, OTP-verify, and register-verify — wrote the **real backend JWT to plaintext**
(`ble_attendance_prefs`) as a "legacy fallback", directly beside the encrypted copy that
`AuthRepository.applyAuthenticatedSession` had already made. Every production login leaked the
bearer token to unencrypted disk storage until logout. Fix: the token write is gone (plaintext
prefs keep only display metadata: name/role/roll); `hydrateSessionFromPrefs` is now a **one-time
migration** that adopts a found legacy token into the session and **destroys the plaintext copy
immediately**. Honest caveats recorded in code: the migrated user record is best-effort (email
empty, id from roll), and the next real login replaces it with the backend profile.

**Chunk-7 promise completed:** `logout(notifyServer = true)` fires `POST /api/v1/auth/logout`
(new endpoint in `AttendanceApiService`) before the local teardown, passing the Authorization
header **explicitly** — AuthInterceptor reads the session lazily at request time, and `endSession()`
would have cleared the token before the coroutine fired, silently sending an unauthenticated logout.
The 401-expiry path calls `logout(notifyServer = false)`: the server already revoked that token —
that is why the 401 happened. best-effort everywhere: local logout never depends on network. A
second sign-out path was also found routing around all of this — HomeScreen's account-dialog
Sign-Out called `SessionManager.endSession()` directly (no VM cleanup, no revocation) — now plumbed
through `viewModel.logout()` via AppNavigation.

**Honest notes:**

- **Self-caught defect in my own first shape:** the permission-replay initially invoked the pending
  action directly on grant — skipping the adapter check the normal path would have run — so a
  Bluetooth-off device would replay into a scan that can never succeed. Fixed by declaring the
  bluetoothManager remember before the launchers and replaying through the same gate. Record kept
  because it is exactly the "fix introduces a subtler bug" class worth watching for.
- Keystore-broken devices now get exactly the review's "stay logged in for this session only":
  token RAM-only until process death, wipe of the old plaintext file, log the downgrade.
- `markLoading()` leaves `_realUser`/`_actingRole` stale by design (transient Loading while login UI
  shows; replaced on next startSession) — noted to avoid confusion, no action.

---

## Chunk 9 — URL single-source ✅ (+ bug R9-A found & fixed)

**Scope:** review §3.4 — three URL sources of truth collapsed into one owner
(`ServerConfigManager`); hand-rolled boot filters deleted; hardcoded basement-LAN quick button
deleted. Plus one UI bug found during the chunk (R9-A).

**Receipts:**

- `./gradlew :app:assembleRelease` → **exit 0**, APK **3,783,888 bytes**.
- **Constant sweep:** zero live references to `10.115` / `:53317` / `10.0.2.2` / `:8000` across all
  Kotlin (the single grep hit is the deletion comment itself). Both rogue sites were
  `AttendanceViewModel:262` (boot-time filter ad-hoc veto) and `AttendanceScreen:508-512` (LAN
  button compiled into release UI).
- Diff: 2 files **+24/−18** (WebView unchanged; no module gradle changes needed —
  `feature/attendance` already had `buildConfig = true`).

**What was actually wrong (discovered while consolidating):** both owners pointed at the SAME
SharedPreferences file AND key (`ble_attendance_prefs/server_url`) — the duplication was not two
files but **two writers and two readers with different adjudication rules fighting over one key**:
ServerConfigManager normalized scheme on write; the ViewModel re-read the raw key at boot,
vetoed "suspicious" URLs with string contains-checks, re-persisted through a second writer, and
its setter also persisted without scheme normalization (a raw host like `10.115.186.134:53317`
saved exactly as typed by the VM path). Whichever ran last won; every boot re-adjudicated.

- **Deletion is behavior-safe** (verified, not assumed): `HostSelectionInterceptor` rewrites every
  request's Host at *request time* from `ServerConfigManager.currentHttpUrl` — that includes all
  `AttendanceApiClient`/`LibraryApiClient` traffic; `AttendanceApiClient.baseUrl`'s own delegate
  getter reads `ServerConfigManager.currentBaseUrl` and setter persists through it. The VM's boot
  block re-dictating the URL was redundant with `ServerConfigManager.init` (which runs first in the
  same init) and active harm whenever the two disagreed — every disagreement was a future
  "401 from nowhere" bug, exactly as §3.4 predicted.
- **LAN quick-button deleted outright** (§3.4's remedy; not merely debug-gated): a release build
  shipping a fixed basement-IP button is a hard-coded redirect into a private network. The Cloud
  Render shortcut stays but references `ServerConfigManager.DEFAULT_BASE_URL` — no fresh literals;
  the search field remains for any needed custom target.
- **R9-A (found in this chunk):** the `AuthServerDialog`'s **Log Out button also committed the URL
  field** (`onUpdateServerUrl(serverUrlInput)` inside the logout handler) — pressing Log Out with
  a half-typed or stale LAN value silently switched the backend, stranding a logged-out user on
  the wrong server with no way to change it back before logging in elsewhere. Removed; the register
  flow keeps its URL apply (deliberate server-switch before creating an account).

**Honest caveats:**

- The deleted filters' legitimate job (deflecting a persisted dev URL) now lives with the single
  owner (`formatBaseUrl`: private-range → http, otherwise https). Side effect of the §3.4 remedy:
  a user-typed dev URL can now legitimately persist across restarts — that IS the single-owner
  policy. The review's **§1.7.3 (cleartext-HTTP hardening: `usesCleartextTraffic="true"` app-wide +
  `HostSelectionInterceptor` rewriting every request, sending Authorization over plain http to any
  host) was verified P1 but never chunked** — user later reviewed it deliberately and accepted
  keeping as-is (see the consciously-accepted note in the rejected list below).
- VM `setServerUrl` remains as a thin façade only because `AuthServerDialog` already binds to it;
  its body is now the single delegate assignment.

---

## Chunk 10 — Docker/infra hardening ✅ (+ a self-caught assertion slip)

**Scope:** review §1.8 + §3.6 + the chunk-3 deferral — multi-stage non-root Dockerfile with
`--proxy-headers`, exact dependency pins, Postgres 5432 closed to the host, `DATABASE_URL`
placeholder now inside the production guard, and the single-worker truth codified (startup
warning + README banner).

**Receipts:**

- pytest **72 passed** (+2) in 36.59s — the new guard test (DSN placeholder refused,
  generated-credential DSN accepted, empty DSN refused in production) and the compose test
  (db publishes **no** `ports`, exposes 5432 on the compose network only; YAML parses clean).
- **Pins receipt:** `uv pip install --dry-run -r requirements.txt` → "Would make no changes" —
  every exact pin is already satisfied in the venv the 72-test suite runs against, so the pins
  ARE the verified set (fastapi 0.159.0, uvicorn 0.54.0, sqlalchemy 2.1.6, asyncpg 0.32.0,
  opencv-python-headless 5.0.0.93, …; `psycopg[binary]` previously had NO version at all).
- **CMD flags verified locally:** `uvicorn --help` on the pinned 0.54.0 confirms
  `--proxy-headers` and `--forwarded-allow-ips` exist as used.
- **Docker build NOT executed by me — honest limit:** the daemon socket is permission-denied for
  this user (`ERROR: permission denied … /var/run/docker.sock`), so the image-build receipt must
  be produced user-side: `cd backend && docker build -t ble-attend-backend:chunk10 .` — then spot
  check `docker run --rm ble-attend-backend:chunk10 id -u` → expect `1000` (or similar non-zero),
  and `docker run --rm … which gcc` → expect empty. Diff: Dockerfile rewritten (multi-stage:
  builder compiles wheels into `/install`, runtime copies to `/usr/local` and never sees
  build-essential; `useradd nonroot` + `chown /app`; `--proxy-headers --forwarded-allow-ips="*"`),
  requirements.txt re-pinned, compose 5432 closed, config/main/tests/README edits.

**Notes (honest):**

- **Self-caught slip in my own new test:** I first asserted the FULL password string
  (`change_this_secure_password`) inside the RuntimeError text — but the guard's chunk-3
  convention (pinned by the catalogue test) reports the matching **marker token** (`change_this`)
  only, deliberately never echoing the full suspect value. The red was mine to fix; the guard
  needed no change. Also fixed in the same pass: a bare `config.database_url_reason() is None`
  expression with no `assert` (would have silently checked nothing).
- **The deferred §1.1 gap is now genuinely closed end-to-end:** a compose deployment with rotated
  JWT/faculty secrets but the shared placeholder DB password now refuses boot in production with
  "DATABASE_URL contains placeholder marker 'change_this'" — before chunk 10 it sailed through
  and connected with a placeholder-clad database over a *published* host port.
- **DATABASE_URL policy accounting:** production-empty DSN → refused (a config error, not a
  default); placeholder marker in DSN → refused with the marker named; real DSN → fine. Permission
  here means "consistent with the rest of the guard", not a blanket "DATABASE_URL must be exact".
- **Single-worker guard is a WARNING, not a kill switch** (matching the review's own wording):
  production still boots if an operator forces `WEB_CONCURRENCY=4`, but the log names the exact
  consequences (split login budgets, stranded staged check-ins) and the actual fix (Postgres/Redis
  structures). README deployment notes repeat it in operator language.
- compose keeps the backend's 8000 published (that is the product surface); only the database
  lost its host port. Placeholders in compose stay — the guard now refuses them in production,
  which was always the intended pairing (chunk 3's comments already said so).
- README was a 5-line stub; a "Deployment notes (backend)" section was appended. Pre-existing
  typos in the user's stub left untouched (user content).

---

## Chunk 11 — P2 perf pack ✅ (+ two self-caught build mistakes + a camera refactor)

**Scope:** review §2 (older-hardware pack) — BLE binder-thread prefilter, vsync-paced game loop,
face encode off the main thread, effective 44.1 kHz audio rate, thread-priority corrections,
busy-handshake un-blacklist, probing wake lock, CameraX version alignment. Deferred on purpose:
scan-mode switching (§2.1) and the `peerBatchJob` `while(true) delay(300)` rewrite (§2.2) — recorded
honestly, they stay explicit follow-ups.

**Receipts:**

- `./gradlew :app:assembleRelease` → **exit 0**, **BUILD SUCCESSFUL in 35s**, APK **3,783,887**
  (chunk 9 was 3,783,888 — byte-identical shape, no dependency bloat despite CameraX 1.4.0 claim).
- Backend untouched this chunk; 72 passing from chunk 10 stands.
- The review's claimed `:339/:424` MAX_PRIORITY sites were line-number noise; real audit found the
  priority setters with grep across all of feature/ (three sites, all now handled — see below).

**Diff map (what changed where):**

- `BleScanner.kt` — binder-thread gate before ANY allocation/posting: `rssi < -95` heap cut +
  `looksLikeAttendanceMessage()` shape gate (`S:`/`SESSION:`/`V:` prefixes or exactly 8 chars —
  verified superset of every shape `AttendanceViewModel.onPacketReceived` ever accepts; its only
  consumer, teacher side included). Foreign advertisers and fragments now never allocate a String
  or post a runnable — the review's "thousands of main-looper posts/sec on a weak device" dies here.
- `OrbPoppingGame.kt` — pacing gate `delay(16L)` → `withFrameNanos { }` (imports swapped).
  Game math still uses `System.currentTimeMillis()` — the recorded spawn-times semantic is
  unchanged; only the *pacing* moved to vsync, so 60 Hz no longer drifts to 2-3 skipped frames
  and 120 Hz stops double-charging recomposition.
- `BestFrameAnalyzer.kt` — new `takeBestCandidate()`: detaches the winning candidate under lock
  WITHOUT recycling its bitmap (ownership transfer — `getBestCandidate()` shares the reference and
  `reset()` would have recycled a bitmap still needed for encoding).
- `PassiveFaceCaptureManager.kt` — split `stopAndExtractResult()` into live-callable halves:
  `stopCameraAndTakeCandidate()` (main-thread CameraX teardown; camera unbind is main-thread-bound
  and `stopCamera()`→`reset()` recycles the stored bitmap — so teardown stays here, candidate
  detached FIRST) + `encodeCandidateResult(candidate)` (off-main JPEG+Base64). Source bitmap
  recycled in both outcomes — same lifecycle the old single method had via teardown, no leak added.
  Old `stopAndExtractResult()` retained verbatim for the non-critical paths.
- `StudentSection.kt` — `onComplete` split: `isGameOver = true` (immediate — scorecard swaps in),
  candidate steal on main, encode `withContext(Dispatchers.Default)` inside `rememberCoroutineScope`
  (`gameScope`), then ALL remaining state writes + `onNext()` hop back `withContext(Dispatchers.Main)`.
  The tens-of-ms JPEG encode no longer runs synchronously inside the composition callback.
- `UltrasonicToneSynthesizer.kt` — `EFFECTIVE_SAMPLE_RATE` (@Volatile var, private set, init 48000) +
  `resolveEffectiveSampleRate(context)`: queries `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` once,
  accepts exactly 44100|48000, else falls back to 48000 (never worse than before).
- `UltrasonicProximityManager.kt` — manager `init` calls the resolver (the handshake's only owner →
  guaranteed earliest hook); all four `DEFAULT_SAMPLE_RATE` sites (diagnostic reader, `emitChirp`,
  `listenForChirp`, `collectBurstEnsemble`) now read `EFFECTIVE_SAMPLE_RATE`, so a 44.1 kHz-locked
  old phone no longer dies silently at `AudioRecord` init (`createAudioRecord` retries *sources*,
  never *rates*); Goertzel coefficients accept either verified rate. Thread priorities corrected:
  diagnostic reader gets real Linux niceness via `android.os.Process.setThreadPriority` INSIDE the
  thread body (`THREAD_PRIORITY_URGENT_AUDIO`), while the two sleep-loop emitter threads drop the
  java-side `Thread.MAX_PRIORITY` (they spend ~99% asleep — max priority bought only contention).
- `UltrasonicProximityManager.kt` (second fix) — `emitBurstSequence`/`collectBurstEnsemble` return
  **Boolean**: busy → `false` and, in the ensemble's busy branch, the fabricated emptySummary is
  GONE (it used to post a fake verdict mediately, marking the pair handled when nothing happened).
  Permission-missing keeps the empty-summary + `true` shape (not retriable by definition — avoids
  an un-blacklist loop on a permanently denied mic).
- `AttendanceViewModel.kt` — handshake consumes the Booleans: on `false`, `testedUltrasonicPeers
  .remove(peerId)` (hop to `Dispatchers.Main` — every other touch of that set is main-thread;
  the un-blacklist keeps it that way) + one log line, so the peer retries on its next
  close-proximity sighting instead of being frozen forever. Wake lock: `powerManager` field +
  `acquireProbingWakeLock()`/`releaseProbingWakeLock()`; PARTIAL, tag `attendance:peer_probe`,
  acquired once per discovery window at its start, hard TTL **105_000L** as the crash-safety belt,
  explicit releases in `stopStudentScanning()` and `resetStudentSession()`.
- `feature/attendance/build.gradle.kts` — CameraX 1.3.1×4 → 1.4.0 (mirrors what `:feature:library`
  already resolves; two majors' worth of artifacts no longer double-resolve).

**Notes (honest):**

- **Two build mistakes, both mine, both caught by kotlinc and fixed before the green run:** (1) a
  botched edit around `stopBeaconEmitter()` that dropped the function's signature lines mid-file —
  repaired in the same pass, the region was re-read and verified intact afterwards; (2) a plain
  `.start()` used where a `Thread` VALUE was needed — `Thread { }.start()` returns Unit so
  `beaconThread = Thread { … }.start()` is a type error; fixed with `.also { it.start() }` on both
  emitter threads. First build: exit 1 with exactly these two type errors; second build: exit 0.
- **`takeBestCandidate` needed the inverse of the obvious edit.** Moving "just the encode"
  off-main with a naive `withContext(Default) { stopAndExtractResult() }` would have been WRONG
  twice: CameraX `unbindAll()` is main-thread-bound (would throw), and `stopCamera()`→analyzer
  `reset()` RECYCLES the bitmap while the background encode would still be reading it. The split
  with an ownership-transfer detach is the only correct shape; recorded why in both doc comments.
- **Wake-lock TTL is 105 s, deliberately above the 25 s discovery window** — it must cover the
  BLE window *plus* every device-pair ultrasonic handshake issued while `PROBING_NEIGHBORS`
  persists (students finishing the game at different speeds), without an ever-growing explicit
  bookkeeping; timeout = the safety net, explicit release = the fast path.
- **Un-blacklist on busy keeps a bounded retry loop:** while a burst is airborne, the next sighting
  re-queues the handshake (one log line each, a few Hz at most); when free, it runs. Perf is
  bounded because `emitBurstSequence` rejects in O(1) without touching any audio object.
- **`withFrameNanos { }` returns the frame's time in nanos which we discard on purpose** — the
  loop re-reads `System.currentTimeMillis()` every iteration; recorded in-code that the game's
  time SOURCE has not changed, only the sleep mechanism.
- CameraX alignment kept the APK byte-flat: the module's compiled references stay the same; the
  runtime now resolves ONE CameraX version instead of two.

---

## Pending chunks (map as agreed)

- **4b** — R2 finalize lock (stage/pop window; highest backend risk) ← *done*
- **5** — Honesty statuses: `face_verified` truthful, lab routes → real 5xx, `total_verified` DB
  recount, body cap + decode bounds (test assertions get updated — expected churn) ← *done, no churn needed*
- **6** — Android build security: `BuildConfig.DEBUG` log gate, ProGuard Log strip, CI-only keystore
  fail-fast ← *done (dex-level strip proof + CI exit-1 proof)*
- **7** — Auth lifecycle: `token_version` + `is_active` + logout (missing claim == v0 for deploy
  safety; DDL `ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version`) ← *done (red-first: 3 designed
  losses proven; deploy-safety guard green on both sides; 70 passed)*
- **8** — SessionManager fail-closed (no token in plaintext fallback), permission UX, bridge
  clearing, `realUser` → state ← *done (+ bug R8-B plaintext-JWT shadow-store found & fixed; app
  logout wires the chunk-7 route)*
- **9** — URL single-source (`ServerConfigManager` only; delete VM:262 filters + LAN quick button) ← *done
  (+ bug R9-A: Log Out silently committing the URL field — fixed; §1.7.3 flagged → see rejected list)*
- **10** — Infra: Docker non-root/pinned/`--proxy-headers`/close 5432, single-worker warning,
  **DATABASE_URL placeholder coverage** ← *done (docker build unrunnable here — user-side receipt
  documented)*
- **11** — P2 perf pack (BLE prefilter, `withFrameNanos`, face-extract off main, 44.1k fallback,
  wake lock, CameraX alignment) ← *done (build exit 0; scope-deferred items recorded:
  scan-mode switching, peerBatchJob rewrite)*

**Rejected remedies (remain rejected):** 401-retry POST→logout (would reintroduce the mid-lecture
logout bug); rotating salts as P1 (optional P2 — the mesh gate already defeats foreign/remote
verification and past-session replay is dead).

**Consciously accepted (user decision, §1.7.3 — cleartext HTTP + host rewriting):** the app stays
permissive on plain `http://`. Reason recorded honestly: self-hosted LAN deployments
(`SelfHostingGuide.md`, `http://10.x…:53317`) cannot get a real TLS certificate — plain http is
the only way they work, and full HTTPS-only would kill that feature while stripping the JWT over
http would break LAN logins. Residual risk accepted: whenever the URL field carries `http://`,
the `Authorization` token and uploaded face images travel unencrypted on that network
(default Render URL is `https://`, unaffected). No code change.

**Standing deferrals:** Alembic, de-monolithization, Neon password rotation (→ college-server
migration), liveness-on-enrollment (needs a product call).

**User-side pending:** rotate the leaked real secrets on Render (faculty secret/captcha-adjacent
values recoverable from git history; `fff791e6…` was still the config default until chunk 3 made
that unbootable in production mode).
