from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/ble_attendance"
    APP_ENV: str = "development"
    API_V1_STR: str = "/api/v1"
    JWT_SECRET_KEY: str = "ble-mesh-attendance-secret-key-super-secure"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24
    FACULTY_SIGNUP_SECRET: str = "CSE_FACULTY_2026"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()

