import os
import pytest

# Ensures pytest uses isolated SQLite database during test runs
os.environ.setdefault("DATABASE_URL", "sqlite+aiosqlite:///ble_attendance.db")

from app.config import settings


@pytest.fixture(autouse=True)
def disable_smtp_in_tests():
    orig_host = settings.SMTP_HOST
    settings.SMTP_HOST = ""
    yield
    settings.SMTP_HOST = orig_host


@pytest.fixture(autouse=True)
def reset_in_memory_limiters():
    # Rate limiters are process-wide singletons; a fresh suite run must not
    # inherit budgets spent by earlier tests (the suite makes many unauth-
    # enticated register/login calls from one "testclient" IP). Clear every
    # limiter instance in the module so future limiters are covered too.
    from app.services import rate_limiter

    def _clear_all():
        for name in dir(rate_limiter):
            obj = getattr(rate_limiter, name)
            if isinstance(obj, rate_limiter.SlidingWindowRateLimiter):
                obj._events.clear()

    _clear_all()
    yield
    _clear_all()
