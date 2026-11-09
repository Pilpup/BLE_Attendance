from __future__ import annotations

import asyncio
import time
import uuid
from typing import Dict, List, Optional


# Staged check-in submission record
class StagedSubmission:
    __slots__ = ("device_key", "student_id", "reported_neighbors", "claimed_ultrasonic")

    def __init__(
        self,
        device_key: str,
        student_id: Optional[uuid.UUID],
        reported_neighbors: list[str],
        claimed_ultrasonic: Optional[str] = None,
    ) -> None:
        self.device_key = device_key
        self.student_id = student_id
        self.reported_neighbors = reported_neighbors
        # M3-lite: ultrasonic desk claim (partner device key). Buffered ONLY here —
        # never persisted — so aborted/restarted claims leave no DB footprint and a
        # one-sided claim can never vandalize a record on its own.
        self.claimed_ultrasonic = claimed_ultrasonic


# In-memory staging buffer for active session student check-ins
class SubmissionBuffer:
    def __init__(self, ttl_seconds: int = 3600):
        self._lock = asyncio.Lock()
        # R2 (review §3.1): per-session locks serialize the finalize/close
        # boundary. A submission must either be drained by finalize's pop, or
        # land AFTER finalize committed and be honestly rerouted to the grace
        # path by the submit route's post-stage status re-read. Without the
        # gate, a stage entering between finalize's pop and its commit reads
        # the still-ACTIVE (uncommitted) status and is silently orphaned when
        # finalize clears the buffer seconds later.
        # Gates are never pruned: they ride on unexpired locks keyed by
        # session id (bounded by session lifetime, a few hundred bytes each).
        self._gates: dict[str, asyncio.Lock] = {}
        self._ttl_seconds = ttl_seconds
        self._buffers: dict[str, dict[str, StagedSubmission]] = {}
        self._last_accessed: dict[str, float] = {}

    # Request-scoped gate used by finalize/close bodies and the submit route.
    def session_gate(self, session_id: str) -> asyncio.Lock:
        return self._gates.setdefault(f"gate:{session_id}", asyncio.Lock())

    # Prunes stale session buffers inactive for longer than TTL
    def _prune_stale(self, now: float) -> None:
        stale_keys = [
            sid for sid, ts in self._last_accessed.items()
            if (now - ts) > self._ttl_seconds
        ]
        for sid in stale_keys:
            self._buffers.pop(sid, None)
            self._last_accessed.pop(sid, None)

    # Stages student check-in submission in memory
    async def stage_submission(
        self,
        session_id: str,
        device_key: str,
        student_id: Optional[uuid.UUID],
        reported_neighbors: list[str],
        claimed_ultrasonic: Optional[str] = None,
    ) -> None:
        now = time.time()
        async with self._lock:
            self._prune_stale(now)
            self._last_accessed[session_id] = now
            session_buf = self._buffers.setdefault(session_id, {})
            existing = session_buf.get(device_key)
            if existing:
                # Merge snapshots instead of overwriting: interim submissions arrive
                # through asymmetric BLE discovery windows, so a later snapshot can
                # know FEWER peers than an earlier one (and then fail mutual
                # confirmation at finalize). Union of claims lets mutual links
                # converge by finalize. student_id keeps the earliest identity binding.
                merged_claims = list(dict.fromkeys(
                    list(existing.reported_neighbors) + list(reported_neighbors)
                ))
                existing.reported_neighbors = merged_claims
                existing.student_id = existing.student_id or student_id
                existing.claimed_ultrasonic = existing.claimed_ultrasonic or claimed_ultrasonic
            else:
                session_buf[device_key] = StagedSubmission(
                    device_key=device_key,
                    student_id=student_id,
                    reported_neighbors=reported_neighbors,
                    claimed_ultrasonic=claimed_ultrasonic,
                )

    # Extracts and removes all staged submissions for a session upon final audit
    async def pop_submissions(self, session_id: str) -> dict[str, StagedSubmission]:
        async with self._lock:
            self._last_accessed.pop(session_id, None)
            return self._buffers.pop(session_id, {})

    # Clears staged submissions for a session upon cancellation
    async def clear_session(self, session_id: str) -> None:
        async with self._lock:
            self._last_accessed.pop(session_id, None)
            self._buffers.pop(session_id, None)


submission_buffer = SubmissionBuffer()
