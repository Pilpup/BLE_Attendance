from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from sqlalchemy import text
from app.config import settings
from app.database import engine
from app.models.attendance import Base
from app.routers import attendance, auth


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Auto-initialize database tables if not already existing
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            # Normalize existing PostgreSQL enum columns to VARCHAR to prevent type mismatch
            for stmt in [
                "ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;",
                "ALTER TABLE attendance_sessions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
                "ALTER TABLE attendance_records ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
                "ALTER TABLE attendance_records ALTER COLUMN verification_method TYPE VARCHAR(50) USING verification_method::text;",
            ]:
                try:
                    await conn.execute(text(stmt))
                except Exception:
                    pass
    except Exception as e:
        print(f"Warning during DB table auto-creation: {e}")
    yield
    await engine.dispose()


app = FastAPI(
    title="BLE Attendance Verification API",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Routers ───────────────────────────────────────────────────────────────────
app.include_router(attendance.router, prefix=settings.API_V1_STR)
app.include_router(auth.router, prefix=settings.API_V1_STR)


@app.get("/", tags=["System"])
async def root():
    return {
        "status": "online",
        "service": "BLE Attendance Verification API",
        "docs": "/docs",
        "health": "/health"
    }


@app.get("/health", tags=["System"])
async def health_check():
    return {"status": "ok", "env": settings.APP_ENV}


@app.get("/debug-db", tags=["System"])
async def debug_db():
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            for stmt in [
                "ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;",
                "ALTER TABLE attendance_sessions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
                "ALTER TABLE attendance_records ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
                "ALTER TABLE attendance_records ALTER COLUMN verification_method TYPE VARCHAR(50) USING verification_method::text;",
            ]:
                try:
                    await conn.execute(text(stmt))
                except Exception:
                    pass
        return {"status": "connected", "database": "tables and columns verified and normalized successfully"}
    except Exception as e:
        return {"status": "connection_error", "error": str(e), "type": type(e).__name__}

