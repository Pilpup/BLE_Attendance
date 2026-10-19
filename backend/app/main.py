from contextlib import asynccontextmanager
import traceback
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

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
            # Normalize existing PostgreSQL enum and length columns to VARCHAR(50)
            for stmt in [
                "ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;",
                "ALTER TABLE attendance_sessions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
                "ALTER TABLE attendance_records ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
                "ALTER TABLE attendance_records ALTER COLUMN verification_method TYPE VARCHAR(50) USING verification_method::text;",
                "ALTER TABLE attendance_records ALTER COLUMN ble_device_id TYPE VARCHAR(50);",
                "ALTER TABLE attendance_records ALTER COLUMN bridge_device_id TYPE VARCHAR(50);",
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

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    return JSONResponse(
        status_code=500,
        content={
            "detail": str(exc),
            "type": type(exc).__name__,
            "traceback": traceback.format_exc(),
        },
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
                "ALTER TABLE attendance_records ALTER COLUMN ble_device_id TYPE VARCHAR(50);",
                "ALTER TABLE attendance_records ALTER COLUMN bridge_device_id TYPE VARCHAR(50);",
            ]:
                try:
                    await conn.execute(text(stmt))
                except Exception:
                    pass
            sess_rows = (await conn.execute(text("SELECT id, session_token, status, total_verified, total_spoofers, started_at FROM attendance_sessions ORDER BY started_at DESC LIMIT 5;"))).mappings().all()
            rec_rows = (await conn.execute(text("SELECT id, session_id, ble_device_id, status, verification_method, recorded_at FROM attendance_records ORDER BY recorded_at DESC LIMIT 5;"))).mappings().all()

        return {
            "status": "connected",
            "recent_sessions": [dict(r) for r in sess_rows],
            "recent_records": [dict(r) for r in rec_rows],
        }
    except Exception as e:
        return {"status": "connection_error", "error": str(e), "type": type(e).__name__}

