from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/ble_attendance"
    APP_ENV: str = "development"
    API_V1_STR: str = "/api/v1"
    JWT_SECRET_KEY: str = "7f9d8a4b2c1e6f0a3e5b7c9d1a3f5b7c9e1d3a5b7c9f1a3e5b7c9d1a3f5b7c9e"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24 * 365
    FACULTY_SIGNUP_SECRET: str = "fff791e68ec68434901e256c293f0724"

    # Email configuration for OTP Verification & 2FA
    BREVO_API_KEY: str = ""
    SMTP_HOST: str = ""
    SMTP_PORT: int = 587
    SMTP_USER: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM_EMAIL: str = "noreply@college.edu"
    SMTP_FROM_NAME: str = "College Hub"
    OTP_EXPIRE_MINUTES: int = 5
    OTP_CLEANUP_INTERVAL_SECONDS: int = 300  # 5 minutes

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()

