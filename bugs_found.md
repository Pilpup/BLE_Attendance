# Bugs Found Log

Continuous change tracking is active. As new changes and commits are made to the codebase, any bugs, compiler errors, race conditions, or regressions detected will be recorded here.

---

### Status: Monitoring Active (All Clear)
*Last checked: 2026-11-10 10:12 UTC*

- **Backend tests:** 72/72 passed.
- **Android compilation:** Green (clean release compilation).
- **Working tree:** Chunk 11 background face-encode and camera frame candidate separation verified (`BestFrameAnalyzer.kt`, `PassiveFaceCaptureManager.kt`, `StudentSection.kt`, `OrbPoppingGame.kt`, `BleScanner.kt`). Zero active bugs.

### Resolved Issues
1. **Unresolved Reference `looksLikeAttendanceMessage` in `BleScanner.kt`**:
   - *Issue:* `e: .../BleScanner.kt:102:18 Unresolved reference: looksLikeAttendanceMessage` caused `:feature:attendance:compileReleaseKotlin` to fail.
   - *Resolution:* Defined `looksLikeAttendanceMessage(messageText: String): Boolean` inside `BleScanner.Companion`. Build now passes cleanly.
2. **Assertion Mismatch in `test_production_guard_rejects_placeholder_database_url` (Chunk 10)**:
   - *Issue:* Test asserted `"change_this_secure_password" in str(err.value)`, but `validate_production_secrets()` only outputs the specific matched placeholder token (`"change_this"`).
   - *Resolution:* Resolved in `test_backend.py` (`assert "change_this" in str(err.value)`). All 72 backend tests passing.
