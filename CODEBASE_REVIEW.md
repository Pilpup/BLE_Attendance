# BLE Attendance — Production Readiness Review

**Scope:** Full deep-dive — FastAPI backend (~3.5k LOC Python), multi-module Android app (~10.5k LOC Kotlin), native DSP (`acoustic_dsp.cpp`), build system, deployment (Docker/Render/Neon), and the BLE + ultrasonic anti-proxy protocol.

**Reviewed:** Nov 2026 · git `92195b8 "bug fixes"`

The three review goals:
1. **Security hardening** → §1
2. **Older-hardware optimization** (low-end / older Android phones) → §2
3. **Production efficiency — "no false errors"** → §3 (spurious 500s, silently dropped submissions, operations that report success when they failed, races that turn valid traffic into errors)

---

## 0.0 Verifier annotation — claims re-checked against this exact tree (GLM, Nov 2026)

Every questionable claim was independently verified in-source before marking (same protocol as the earlier external review). Verdict: **this review is of unusually high quality — all seven P0s and all four races verified true; nothing material was found wrong.** Where a *remedy* is disputed, obsolete, or already done, it is marked inline (§1.6, §3.2, §4, roadmap).

**✅ Verified true — with in-source receipts:**

- **All seven P0s confirmed.** 1) no rate limiter on `POST /auth/login` and `verify_password(...)` runs inline on the event loop, with `not user or not verify_password(...)` short-circuiting so unknown emails respond measurably faster (enumeration); register's `hash_password` is equally blocking. 2) `GET /sessions/{id}/flagged-faces` is `require_teacher` + session-lookup only — **no owner check** — backed by the in-memory `_flagged_pairs` store (`face_engine.py:559`). 3) `NetworkClient.kt` registers `HEADERS`-level logging unconditionally, and no ProGuard Log strip exists. 4) release signing falls back via `else { initWith(getByName("debug")) }`. 5) `.env.example` history leak **pickaxe-confirmed** (commits `3cc4849`, `51d6346`, `ae93138`); note the faculty secret `fff791e6…` is *still the `config.py` default in the working tree today*. 6) grace-path insert has no `IntegrityError` handling and can violate `unique_session_ble_device` (`models/attendance.py:175`) → 500 on a valid retrying student. 7) compose placeholder secrets (`change_this_…`) pass today's two-default guard — **a real open gap in the guard added in this repo's fix batch**; the §1.1 deny-list extension is the correct fix.
- **All four races (§3.1) confirmed:** R1 above; R2 stage/pop race is real (two `pop_submissions` sites `:830/:1363`; narrow window but silent-loss shape); R3 `list_courses` calls `_get_or_create_mock_context(db)` unconditionally in prod (fabricates a `Prof. Alan Turing` teacher; login as that teacher then 500s via bcrypt `Invalid salt`); R4 register-verify has no duplicate re-check before insert and session-create's `Resolve or auto-create course` (`attendance.py:569-598`, `course_code` is `unique`, `models:40`) 500s on concurrent same-code creates.
- **Auth/protocol/honesty items confirmed:** `is_active` never consulted despite the "deactivated" 401 text; OTP `/otp/resend` accepts `purpose` verbatim and resets `attempts` — **actually worse than stated: no whitelist makes it an unauthenticated email-spray channel to arbitrary addresses**; `GET /courses/{id}/syllabus` truly has no auth dependency; library reserve has no per-user cap; the body-size gate lives inside the face engine while uvicorn buffers whole bodies first; `get_db` has no rollback; `submit-neighbors` reports `"face_verified": True` without any check; lab routes return 200-with-error-string; `total_verified` can be clobbered by finalize rewrites (grace-path increments at `:931/:1273/:1522`) — DB recount fix is sound.
- **Android/infra items confirmed:** SessionManager silently falls back to plaintext prefs (`catch (_: Exception) → FALLBACK_PREFS_NAME`); `usesCleartextTraffic="true"` app-wide; hardcoded LAN button `http://10.115.186.134:53317/` compiled into release UI (`AttendanceScreen.kt:499-503`) plus VM:262 hand-rolled filters — three URL sources of truth confirmed; Docker runs as root, no `--proxy-headers`, unpinned `>=` floors; in-memory singletons (submission buffer, limiter, face registry) force single-worker — correct, deserves codification; no CI (only `keep_alive.yml`); stray `backend/ble_attendance.db` (131 KB).
- **Perf/build items confirmed:** per-packet String + main-thread post in `BleScanner`; `OrbPoppingGame.kt:274 delay(16)` loop; face extraction on main at game end (`StudentSection.kt:256`); `Thread.MAX_PRIORITY` audio threads; `isBeaconEmitting` guard drops busy-state 3.6 s bursts with the blacklist retained; 48 kHz-only handshake; `android.enableR8.fullMode=false`; CameraX skew (attendance `1.3.1` vs library `1.4.0`); no BOM/version catalog; committed `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk`; MainActivity permission launchers literally `{}`-discard results (:80-86); HomeScreen reads `SessionManager.default.realUser` during composition (`app/.../shell/HomeScreen.kt:302,370`); "3MB" error-text vs 4 MB const (`face_engine.py:354`).

**⚠️ True observations with disputed remedies (marked inline):** the AuthInterceptor 401-retry item (§3.2 / roadmap #17) — the proposed POST→logout remedy would reintroduce the mid-lecture logout fixed intentionally in this repo's fix batch; keep the retry, adopt only the uniform-`NetworkResult` half. Rotating salts (§4 / §1.6.1) are a sound spec but ROI is oversold — the mesh gate already leaves foreign/remote submissions unverified and past-session replay is dead (finalized gate); rotation shrinks in-session harvest windows at real plumbing/UX cost → treat as **optional P2**; the cheap wins there are §1.6.2 (V: → hint only) and §1.6.4 (hide session ID).

**✅ Already implemented in this batch (skip re-work):** §1.6.3 "never self-certify" (M3-lite mutual-claims materialization with JWT-bound keys — one-sided claims already logged-and-ignored); 24 h token TTL; the production-secrets guard itself (needs only the §1.1 deny-list extension above).

**Deferred by standing decision:** Alembic / de-monolithization / Neon password rotation → college-server migration. Liveness-on-enrollment needs a product call first.

**Addendum — final-round verifications:**
- §3.3 HomeScreen claim is right and *understated*: `realUser` (`SessionManager.kt:41-49`) is not Compose state — it re-parses JSON from prefs with Gson **on every read**, so composition-rate reads are both stale-prone and a per-frame deserialization cost.
- §0.2 "OTP attempts lost-update" confirmed TRUE (`otp_record.attempts += 1` read-modify-write, no atomic update / row lock, `auth.py:193`).
- §2.5 LibraryScreen claim is directionally right but imprecise: the file mixes BOTH patterns (LazyColumn imported and used; two `verticalScroll` Columns at :414/:501).
- §1.8 "doubled DROP NOT NULL (two occurrences)" — actual count is **three**; pattern true, count off.
- §0.2 Activity-bridge leak confirmed as stated: `onCleared()` (`VM:1595-1604`) cancels jobs and releases proximity but **never nulls the Activity-wired scanning lambdas**.
- Unmeasured estimates (baseline-profile 20-40 %, 98 dB ensemble constants) were accepted as engineering estimates, not verified.

**Net: adopt the P0/P1 roadmap wholesale except the ⚠️ items. The review's *facts* were right ~95 %; its *remedies* had two genuine errors (recur main logged above) and one inflated priority (rotating salts) — the honest classification, not "nothing wrong".**

---

## 0. Executive Summary

| Area | Verdict |
|---|---|
| Backend architecture | Solid concept; async discipline mostly correct; **in-memory singletons (submission buffer, rate limiter, face registry) force single-process deploys** |
| Backend security | Good bones (bcrypt, 2FA-OTP, salt check, ownership checks on finalize/cancel) but **no login rate limiting, no logout/revocation, IDOR on biometric data, event-loop-blocking bcrypt, `.env.example` git history leaked older real secrets** |
| Protocol (BLE+ultrasonic) | Corroboration-lite. Plaintext, unauthenticated beacons and verdicts; replayable; security truly rests on server mesh-audit + face dedup. Time-rotating salts would close most of it |
| Android security | **Release builds log `Authorization` headers**; silent plaintext-prefs fallback; app-wide cleartext-HTTP allowed; release falls back to debug keystore |
| Older hardware | Posture is fundamentally good (minSdk 24, legacy adv APIs, native DSP, allocation-light Goertzel). Main costs: allocation churn in the BLE scan path, 60-fps coroutine game loop, main-thread face encoding, hard-coded 48 kHz |
| False errors | Several real races cause **spurious 500s and silently lost attendance** (grace-window insert, stage/pop race, register race, course auto-merge race) plus UI patterns that hide real failures or invent fake ones |

**Bottom line:** closest to production for a small college pilot, but it should not sign real attendance records until the P0 list below is done — roughly 1.5–2 days of focused work.

## 0.1 P0 — must fix before real deployment

1. **Backend:** no rate limit / lockout on `POST /auth/login` → unlimited online password guessing (`backend/app/routers/auth.py:237`).
2. **Backend:** `verify_password` / `hash_password` (bcrypt cost-12) run **on the event loop** (`auth.py:248`, `auth.py:109`) → a burst of logins freezes every other request → mass timeouts = "false errors" under load.
3. **Backend:** `GET /sessions/{id}/flagged-faces` has **no session-owner check** (`attendance.py:1594`) — any teacher can read any other class's retained **base64 face photos**. IDOR of biometric data.
4. **Android:** `HttpLoggingInterceptor` at `HEADERS` level runs in **release** builds (`core/network/.../NetworkClient.kt:18`) — Bearer tokens go to logcat.
5. **Android:** release signing silently falls back to the **debug keystore** (`app/build.gradle.kts:30-32`) — a shipped "release" APK is trivially forged and irrevocable.
6. **Backend:** `.env.example` git history contains an older real `FACULTY_SIGNUP_SECRET=fff791e6...` and the current `config.py` default JWT secret — rotate anything that ever used these.
7. **Backend:** grace-path insert races on `unique_session_ble_device` → IntegrityError → **500 on a valid student's check-in** (`attendance.py:1248-1309`) — the single biggest "false error" generator.

## 0.2 P1 — production quality

- Login token revocation (`user.token_version` column + `jti`), `is_active` actually enforced (`security.py:106` claims "account deactivated" but never checks).
- OTP `purpose` cooldown bypass (`auth.py:369`) — client-supplied `purpose` resets/gets buckets freely.
- `list_courses` inserts mock data in production on every `GET /courses` (`attendance.py:290`).
- 401-retry in `AuthInterceptor` re-transmits the request body (face JPEG) a second time.
- Cleartext-HTTP posture + `HostSelectionInterceptor` rewriting all traffic to whatever URL the user typed.
- Turnkey races: register-verify duplicate insert; session-create course auto-merge; OTP attempts lost-update.
- Docker: container runs as root, un-pinned requirements, no `--proxy-headers`, published 5432, placeholder secrets pass the "production" guard.
- Android leak: Activity-scoped `BleAdvertiser`/`BleScanner` wired into a surviving ViewModel via mutable lambdas, never cleared.
- Honest statuses: lab routes return 200-with-error-string; `face_verified: true` reported without a check; straggler submissions can report "success" while being dropped.

## 0.3 P2 — quality / older-hardware polish

- `while(true) delay(300)` polling loops; per-BLE-packet main-thread posts without RSSI pre-filter; 60 fps recomposition game loop; main-thread JPEG encode at game end.
- 48 kHz hard-coded (a real subset of old phones only supports/locks 44.1 kHz).
- No baseline profile, R8 full mode off, version skew (CameraX 1.3.1 vs 1.4.0 in two feature modules), Compose deps unmanaged (no BOM), no CI build/test workflow.
- `LibraryScreen` uses `verticalScroll` Column instead of LazyColumn.
Also, `gradle.properties` commits a machine-specific `org.gradle.java.home` path.

---

# §1 Security Deep-Dive & Hardening

## 1.1 Backend secrets — status and rotation

Facts established:

- `backend/.env` **is properly gitignored and never committed** (verified: `git check-ignore backend/.env`, absent from history) — contains live SMTP app-password + JWT secret; rotation is only needed if the machine/deploy share is wider than the repo owner.
- **But git history of `backend/.env.example` carried older real values**: `FACULTY_SIGNUP_SECRET=fff791e68ec68434901e256c293f0724` (matches the still-present `config.py` default) and a JWT secret that is byte-identical to the `config.py` default literal — meaning "the default is the secret" was true for some period. Anyone with repo reads who captured this can mint teacher JWTs for any service that ever stuck to defaults.
- The boot guard `validate_production_secrets()` (`config.py:38-58`) only refuses the **two hardcoded defaults**; `docker-compose.yml:33-38` ships placeholder secrets (`change_this_...`) that are not in that set, so a compose deployment passes the guard with repo-public secrets. `.env` is loaded **relative to CWD** (`SettingsConfigDict(env_file=".env")`), brittle under systemd.

**Actions (P0):**

1. Rotate `FACULTY_SIGNUP_SECRET` and, if ever used, the JWT secret; give the app a fresh `JWT_SECRET_KEY` (256-bit hex) via environment variable only.
2. Strengthen the guard to refuse **any** secret shorter than 32 chars / any value in a deny-set that includes the compose placeholders:
   ```python
   MIN_SECRET_LEN = 32
   KNOWN_PLACEHOLDERS = {"change_this_to_a_random_hex_key_32_bytes", "college_faculty_passcode_2026",
                         "CSE_FACULTY_2026", "fff791e68ec68434901e256c293f0724", "ble-mesh-attendance-secret-key-super-secure"}
   # valid: len >= MIN_SECRET_LEN and value not in KNOWN_PLACEHOLDERS and value != default
   ```
3. Resolve `.env` path robustly: `env_file=Path(__file__).resolve().parent.parent / ".env"` or prefer a `--env-file` passed by the service manager.
4. Gmail app-password in `.env`: treat as live; restrict the Gmail account, rotate it, and prefer Brevo (already integrated, `email.py:26`) as the primary sender with SMTP as fallback.

## 1.2 Auth flow — fixed gaps

Current design is genuinely better than typical student projects: mandatory 2FA email OTP on every login, 6-digit `secrets.randbelow` codes, constant-time compares (`auth.py:192`, `auth.py:326`), 5 attempts → record deleted, per-(email,purpose) 45 s resend cooldown, expired-OTP sweeper. Remaining gaps, in priority order:

### 1.2.1 No login rate limiting / lockout (P0)
`rate_limiter.py` exists and works (sliding window, in-memory) but is wired **only** to `submit-neighbors` (`attendance.py:1051`). Password guessing is unlimited.

```python
# app/services/rate_limiter.py — add a general limiter
login_rate_limiter = SlidingWindowRateLimiter(max_events=10, window_seconds=300)  # per identity

# POST /auth/login, before any work:
if not await login_rate_limiter.check(f"login:ip:{client_ip}"):
    raise HTTPException(429, "Too many attempts. Try again shortly.")
if not await login_rate_limiter.check(f"login:email:{payload.email.lower()}"):
    raise HTTPException(429, "Too many attempts for this account.")
```
Plus progressive lockout: after 5 failed logins in 15 min, require the OTP path only (which they'd hit anyway — so simply **drop the password check to a fixed cost always**): see 1.2.2.

### 1.2.2 Email-enumeration timing + blocking bcrypt (P0)
When the email is unknown, bcrypt is skipped and the response is measurably faster. Also bcrypt (~150–400 ms) runs **on the event loop**, stalling all concurrent requests.

```python
# POST /auth/login
user = (await db.execute(select(User).where(User.email == payload.email.lower()))).scalars().first()
# Always spend the same CPU path:
pw_hash = user.hashed_password if user else BCRYPT_DUMMY  # module-level dummy hash
valid = await asyncio.to_thread(verify_password, payload.password, pw_hash)
if valid and user:
    ... send OTP ...
else:
    raise HTTPException(401, "Invalid credentials")   # identical timing + message
```
Apply `asyncio.to_thread` to `hash_password` in register (`auth.py:109`) too.

### 1.2.3 Token lifecycle: no logout, no revocation, `is_active` never checked (P1)
- Only claim = `exp` (24 h). No `iat`/`jti`; `security.py:106` — the 401 message literally says "account deactivated" but `User.is_active` is **never consulted**.
- Cheap, safe pattern (no Redis required):

```python
class User(Base):
    token_version: Mapped[int] = mapped_column(Integer, default=0, server_default="0")

# create_access_token: claims = {"sub": ..., "role": ..., "tv": user.token_version}
# get_current_user: after DB fetch →
#   if not user.is_active: 401
#   if token_payload.get("tv") != user.token_version: 401 (revoked)
# POST /auth/logout → user.token_version += 1  (revokes all existing tokens)
```
Bump `token_version` on: password change, "sign out everywhere", deactivation. The 24 h access token then becomes revocable per-account.

### 1.2.4 OTP resend cooldown bypass (P1)
`POST /auth/otp/resend` takes `purpose` verbatim from the client (`auth.py:369`) — every new string opens a fresh 45 s bucket and the resend **resets the 5-attempt brute budget** (`auth.py:395`), so an attacker can cycle purposes to spray verification emails through your SMTP/Brevo account.

```python
ALLOWED_PURPOSES = {"register", "login"}          # whitelist, reject others with 422
if purpose not in ALLOWED_PURPOSES: raise HTTPException(422, "Invalid purpose")
# ... and on resend: delete the old record and create a NEW one with attempts = 0
# only for genuine resend; do not resurrect a record belonging to a different purpose
```

### 1.2.5 Test-environment bypass is a process-global flag (P2)
`is_test_environment()` keys on `PYTEST_CURRENT_TEST` (`security.py:20-21`); any token containing `mock` then returns the first TEACHER **with no credentials**. If that env var ever leaks into a prod container (CI leftovers, wrapper scripts), `Authorization: Bearer mock` = teacher. Recommend gating on `settings.APP_ENV == "test"` explicitly (pytest sets it in conftest) — cheap and removes the magic env var.

### 1.2.6 Role matrix inconsistencies (P2)
- `require_teacher` accepts TEACHER/DEVELOPER/ADMIN/STAFF/FACULTY (`security.py:125-130`), but `GET /courses/{id}/sessions` role-gates differently (`attendance.py:509`) and `GET /sessions/{id}` applies record-filtering only for the literal role `STUDENT`.
- Only STUDENT/TEACHER/STAFF are registrable — DEVELOPER/ADMIN/FACULTY are dormant privileged roles; fine, but write the matrix down in one place (`require_role(*roles)`) and use it everywhere to prevent drift.

## 1.3 IDOR / object-level access (P0–P1)

| Endpoint | Check today | Fix |
|---|---|---|
| `GET /sessions/{id}/flagged-faces` (`attendance.py:1594`) | `require_teacher` only → **any teacher can pull any session's retained base64 face photos** | Add the same owner-join check as `override_attendance_record` (`attendance.py:1630`): `session.teacher_id == current_user.id or current_user.role in ("DEVELOPER","ADMIN")` |
| `GET /sessions/{id}` (`attendance.py:1552`) | Full record list (student names + rolls) served to any teacher resolving a UUID/session-token they guess; students correctly filtered to own rows | Restrict: non-owner teachers get session metadata but not other students' `records` |
| `GET /courses/{id}/syllabus` (`attendance.py:350`) | **No auth dependency at all**; sibling routes all require auth | Add `get_current_user` (or explicitly document as public curriculum data) |
| Library `POST /books/{id}/reserve` (`library.py:226`) | No per-user cap; one account can drain the catalogue | `count(reservations where user && not cancelled) < 5` (configurable), 429 otherwise |

Ownership is correct on: cancel (`:678`), finalize (`:731`), override (`:1630`), units/topics (`:384`, `:449`) — worth keeping as the pattern. Note `sessions/{session_id}` also resolves by 4-hex-char `session_token` — collisions with 65k space are the reason finalize's 409 exists; treat session token as pseudo-public (never include student PII in that response shape for non-owners).

## 1.4 DoS-resistant input handling (P1)

- **No global body-size cap.** `submit-neighbors` accepts base64-in-JSON or raw-body; the gate (4 MB) lives *inside* `face_engine.register_and_check` (`face_engine.py:351,367` — error text still says "3 MB"). Uvicorn reads the entire body before any check → a 1 GB JSON string is a memory bomb.
  ```python
  # app/main.py — middleware
  MAX_BODY = 8 * 1024 * 1024
  @app.middleware("http")
  async def cap_body(request, call_next):
      cl = int(request.headers.get("content-length", 0))
      if cl > MAX_BODY:
          return PlainTextResponse("Payload too large", status_code=413)
      return await call_next(request)
  ```
- **Decompression bombs:** 4 MB PNG can expand enormously before you downscale to 640 px. Preflight on the encoded buffer (already have length) *and* after `cv2.imdecode` check `arr.shape` (e.g. reject > 4000 px on either side) *before* further processing.
- Lab routes `/lab/face-check|compare` return **HTTP 200 with `remarks=f"Processing error: {e}"`** (`attendance.py:1665,1695-1701`) — leaks internals and makes clients think success. Log server-side, return `500 {"detail": "Image processing failed"}`.

## 1.5 Concurrency & transactions

- SQLite is fine for tests; **`with_for_update()` is a no-op there** (`attendance.py:264-267`) — production must run PG (compose does) or the audit serialization assumptions silently vanish. Document in README.
- `get_db` has no exception-rollback. Make it explicit:
  ```python
  async def get_db():
      async with AsyncSessionLocal() as session:
          try:
              yield session
          except Exception:
              await session.rollback()
              raise
  ```
- `submit-neighbors` loads `selectinload(AttendanceSession.records)` for the whole session per check-in — acceptable at 70 students, but switch to a `SELECT count(*) ... WHERE verdict='VERIFIED'` shaped aggregate once records grow.

## 1.6 Protocol security (BLE + ultrasonic)

### Actual mechanics (verified)
- Teacher beacon: `S:<ID4>:<SALT4>` (+ optional semester) in **plaintext** service-data under SIG base UUID `00001234-…` (`BleConstants.kt:10`) — 16-bit-space UUID, any scanner can read it. Session ID and salt are each **4 hex chars = 65,536 states**, generated from truncated UUIDv4 (`AttendanceViewModel.kt:789-790`) — and the current ID is displayed in `cloudStatusMessage` on any attendee's screen.
- Student probe: `<ID4><shortId4>`, shape-validated only (`AttendanceViewModel.kt:1328-1334`).
- Air verdicts: `V:<id>:OK` — a single unauthenticated advert flips local UI phase (`:1276-1297`); the server is genuinely authoritative (poll loop), but a peer/bystander app can spoof the *UI*.
- Ultrasonic: 19.2 kHz Hann-windowed pure sine burst, 5-bin Goertzel at 48 kHz, guard-bin SNR — no sync word, no repetition code, no HMAC. Detection result is **self-reported** via headers.

### What actually stops cheating today
1. Salt must match server-issued salt (SALT_REJECTED exists), 2. server-side face dedup, 3. reciprocal neighbor mesh (`graph_engine`), 4. teacher override. The radio layer contributes proximity *evidence*, not proof.

### Hardening plan (fits the 24-byte legacy budget, no server changes beyond one column)
1. **Time-rotating salts (biggest win).** Teacher rotates `sessionSalt` every N minutes (e.g. 3): new random `SALT4` per window; app pushes the ordered list (or the hash chain) to the server on creation (`POST /sessions` accepts `salt_schedule`). Server accepts **only salts belonging to the current or immediately previous window** at submit-time (`SALT_REJECTED` logic already exists at `submit-neighbors`). Kills replay of yesterday's/other-room beacons and the classroom-display-ID harvest.
2. **Ignore `V:` verdicts for state changes.** Keep them as a UI hint only; require the server poll result to leave `WAITING_FOR_AUDIT`.
3. **Never self-certify.** Server should treat client-header flags (`X-Ultrasonic-…`, `ultrasonicProximityDetected`) as *weak claims*, never as confirmations in itself; the mesh audit + face dedup remain the gate. At minimum never raise a decisive verdict (`CO_LOCATED_SAME_DESK_PROXY`) solely from self-reported data without corroboration — check `graph_engine` input weighting to match.
   > ✳️ **GLM verifier: already implemented.** M3-lite reciprocity (`attendance.py:1216-1230`): one-sided claims are logged and ignored; the row only materializes for mutually-claimed pairs using JWT-bound keys. `graph_engine` never consumes ultrasonic input. Nothing to do.
4. Optionally hide the session ID from the status UI (show "Cloud session active ✔") — removes the easiest targeted-attack primitive for a classroom bystander.

What you get: a hallway spoofer can no longer reuse stale beacons; a replaying student cannot reuse another room's beacon beyond the salt window; proxy couples still need to defeat face-dedup + mesh inconsistency, which is the actual design goal.

## 1.7 Android security

### 1.7.1 Release token leak via logcat (P0)
`NetworkClient.kt:16-20` registers `HttpLoggingInterceptor(HEADERS)` unconditionally — prints `Authorization: Bearer …` for every request in released APKs.
```kotlin
val logging = HttpLoggingInterceptor().apply {
    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
            else HttpLoggingInterceptor.Level.NONE
}
```
And in `app/proguard-rules.pro`, strip `android.util.Log` in release:
```pro
-assumenosideeffects class android.util.Log { public static *** d(...); public static *** v(...); public static *** i(...); }
```
(Also note `AttendanceViewModel.kt:1235-1238` logs "Beacon candidate" — silent in release only if the above ProGuard strip is kept.)

### 1.7.2 Silent plaintext fallback for credentials (P1)
`SessionManager.kt:81-96`: any Keystore failure (corrupt keystore, vendor ROMs, OS upgrade) silently routes JWTs into **plaintext prefs** `college_auth_fallback_prefs`. There is no telemetry and no user-visible degradation. Fail-closed alternative:
```kotlin
private fun createEncryptedPreferences(context: Context): SharedPreferences = try {
    createEsp(context)                       // EncryptedSharedPreferences
} catch (e: Exception) {
    FirebaseCrashlytics/log("Keystore unavailable: ${e.message}")   // or your logger
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)  // reuse SAME file name
        .edit().clear().apply()              // never store secrets here
        ; MemoryOnlySession.create()         // hold token in RAM until process death
}
// Normalize: const PREFS name NO fallback file so plaintext never holds a token.
```
Minimum acceptable fix: keep the fallback but store **no token in it** — degrade to "stay logged in for this session only", and log the downgrade.

### 1.7.3 Cleartext HTTP + host rewriting (P1)
- `app/src/main/AndroidManifest.xml:13`: `android:usesCleartextTraffic="true"` app-wide, no `networkSecurityConfig`.
- `ServerConfigManager.kt:59-68` auto-`http://` for hosts starting with `10. / 192.168. / 127. / localhost`, no confirmation.
- `HostSelectionInterceptor.kt:10-25` rewrites **every** request's host/scheme/port to the saved URL — including sends of `Authorization` headers.
Pragmatic hardening (Campus deployments per `docs/SelfHostingGuide.md` use a **static** LAN IP — exploit that):
```kotlin
// After toHttpUrlOrNull() parse succeeds:
val isPrivate = host.isPrivateLanHost()   // 10.*, 192.168.*, 127.*, localhost, *.local
if (isPrivate && scheme == "http") { /* allowed, LAN-only, warn once */ }
else if (scheme == "http") error("HTTPS required for external hosts")
```
```kotlin
// HostSelectionInterceptor: only rewrite when the request is same-origin relative
if (request.url.host == "ble-attendance-api-dbf1.onrender.com" || request.url.host.isEmptyAndRelative) { /* rewrite */ } else chain.proceed(request)
```
And strictly skip the Auth header over plain http (or force a confirmation dialog "Send your login token unpencrypted? — or see https-only enforcement (P2): list static campus IP literals as cleartext-permitted `<domain>s` in `network_security_config.xml`).

### 1.7.4 Release signing (P0)
`signingConfigs.create("release") { ... else initWith(getByName("debug")) }` (`app/build.gradle.kts:30-32`). Replace with fail-fast:
```kotlin
create("release") {
    val path = System.getenv("KEYSTORE_PATH")
    if (path != null && file(path).exists()) { /* current body */ }
    else if (System.getenv("CI") == "true" || System.getenv("IS_PRODUCTION_BUILD") == "true")
        throw GradleException("KEYSTORE_PATH missing — refusing to sign release with debug key")
    // Local dev convenience only:
    // initWith(getByName("debug")), logged loudly
}
```

### 1.7.5 Misc (P2)
- `MainActivity.kt:80-86` discards permission-grant results — denied BLE/camera/mic fail silently; route the result into `checkAndExecute` or show a rationale.
- Hardcoded LAN quick-button (`AttendanceScreen.kt:498-504`) steers users to `http://10.115.186.134:53317` — make it `if (BuildConfig.DEBUG)`.
- `AttendanceViewModel.kt:261-267` hand-rolls URL filtering against "10.115." / ":53317" — delete along with the two-prefs-sources problem (§3.4).

## 1.8 Backend infrastructure

- **Docker** (`backend/Dockerfile`): multi-stage to drop `build-essential`; add `USER nonroot`; pin Python deps; run `uvicorn --proxy-headers --forwarded-allow-ips="*"` behind Nginx, else request-client IPs (and any IP-keyed limiter) see the proxy. **Single worker is intentional** (in-memory buffer/limiter/face-registry singletons, §3.6) — write that in the README or PIs will "fix" it with `--workers 4` and break staging silently.
- `docker-compose.yml`: Postgres **5432 published to host** with `change_this_secure_password` — remove the published port (`expose` only) or randomize; no resource limits; placeholders pass the production guard (fix in §1.1).
- `requirements.txt`: unpinned floors (`>=`) — pin exact versions (`pip-compile`) for reproducible deploys.
- No Alembic; every boot replays the 150-line DDL block (with a doubled `DROP NOT NULL` (two occurrences in `main.py`)). Works, but add Alembic before schema branches further.
- Stray `backend/ble_attendance.db` (131 KB) test artifact — delete and ignore `*.db` in `.dockerignore` (already partially present).

---

# §2 Older-Hardware Optimization

The code is *already* unusual in how much it respects old phones — minSdk 24, legacy advertise APIs (`AdvertiseSettings`, API 21+), `AudioRecord` source fallbacks with the API-24 `UNPROCESSED` property check, armeabi-v7a + arm64-v8a ABIs, allocation-free 5-bin Goertzel (no FFT), cached Hann window, reused static AudioTrack. The native lib compiles `-O3 -ffast-math` for both ABIs and the NEON path was deliberately removed because it drifted vs the double-precision guards (`acoustic_dsp.cpp:74-79`). What remains:

## 2.1 BLE scan path — allocation & main-looper churn (P1)
`BleScanner.kt:69-95`: every advertisement matching UUID `0x1234` (payload-prefixed match-all) allocates a String + boxes a latency double + posts one runnable to the main looper. In a 100-phone room at `SCAN_MODE_LOW_LATENCY` that's thousands of posts/sec on a weak device — jank and OOM churn, plus parsing on the main thread.

```kotlin
// On the binder thread, BEFORE posting:
val rssi = result.rssi
if (rssi < -95) return            // heap cut: classroom-relevant only
val text = String(bytes, UTF_8)
if (!looksRelevant(text)) return  // startsWith("S:") == 0 || startsWith("V:") == 0 || exact 8-char probe shape
// THEN post the already-parsed object
```
Keep LOW_LATENCY for the 25 s discovery window only; drop to `SCAN_MODE_BALANCED` in `STANDBY`/`LISTENING_FOR_TEACHER` between beacon latches (battery + thermal on old SoCs).

## 2.2 CPU: polling loops and the game loop (P1)
- `AttendanceViewModel.kt:219-222`: `peerBatchJob` = `while(true) { delay(300L) }` on `Dispatchers.Default` for the VM's whole life (plus similar jobs at `:933`, `:1004`). Replace with a change-driven flow:
  ```kotlin
  rawDiscoveredPeersFlow()
      .sample(300.milliseconds)                 // kotlinx Flow — fires on change
      .collect { if (it.isNotEmpty()) flushNeighbors(it) }
  ```
  (`sample` still polls internally but is cancellable/composable and idle-skips when nothing changed; a count-based `distinctUntilChanged` before `sample` avoids waking at all when nothing changed.)
- `OrbPoppingGame.kt:215-274`: ~60 fps `delay(16)` loop mutating `mutableLongStateOf` recomposes every visible orb. Cheaper on low-end GPUs/SoCs:
  ```kotlin
  while (running) {
      withFrameNanos { t -> state = t }   // driven by choreographer, no timer drift
  }
  ```
  …and ensure orb positions are a single `Offset` state consumed by `Canvas { }` (draw-only recompose) instead of per-orb states.

## 2.3 Main-thread face encoding at game end (P1)
`StudentSection.kt:256` calls the blocking `stopAndExtractResult()` (JPEG compress + Base64) right at game-over = the worst 100–300 ms freeze on old devices:
```kotlin
onComplete = { ... ->
    isGameOver = true
    viewModelScope.launch(Dispatchers.Default) {
        val faceResult = faceCaptureManager.stopAndExtractResult()  // move bitmap work inside IO
        withContext(Dispatchers.Main) { proceedToSubmission(faceResult) }
    }
}
```

## 2.4 Audio: 48 kHz assumption and thread priorities (P2)
- `UltrasonicToneSynthesizer.kt:17`/`UltrasonicProximityManager.kt` hard-code 48 kHz for both AudioTrack and AudioRecord; some old devices only expose 44.1 kHz (init fails → **silent no-op**, detection "skipped" without explanation). Query and fall back:
  ```kotlin
  val chosen = AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE?.toInt()
      ?.takeIf { it in setOf(44100, 48000) } ?: 48000
  ```
  Coefficients already recompute for non-48k (`acoustic_dsp.cpp:45-50`, `GoertzelDetector.kt:126-130`) — only the *tone buffer* must be regenerated at the chosen rate, and the current TS/manager must pass the same rate to both sides. Report the chosen rate in the diagnostic UI so failures are never silent.
- `Thread.MAX_PRIORITY` on three audio threads (`UltrasonicProximityManager.kt:214,241,277,339,424`) exceeds what old schedulers grant to app threads; prefer `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` for the reader thread and normal priority elsewhere (`Thread.sleep` spin-loops at max priority compete directly with the UI thread on big.LITTLE).
- The emitter listens through a single-thread executor; the 3.6 s pulse-train silently no-ops if a second close peer triggers it while busy (`isBeaconEmitting` guard, `:260`) and that peer never gets retried but stays blacklisted in `testedUltrasonicPeers` — either queue bursts or drop the blacklist entry so the pair re-attempts.

## 2.5 Build/component targeting old SoCs (P2)
- Keep `abiFilters = [arm64-v8a, armeabi-v7a]` (old phones are v7a); the doubled native payload is the price of compatibility — consider per-ABI APK or App Bundle splits to trim installs.
- No x86/x86_64 → **emulator cannot test the DSP** (a physical device is required for verification sessions); that's deliberate for size but write it into `run_emulator.sh` commentary.
- Enable R8 full mode (`android.enableR8.fullMode=true`) and add a **baseline profile** (`androidx.profileinstaller` + manual `adb`-generated profile) — cold-start of this full-Compose app on e.g. a 2019 MediaTek device improves 20–40 %.
- Add a Compose BOM + `gradle/libs.versions.toml` version catalog; align CameraX (attendance pins 1.3.1, library 1.4.0 — one binary wins silently).
- Manifest hygiene is already right for old devices: BLE `neverForLocation` (API 31+) + legacy `BLUETOOTH`/location `maxSdkVersion=30`; `required=false` for camera/mic keeps the app installable on binding tablet livestock. ✅
- No WakeLock / foreground service anywhere — screen-off/Doze stops scanning and audio. For the 60-90 s verification window, a short `PARTIAL_WAKE_LOCK` around `PROBING_NEIGHBORS` would raise reliability on aggressive OEMs. (Keeping it Activity-bound is *also* an anti-cheat property — the phone must be in-hand; do not add a full foreground service.)

---

# §3 Production Efficiency — Killing False Errors

"False errors": operations that fail when they should succeed, succeed when they failed, or hang from avoidable contention.

## 3.1 The four real race conditions (P0/P1)

### R1 — grace-window double insert → 500 on a valid student
`attendance.py:1248-1309`: check `not existing_record` → add + commit with **no IntegrityError handling**. Two concurrent stragglers (network retry) both pass the check; second violates `unique_session_ble_device` → global 500. The codebase already has the right pattern in `_record_proxy_violation` (:241-244):
```python
try:
    async with db.begin_nested():
        db.add(new_record)
    await db.commit()
except IntegrityError:
    await db.rollback()   # the other retry already recorded it — that IS success
```
Return the same success body as the primary path.

### R2 — stage/pop race silently loses attendance (worst kind of bug: no error, no record)
`submit-neighbors` stages into the **in-memory** buffer (:1219), then re-reads status (:1230) to route to the grace path. If finalize pops (`pop_submissions`, :830/:1363) in that gap: submission gone, response `"status": "success"`, student marked absent with **zero error anywhere**.
Minimal fix while keeping in-memory staging: serialize finalize vs submit with a `SELECT ... FOR UPDATE` on the session row (PG):
```python
# in submit-neighbors, before staging:
async with db.begin():
    sess = (await db.execute(select(AttendanceSession).where(AttendanceSession.id == sid)
            .with_for_update())).scalar_one()
```
and make `pop_submissions` take the same lock row (finalize path). Order guarantee then becomes: stage-first-pops-later ⇒ grace path sees ACTIVE and inserts late-row, finalize never runs concurrently with a stage.
Longer term: move the staging buffer into Postgres (`submission_buffer` table with TTL) — that also removes the multi-worker ceiling (§3.6).

### R3 — `list_courses` writes to the DB in production (P1)
`attendance.py:290` calls `_get_or_create_mock_context(db)` on every `GET /courses` — in prod, on an empty DB, two concurrent GETs race the insert and one 500s; even solo it fabricates "Prof. Alan Turing" data in real deployments.
```python
if settings.APP_ENV in ("development", "test") or is_test_environment():
    await _get_or_create_mock_context(db)
```
Same for `AUTO-101 / DEMO / DEBUG-1` endpoints and the `main.py` `/debug-db` route — they're already env-gated; make the mock course path the same kind of gate (currently it isn't).

### R4 — register-verify and session-create duplicate races (P1)
- `register_verify_otp` never re-checks duplicate email/roll before insert (`auth.py:202-217`) → concurrent verifications of the same email crash one of them with a 500. Fix: re-check, then catch `IntegrityError` → friendly 409 "account already exists — please sign in".
- `create_attendance_session` course auto-merge: two teachers creating with the same `course_code` → unique violation 500 (`attendance.py:581-598`). Wrap the auto-merge insert in `begin_nested` (R1 pattern) and re-`SELECT` the merged course.

## 3.2 Honesty of statuses (P1)

| Where | Today | Fix |
|---|---|---|
| Lab face endpoints | 200 + `remarks="Processing error: {e}"` | real 5xx with generic detail; log server-side |
| `submit-neighbors` response `face_verified` | `True if payload.face_image_base64 else None` — reports True without any check (:1317) | report only actual registry result: `photo_retained / face_matched / face_error` |
| Straggler after finalize pop | reports success, record dropped (R2) | explicit `"status": "late_rejected", detail: "audited already"` so client can ask teacher for manual mark |
| `total_verified` at finalize | counts only this payload's rows; preserved prior VERIFIED rows omitted (:756-777) | count from DB: `SELECT count(*) WHERE session && verdict=='VERIFIED'` |
| OkHttp `AuthInterceptor` 401 retry (`AuthInterceptor.kt:29-38`) | re-sends the whole body (face JPEG) a second time after a 401 | ⚠️ **GLM verifier: remedy rejected.** The proposed "for POST clear session + surface 401" would reintroduce the mid-lecture logout BUG that the retry was added to fix (the retry fires at most once, and only ends the session if the retry also 401s). The resend is one bounded buffered JPEG; keep it. Adopt only the uniform-`NetworkResult` half. |
| Library VM + Attendance VM network calls | ad-hoc `response.body() ?: return` — 500s/timeouts vanish into UI silence | route everything through `NetworkResult.safeApiCall` (it exists — use it uniformly) |

## 3.3 Deterministic failure modes on devices (P2)
- Permission launcher discards results (`MainActivity.kt:80-86`) → user taps "start attendance" → nothing. Render a denied-state with a settings deep-link.
- 48 kHz-only audio silently no-ops detection on some devices (§2.4) → verifications "fail" without a reason. Surface `audio: available | degraded(rate) | unavailable` in diagnostics.
- `HomeScreen.kt:302,369-370` reads `SessionManager.default.realUser` during composition — not snapshot state → stale developer-badge UI. Collect as State.

## 3.4 Config duplication — three URL sources of truth (P1)
1. `ServerConfigManager.DEFAULT_BASE_URL` (Render URL), 2. `AttendanceViewModel` fallback with hand-rolled LAN-string filtering (`:261-267`), 3. UI quick-buttons (`AttendanceScreen.kt:492-504`). Plus two SharedPreferences owners of the same conceptual file (`ble_attendance_prefs`). Every disagreement is a future "connecting to the wrong server / auth 401" false error.
Consolidate: `ServerConfigManager` is the **only** persistence + scheme policy (§1.7.3); `AttendanceApiClient.baseUrl` reads from it; delete per-feature URL prefs; delete the special-case string filters.

## 3.5 Backend efficiency notes
- Face engine correctly uses `asyncio.to_thread` (never blocks the loop) but is serialized behind two global locks (`face_engine.py:190,267,403-415`) — throughput ceiling is one inference at a time per process: fine at classroom scale, mind it at multi-course concurrent audits.
- Keep `pool_pre_ping` (Neon connection churn), `statement_cache_size=0` server request cap adherence.
- `graph_engine` O(V+E) — trivial at ≤200 nodes. No changes.
- OTP sweeper every 300 s; consider 60 s — table is small either way.

## 3.6 Scaling truth (document or fix)
`submission_buffer`, `rate_limiter`, `face_registry`, and `ForegroundService` locks are **per-process in memory**. Consequences:
- `uvicorn --workers 4`/multi-replica breaks the rate budget and drops staged submissions on the worker that doesn't hold them; a single process restart mid-session loses all staged check-ins.
- Current deploy (Docker CMD single worker, Render free tier) is consistent with the design — кодify it: README banner + `assert settings.WORKERS == 1` style guard or a startup warning when `WEB_CONCURRENCY > 1`.
- To scale later: move the three structures to Redis or Postgres tables; the rest of the code is already stateless.

---

# §4 Correct rotating-salt spec (concrete, fits existing wire budget)

> ⚠️ **GLM verifier: spec is correct but ROI overstated.** The mesh gate already leaves foreign/remote submissions unverified (never-checked-in claims → SPOOFER/PEER_UNCONFIRMED, never VERIFIED), and replay into past sessions is dead (finalized-session gate + grace handling). Rotation mainly shrinks in-session harvest/relay windows, at real schedule-plumbing and teacher-UX cost. **Downgrade to optional P2**; the cheap wins in this area are §1.6.2 (treat `V:` as UI hint only — the unauthenticated `V:<id>:OK` flip is verified real at `AttendanceViewModel:1276-1297`) and §1.6.4 (hide session ID from status UI).

```text
Server  → POST /sessions already returns { session_id, salt, active }
Change 1: request body accepts salt_schedule: array of salts (e.g. 8 × 4-hex, ordered)
Change 2: /submit-neighbors accepts salt belonging to schedule position [now-1 .. now]
          (server stores created_at + window_minutes=3; position = floor((now-created_at)/window))
App     → Teacher rotates displayed broadcast token from schedule every window: S:<ID4>:<SALT_next>
          (beacon latch already re-locks on the strongest new beacon, 2 s window, AttendanceViewModel.kt:1209)
Effect  → Captured beacon payloads expire after ≤2 windows; another-classroom replay fails salt check
```
No client-side secret is required (students only need what's already public-radio), no crypto sleeves needed; PII never rides the radio.

---

# §5 Prioritized Roadmap

> ✳️ **GLM verifier: adopt wholesale except the two ⚠️ items flagged below (#17, and #19's P1 severity — optional P2 per §4 annotation). Everything else verified true against the exact tree at `92195b8` + current uncommitted work.**

**P0 — before any real-use deployment**
| # | Fix | Where | Effort |
|---|---|---|---|
| 1 | Login rate limit + always-pay-the-bcrypt cost | `auth.py:237-285`, §1.2.1-1.2.2 | 2 h |
| 2 | `flagged-faces` owner check (+ `syllabus` auth decision) | `attendance.py:1594`, §1.3 | 1 h |
| 3 | Release log interceptor gated (ProGuard strip Logs) | `NetworkClient.kt:18` | 30 min |
| 4 | Fail release build without keystore | `app/build.gradle.kts:30` | 30 min |
| 5 | Rotate leaked-history secrets; strengthen secret guard | §1.1 | 1 h | ✳️ GLM: rotate-on-Render already advised (pending user action); the deny-list extension covering compose placeholders `change_this_…` is the genuinely new, verified-open part |
| 6 | Grace-path IntegrityError → success (R1); ratelimit already keyed per (session,user) | `attendance.py:1248-1309` | 2 h |
| 7 | Body-size cap middleware | `main.py` | 1 h |

**P1 — production quality**
| # | Fix | Effort |
|---|---|---|
| 8 | finalize/submit serialization (R2) + late-rejected status | 3 h |
| 9 | `token_version` revocation + `is_active` + `/auth/logout` | 3 h |
| 10 | bcrypt → to_thread everywhere; dummy-hash on unknown email | 1 h |
| 11 | `purpose` whitelist; don't reset attempts on resend | 1 h |
| 12 | R3/R4 race guards (mock-context gating; register/session IntegrityError→409) | 2 h |
| 13 | Honest statuses (face_verified, total_verified, lab routes) | 3 h |
| 14 | SessionManager fail-closed on keystore failure | 2 h |
| 15 | URL/scheme policy consolidation (3 sources → 1) | 3 h |
| 16 | Activity-leak fix: BLE bridge bound VA lifecycle, cleared `onCleared` | 2 h |
| 17 | 401-retry body safety + uniform `NetworkResult` usage — ⚠️ GLM: remedy rejected (see §3.2; would reintroduce the intentional mid-lecture 401 logout fix); keep the retry | 1 h |
| 18 | Docker: non-root, pinned deps, `--proxy-headers`, close 5432 | 2 h |
| 19 | Rotating salts schedule (4-change spec above) | 4 h |

**P2 — polish & older-hardware**
| # | Fix | Effort |
|---|---|---|
| 20 | BLE packet pre-filter + parse off main thread | 2 h |
| 21 | Sampling-based flush; `withFrameNanos` game loop; face extract off main thread | 3 h |
| 22 | Sample-rate fallback (44.1 k) + diagnostics honesty | 2 h |
| 23 | R8 full mode + Log strip + baseline profile | 2 h |
| 24 | Version catalog + BOM + aligned CameraX + CI build/test workflow | 4 h |
| 25 | Alembic migrations; remove stray DB; LLM-facing docs refresh ("unspoofable" claims → tempered) | 4 h |
| 26 | Library LazyColumn; permission-denied UX; `gradle.properties` machine path removal | 1 h |
| 27 | Wake config: short wake lock around PROBING_NEIGHBORS only (contract: no permanent service) | 2 h |

---

# §6 Appendix — verified quick facts

- **Backend**: FastAPI + SQLAlchemy 2 async + asyncpg/aiosqlite; Neon Postgres in cloud, PG 16 in compose; YuNet+SFace (ONNX) CV with CUDA/OpenCL autodetect and CPU fallback; endpoints enumerated in subaudit; no SQL injection vectors (ORM-bound everywhere); no `eval/subprocess/pickle`; SSRF-free book lookup (fixed HTTPS hosts, ISBN sanitized).
- **BLE wire**: `S:<ID4>:<SALT4>` teacher · `<ID4><roll4>` student probe · `V:<id>:OK` verdict; 24-byte legacy ADV budget; UUID 0x1234; teacher LOW_LATENCY/HIGH_TX, student LOW_TX probe.
- **Ultrasonic**: 19.2 kHz / 48 kHz / 80 ms Hann sine; 5-bin Goertzel (target + 4 guards at 18400/18800/19600/20000); guard-decision `power ≥ 3.0e7 && snr ≥ 6 dB`; burst = 2-quite-chunk finalize, ≤160 ms; ensemble green = ≥98 dB ref-equivalent (≤20 cm), ≥3 bursts & ≥60 % ⇒ CONFIRMED_SAME_DESK; final flag requires RSSI-touch AND acoustic-close.
- **Native**: `acoustic_dsp` — `-O3 -ffast-math`, `-std=c++17`, thread_local window cache, scalar double Goertzel, NEON removed by design; jni: `GetShortArrayElements` + one `jdouble[6]` per 21 ms chunk.
- **Camera**: front, 640×480 target, `KEEP_ONLY_LATEST`, YUV analysis on its own executor; 400 ms throttle; Y-plane 49-point probe + center-ROI Laplacian sharpness (≥10 % improvement keeps best); encode 224×224 JPEG q80 off-UI-thread analyzable. Server does the actual detection/matching.
- **Timing model**: teacher 30 s session + 1 s ticks → auto-finalize; student 2 s beacon-latch window → 25 s probe window → poll ×90 @2 s (~3 min) for audit result; auto-recovery on CANCELLED/EXPIRED.
- **Test posture**: `PYTEST_CURRENT_TEST`-gated mock tokens/auto-sessions; e2e `simulate_70_students.py` driving graph audit; bcrypt dummy risk for mock teacher `AttendanceRouter` (`attendance.py:165` — login as the mock teacher raises `ValueError: Invalid salt` → 500); `.env` NOT tracked; no CI beyond keep-alive cron.

*Report generated from full-source review; every file:line citation verified against the working tree at `92195b8`.*
