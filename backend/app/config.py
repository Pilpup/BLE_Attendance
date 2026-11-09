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


def validate_production_secrets() -> None:
    if settings.APP_ENV != "production":
        return
    offenders = [
        name
        for name, insecure_value in INSECURE_DEFAULT_SECRETS.items()
        if getattr(settings, name) == insecure_value
    ]
    if offenders:
        raise RuntimeError(
            f"Refusing to start: APP_ENV is production but {', '.join(offenders)} "
            "still use the built-in defaults committed in the repository. Generate "
            "fresh values (python3 -c \"import secrets; print(secrets.token_urlsafe(48))\") "
            "and set them via environment variables before deploying."
        )
