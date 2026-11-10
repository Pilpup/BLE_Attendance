from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


# Application environment settings and secret configurations
class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/ble_attendance"
    APP_ENV: str = "development"
    API_V1_STR: str = "/api/v1"
    JWT_SECRET_KEY: str = "7f9d8a4b2c1e6f0a3e5b7c9d1a3f5b7c9e1d3a5b7c9f1a3e5b7c9d1a3f5b7c9e"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24  # 24 h — stolen tokens must age out; OTP re-login is cheap
    FACULTY_SIGNUP_SECRET: str = "fff791e68ec68434901e256c293f0724"

    # Email configuration for OTP verification and two-factor authentication
    BREVO_API_KEY: str = ""
    SMTP_HOST: str = ""
    SMTP_PORT: int = 587
    SMTP_USER: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM_EMAIL: str = "noreply@college.edu"
    SMTP_FROM_NAME: str = "College Hub"
    OTP_EXPIRE_MINUTES: int = 5
    OTP_CLEANUP_INTERVAL_SECONDS: int = 300

    # Book metadata lookup keys
    GOOGLE_BOOKS_API_KEY: str = ""
    ISBNDB_API_KEY: str = ""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


# Global application configuration instance
settings = Settings()

# These literals are COMMITTED in this public repository and must never
# protect a production deployment. Deployments override them via environment
# variables; the startup guard below refuses to boot otherwise.
INSECURE_DEFAULT_SECRETS = {
    "JWT_SECRET_KEY": settings.JWT_SECRET_KEY,
    "FACULTY_SIGNUP_SECRET": settings.FACULTY_SIGNUP_SECRET,
}

# Fragments that cannot occur in a genuine production secret — operators set
# random tokens. Covers today's docker-compose placeholder idioms AND the
# historical committed defaults still recoverable from git history, so pasting
# either (in terms or in whole) cannot smuggle a weak value past the
# exact-default comparison (review §1.1: closes the placeholder gap that
# compose values sailed through).
SECRET_PLACEHOLDER_TOKENS = (
    "change_this",
    "changeme",
    "replace_me",
    "super-secure",
    "college_faculty_passcode",
    "ble-mesh-attendance-secret-key",
)
# 32+ chars keeps room for human-set passcodes while forcing real entropy
# (secrets.token_urlsafe(48) yields 64).
MIN_SECRET_LENGTH = 32


def _placeholder_token_in(value: str) -> Optional[str]:
    """First placeholder marker found in a value, or None (shared scanner)."""
    lowered = (value or "").lower()
    for token in SECRET_PLACEHOLDER_TOKENS:
        if token in lowered:
            return token
    return None


def insecure_secret_reason(name: str, value: str) -> Optional[str]:
    """Reason a production secret value is unsafe, or None when acceptable."""
    value = (value or "").strip()
    if not value:
        return f"{name} is empty"
    if value == INSECURE_DEFAULT_SECRETS.get(name):
        return f"{name} still uses the default committed in the repository"
    if len(value) < MIN_SECRET_LENGTH:
        return f"{name} is too short ({len(value)} chars < {MIN_SECRET_LENGTH})"
    if (token := _placeholder_token_in(value)) is not None:
        return f"{name} contains placeholder marker '{token}'"
    return None


def database_url_reason() -> Optional[str]:
    """Reason the production DATABASE_URL is unsafe, or None when acceptable.

    R10 (review §1.1/§1.8, deferred from chunk 3): DATABASE_URL was outside
    the guard's reach — the compose file embeds the shared placeholder
    password directly in the DSN, so a placeholder booted alongside rotated
    JWT/faculty secrets. The embedded password must be replaced too.
    """
    url = (getattr(settings, "DATABASE_URL", "") or "").strip()
    if not url:
        return "DATABASE_URL is empty"
    if (token := _placeholder_token_in(url)) is not None:
        return (
            "DATABASE_URL contains placeholder marker "
            f"'{token}' — the compose default database password must be replaced"
        )
    return None


def validate_production_secrets() -> None:
    if settings.APP_ENV != "production":
        return
    offenders = [
        reason
        for name in INSECURE_DEFAULT_SECRETS
        if (reason := insecure_secret_reason(name, getattr(settings, name, "")))
    ]
    if (db_reason := database_url_reason()) is not None:
        offenders.append(db_reason)
    if offenders:
        raise RuntimeError(
            "Refusing to start: APP_ENV is production but insecure secrets are "
            "configured — " + "; ".join(offenders) + ". Generate fresh values "
            "(python3 -c \"import secrets; print(secrets.token_urlsafe(48))\") "
            "and set them via environment variables before deploying."
        )
