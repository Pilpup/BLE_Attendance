#!/usr/bin/env python3
"""One-time migration: collapse legacy attendance statuses to the two-flag
taxonomy introduced by graph engine v3.1.

Target mapping (attendance_records.status):
    VERIFIED_PRESENT                          -> VERIFIED
    NEEDS_REVIEW / ISOLATED_FLAGGED /
    FLAGGED_WITNESSED / SPOOFER_REJECTED      -> UNVERIFIED
Everything else (ABSENT, LEAVE, DUPLICATE_FACE_PROXY, NO_FACE_DETECTED,
COGNITIVE_CHALLENGE_FAILED, CO_LOCATED_SAME_DESK_PROXY, DIVIDED_ATTENTION_PROXY,
COMPLETED / ACTIVE / CANCELLED on sessions) is left untouched: they are
either attendance states or frozen defense-layer verdicts.

Idempotent: rows already VERIFIED/UNVERIFIED are not touched; rerunning is a
no-op. Recomputes total_verified / total_unverified for every affected
session afterwards so dashboards stay correct.

Usage (against prod Neon):
    DATABASE_URL="postgresql+psycopg://user:pass@host/db" \
        python3 scripts/migrate_statuses_two_flag.py
    # add --dry-run to print counts without writing

The equivalent raw SQL, if you prefer running it in the Neon console:
    UPDATE attendance_records SET status = 'VERIFIED'
        WHERE status = 'VERIFIED_PRESENT';
    UPDATE attendance_records SET status = 'UNVERIFIED'
        WHERE status IN ('NEEDS_REVIEW', 'ISOLATED_FLAGGED',
                         'FLAGGED_WITNESSED', 'SPOOFER_REJECTED');
"""

import argparse
import os
import sys
from collections import Counter

LEGACY_TO_VERIFIED = ("VERIFIED_PRESENT",)
LEGACY_TO_UNVERIFIED = (
    "NEEDS_REVIEW",
    "ISOLATED_FLAGGED",
    "FLAGGED_WITNESSED",
    "SPOOFER_REJECTED",
)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true",
                    help="count affected rows without writing")
    args = ap.parse_args()

    url = os.environ.get("DATABASE_URL")
    if not url:
        print("DATABASE_URL not set", file=sys.stderr)
        return 2

    try:
        from sqlalchemy import create_engine, text
    except ImportError:
        print("sqlalchemy not installed in this environment", file=sys.stderr)
        return 2

    engine = create_engine(url)

    counts: Counter = Counter()
    affected_sessions: set[str] = set()

    with engine.begin() as conn:
        for legacy in LEGACY_TO_VERIFIED:
            n = conn.execute(
                text("SELECT count(*) FROM attendance_records WHERE status = :s"),
                {"s": legacy},
            ).scalar()
            counts[legacy] = n
        for legacy in LEGACY_TO_UNVERIFIED:
            n = conn.execute(
                text("SELECT count(*) FROM attendance_records WHERE status = :s"),
                {"s": legacy},
            ).scalar()
            counts[legacy] = n

        total = sum(counts.values())
        print("rows to migrate:", dict(counts), f"(total {total})")
        if total == 0:
            print("nothing to do - DB already on two flags")
            return 0
        if args.dry_run:
            print("dry run - no writes")
            return 0

        for legacy in LEGACY_TO_VERIFIED:
            sess = conn.execute(
                text(
                    "UPDATE attendance_records SET status = 'VERIFIED' "
                    "WHERE status = :s RETURNING session_id"
                ),
                {"s": legacy},
            ).scalars().all()
            affected_sessions.update(str(s) for s in sess)
        for legacy in LEGACY_TO_UNVERIFIED:
            sess = conn.execute(
                text(
                    "UPDATE attendance_records SET status = 'UNVERIFIED' "
                    "WHERE status = :s RETURNING session_id"
                ),
                {"s": legacy},
            ).scalars().all()
            affected_sessions.update(str(s) for s in sess)

        # Recompute session counters for every touched session so the
        # dashboards reflect the new vocabulary immediately.
        for sid in sorted(affected_sessions):
            conn.execute(
                text(
                    "UPDATE attendance_sessions SET "
                    "total_verified = ("
                    "  SELECT count(*) FROM attendance_records "
                    "  WHERE session_id = :sid AND status = 'VERIFIED'), "
                    "total_unverified = ("
                    "  SELECT count(*) FROM attendance_records "
                    "  WHERE session_id = :sid "
                    "  AND status NOT IN ('VERIFIED', 'LEAVE')) "
                    "WHERE id = :sid"
                ),
                {"sid": sid},
            )

    print(f"migrated rows across {len(affected_sessions)} sessions; "
          "counters recomputed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
