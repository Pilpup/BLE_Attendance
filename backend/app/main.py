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


SCHEMA_SYNC_STATEMENTS = [
    # Users
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS roll_number VARCHAR(50);",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(50) DEFAULT 'STUDENT';",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS department VARCHAR(50);",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS program VARCHAR(20);",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS semester INTEGER;",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS section VARCHAR(10);",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE;",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();",
    "ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;",

    # Courses
    "ALTER TABLE courses ADD COLUMN IF NOT EXISTS course_code VARCHAR(30);",
    "ALTER TABLE courses ADD COLUMN IF NOT EXISTS course_name VARCHAR(200);",
    "ALTER TABLE courses ADD COLUMN IF NOT EXISTS teacher_id UUID;",

    # Attendance Sessions
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS course_id UUID;",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS teacher_id UUID;",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS session_token VARCHAR(32);",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE';",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP WITH TIME ZONE;",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS total_enrolled INTEGER DEFAULT 0;",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS total_verified INTEGER DEFAULT 0;",
    "ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS total_spoofers INTEGER DEFAULT 0;",
    "ALTER TABLE attendance_sessions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",

    # Attendance Records
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS session_id UUID;",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS student_id UUID;",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS ble_device_id VARCHAR(50);",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ABSENT';",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS verification_method VARCHAR(50) DEFAULT 'NONE';",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS affinity_score NUMERIC(3, 2) DEFAULT 0.00;",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS bridge_device_id VARCHAR(50);",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS reported_neighbors JSONB DEFAULT '[]'::jsonb;",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS remarks TEXT;",
    "ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();",
    "ALTER TABLE attendance_records ALTER COLUMN status TYPE VARCHAR(50) USING status::text;",
    "ALTER TABLE attendance_records ALTER COLUMN verification_method TYPE VARCHAR(50) USING verification_method::text;",
    "ALTER TABLE attendance_records ALTER COLUMN ble_device_id TYPE VARCHAR(50);",
    "ALTER TABLE attendance_records ALTER COLUMN bridge_device_id TYPE VARCHAR(50);",
]


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Auto-initialize database tables if not already existing
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            for stmt in SCHEMA_SYNC_STATEMENTS:
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
            for stmt in SCHEMA_SYNC_STATEMENTS:
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

