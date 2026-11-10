compile:
./gradlew assembleDebug

install on your phoen:
adb install -r app/build/outputs/apk/debug/app-debug.apk

## Deployment notes (backend)

- **Run exactly ONE uvicorn worker.** `submission_buffer`, the rate limiters, and
  `face_registry` are per-process in-memory singletons. `WEB_CONCURRENCY > 1` or a
  second replica silently splits login rate budgets and strands staged check-ins on
  the worker that was not asked — a data-loss bug, not capacity. The warning fires
  at startup (`WEB_CONCURRENCY` logged). Scaling later means moving those
  structures to Postgres/Redis, NOT `--workers 4`.
- The shipped Dockerfile runs `--proxy-headers --forwarded-allow-ips="*"` — client
  IPs reach the rate limiter through the proxy; removing the flag collapses the
  per-gateway login budget into one global counter.
- Postgres is exposed only on the compose network (`expose`, not `ports`); the
  backend is the only client. Do not re-publish `5432:5432`.
- With `APP_ENV=production`, the startup guard refuses to boot on placeholder
  secrets — it checks `JWT_SECRET_KEY`, `FACULTY_SIGNUP_SECRET`, **and** the
  password embedded in `DATABASE_URL`. Generate with
  `python3 -c "import secrets; print(secrets.token_urlsafe(48))"`.
