from __future__ import annotations

import asyncio
import time
import uuid
from typing import Dict, List, Optional


# Staged check-in submission record
class StagedSubmission:
    __slots__ = ("device_key", "student_id", "reported_neighbors")

    def __init__(
        self,
        device_key: str,
        student_id: Optional[uuid.UUID],
        reported_neighbors: list[str],
    ) -> None:
        self.device_key = device_key
        self.student_id = student_id
        self.reported_neighbors = reported_neighbors


# In-memory staging buffer for active session student check-ins
class SubmissionBuffer:
    def __init__(self, ttl_seconds: int = 3600):
        self._lock = asyncio.Lock()
        self._ttl_seconds = ttl_seconds
        self._buffers: dict[str, dict[str, StagedSubmission]] = {}
        self._last_accessed: dict[str, float] = {}

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
    ) -> None:
        now = time.time()
        async with self._lock:
            self._prune_stale(now)
            self._last_accessed[session_id] = now
            session_buf = self._buffers.setdefault(session_id, {})
            existing = session_buf.get(device_key)
            if existing:
                existing.reported_neighbors = reported_neighbors
                if student_id:
                    existing.student_id = student_id
            else:
                session_buf[device_key] = StagedSubmission(
                    device_key=device_key,
                    student_id=student_id,
                    reported_neighbors=reported_neighbors,
                )

    # Stages relayed reports from offline peers in memory
    async def stage_relayed_reports(
        self,
        session_id: str,
        relayed_reports: dict[str, list[str]],
    ) -> None:
        if not relayed_reports:
            return
        now = time.time()
        async with self._lock:
            self._prune_stale(now)
            self._last_accessed[session_id] = now
            session_buf = self._buffers.setdefault(session_id, {})
            for offline_sender, offline_nbrs in relayed_reports.items():
                sender_key = offline_sender[-4:].upper() if len(offline_sender) >= 4 else offline_sender.upper()
                if sender_key not in session_buf:
                    norm_nbrs = [n[-4:].upper() if len(n) >= 4 else n.upper() for n in offline_nbrs]
                    session_buf[sender_key] = StagedSubmission(
                        device_key=sender_key,
                        student_id=None,
                        reported_neighbors=norm_nbrs,
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
