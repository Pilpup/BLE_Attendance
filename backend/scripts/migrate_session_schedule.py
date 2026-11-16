#!/usr/bin/env python3
"""One-time migration: add 60s-timeline schedule columns to attendance_sessions.

Columns:
  - game_starts_at TIMESTAMPTZ  (server-owned game start anchor)
  - game_ends_at   TIMESTAMPTZ  (server-owned game end anchor)
  - audit_run_at   TIMESTAMPTZ  (set in same tx as committed audit verdicts)

All nullable. Legacy sessions keep NULL and must be restarted (no backfill).
Idempotent: ADD COLUMN IF NOT EXISTS.

Usage (against Neon / Render Postgres):
    DATABASE_URL="postgresql+psycopg://user:pass@host/db" \
        python3 scripts/migrate_session_schedule.py
    # add --dry-run to print intent without writing

Raw SQL (Neon console):
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS game_starts_at TIMESTAMPTZ;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS game_ends_at   TIMESTAMPTZ;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS audit_run_at   TIMESTAMPTZ;
"""

import argparse
import os
import sys

STATEMENTS = (
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS game_starts_at TIMESTAMPTZ",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS game_ends_at   TIMESTAMPTZ",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS audit_run_at   TIMESTAMPTZ",
)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true", help="print statements without writing")
    args = ap.parse_args()

    if args.dry_run:
        print("dry run - statements:")
        for s in STATEMENTS:
            print(" ", s + ";")
        return 0

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
    with engine.begin() as conn:
        for s in STATEMENTS:
            conn.execute(text(s))

    print("migration complete: game_starts_at, game_ends_at, audit_run_at added (if missing)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
