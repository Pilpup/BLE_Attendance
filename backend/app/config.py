from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/ble_attendance"
    APP_ENV: str = "development"
    API_V1_STR: str = "/api/v1"
    JWT_SECRET_KEY: str = "7f9d8a4b2c1e6f0a3e5b7c9d1a3f5b7c9e1d3a5b7c9f1a3e5b7c9d1a3f5b7c9e"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24
    FACULTY_SIGNUP_SECRET: str = "fff791e68ec68434901e256c293f0724"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()

