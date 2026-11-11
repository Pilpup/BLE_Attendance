from __future__ import annotations

import time


class SlidingWindowRateLimiter:
    """Generic sliding-window limiter keyed by an opaque bucket string.

    In-memory and per-process — consistent with the single-worker deployment
    posture (submission buffer / face registry are per-process too). `check()`
    contains no await points between the read and the write, so the budget
    decision is atomic under the single-threaded event loop.
    """

    def __init__(self, max_events: int, window_seconds: float):
        self._max_events = max_events
        self._window = window_seconds
        self._events: dict[str, list[float]] = {}

    # Records an attempt and returns True if the bucket is within budget
    async def check(self, bucket: str) -> bool:
        now = time.monotonic()
        stamps = [t for t in self._events.get(bucket, []) if (now - t) < self._window]
        if len(stamps) >= self._max_events:
            self._events[bucket] = stamps
            return False
        stamps.append(now)
        self._events[bucket] = stamps

        # Opportunistic cleanup once the bucket map grows large
        if len(self._events) > 1000:
            stale = [
                k for k, v in self._events.items()
                if not v or (now - v[-1]) >= self._window
            ]
            for k in stale:
                self._events.pop(k, None)
        return True


class SubmitRateLimiter(SlidingWindowRateLimiter):
    """Per-(session, student account) limiter for student check-ins.

    Deliberately NOT keyed by IP: an entire classroom shares one gateway
    address, so an IP-keyed budget would 429 innocent classmates.

    Budget covers roughly 2.5x the interim submit cadence of a full discovery
    window (~8 submits) plus retries, so legitimate clients never hit it.
    """

    def __init__(self, max_events: int = 20, window_seconds: float = 300.0):
        super().__init__(max_events=max_events, window_seconds=window_seconds)

    async def check(self, session_id: str, identity: str) -> bool:
        return await super().check(f"{session_id}:{identity}")


submit_rate_limiter = SubmitRateLimiter()

# Login attempt budgets (review §1.2.1):
#
#   - Per-ACCOUNT (10 / 5 min): throttles online password guessing of one
#     email while leaving room for genuine typos under the 2FA flow. Attempts
#     are counted regardless of success — that is the point.
#   - Per-GATEWAY (150 / 5 min): only a floor against bulk credential-stuffing
#     scans from a single address. Calibrated ABOVE a full classroom's login
#     burst (70 students logging in within the same window through one shared
#     NAT address — the same reality that forbids tight IP limits on check-ins).
#
# Note: behind Render today all requests share the edge proxy address until
# --proxy-headers support lands (infrastructure chunk), so the gateway budget
# effectively caps total login bursts globally — still far above classroom scale.
login_email_limiter = SlidingWindowRateLimiter(max_events=10, window_seconds=300.0)
login_ip_limiter = SlidingWindowRateLimiter(max_events=150, window_seconds=300.0)

# OTP resend budgets (audit M1): the endpoint is unauthenticated, so a strict
# per-MAILBOX cap is the primary defense — it directly bounds inbox flooding
# and Brevo plan burn on any single victim address. The per-IP layer stays
# generous (classroom-sized NAT burst headroom) and only stops distributed
# spraying from many addresses at once.
otp_resend_email_limiter = SlidingWindowRateLimiter(max_events=3, window_seconds=1800.0)
otp_resend_ip_limiter = SlidingWindowRateLimiter(max_events=60, window_seconds=1800.0)
