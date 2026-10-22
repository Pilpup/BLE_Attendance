import pytest
from app.config import settings


@pytest.fixture(autouse=True)
def disable_smtp_in_tests():
    orig_host = settings.SMTP_HOST
    settings.SMTP_HOST = ""
    yield
    settings.SMTP_HOST = orig_host
