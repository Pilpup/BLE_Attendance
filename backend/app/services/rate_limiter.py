from __future__ import annotations

import asyncio
import time


# Per-(session, student account) sliding-window rate limiter for student check-ins.
# Deliberately NOT per-IP: an entire classroom shares one gateway address.
# Budget covers roughly 2.5x the interim submit cadence of a full discovery window
# (~8 submits) plus retries, so legitimate clients never hit it.
class SubmitRateLimiter:
    def __init__(self, max_events: int = 20, window_seconds: float = 300.0):
        self._lock = asyncio.Lock()
        self._max_events = max_events
        self._window = window_seconds
        self._events: dict[str, list[float]] = {}

    # Records an attempt and returns True if the key is within budget
    async def check(self, session_id: str, identity: str) -> bool:
        bucket_key = f"{session_id}:{identity}"
        now = time.monotonic()
        async with self._lock:
            stamps = [t for t in self._events.get(bucket_key, []) if (now - t) < self._window]
            if len(stamps) >= self._max_events:
                self._events[bucket_key] = stamps
                return False
            stamps.append(now)
            self._events[bucket_key] = stamps

            # Opportunistic cleanup once the bucket map grows large
            if len(self._events) > 1000:
                stale = [
                    k for k, v in self._events.items()
                    if not v or (now - v[-1]) >= self._window
                ]
                for k in stale:
                    self._events.pop(k, None)
            return True


submit_rate_limiter = SubmitRateLimiter()
