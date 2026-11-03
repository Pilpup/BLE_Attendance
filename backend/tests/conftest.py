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
