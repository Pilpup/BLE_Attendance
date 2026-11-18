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
- **Fetch the ArcFace weight before starting the backend.** The 174 MB
  `arcface_w600k_r50.onnx` is not in git; run
  `python3 backend/scripts/fetch_arcface_weight.py` on the host. Docker Compose
  mounts `backend/app/models/weights` read-only into the container. Without the
  file the engine falls back to SFace 128-d (and any harvested 1:1 references
  must be reset when the 512-d head is later installed).
- Postgres is exposed only on the compose network (`expose`, not `ports`); the
  backend is the only client. Do not re-publish `5432:5432`.
- With `APP_ENV=production`, the startup guard refuses to boot on placeholder
  secrets — it checks `JWT_SECRET_KEY`, `FACULTY_SIGNUP_SECRET`, **and** the
  password embedded in `DATABASE_URL`. Generate with
  `python3 -c "import secrets; print(secrets.token_urlsafe(48))"`.

## Face threshold calibration (offline, manual corpus)

F1 thresholds are placeholders until measured on real faces. Collect photos
only from consenting people; delete them after calibration. The corpus is
gitignored and never persisted by the app.

1. Put photos in `backend/calibration_corpus/` as
   `<identity>/<day>_<condition>.jpg`.
2. Write `backend/calibration_corpus/manifest.json`:

```json
{
  "images": [
    {"path": "p01/2026-11-20_front.jpg", "identity": "p01", "day": "2026-11-20", "condition": "front"},
    {"path": "p01/2026-11-20_yaw_left.jpg", "identity": "p01", "day": "2026-11-20", "condition": "yaw_left"},
    {"path": "p01/2026-11-21_dim.jpg", "identity": "p01", "day": "2026-11-21", "condition": "dim"}
  ]
}
```

Conditions: `front`, `yaw_left`, `yaw_right`, `glasses`, `dim`, `bright`, `other`.
Add `"lookalike_group": "A"` to both members of an intentionally paired set.
Aim for 10–15 identities, 6 images each across 2 days, plus 3–5 lookalike pairs.

3. Run `python3 backend/scripts/calibrate_face_thresholds.py`. It embeds images
   through the real ArcFace engine, computes ROC on a 70% train split, validates
   on the held-out 30%, and prints recommended constants plus per-condition FRR.
   Add `--report path.json` to save the numbers only.
4. Apply the recommended constants, re-run the backend suite, delete the corpus.
   No accuracy claim before this step.
