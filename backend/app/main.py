import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import logging
import os
import traceback
from fastapi import FastAPI, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from sqlalchemy import delete, text
from app.config import settings, validate_production_secrets
from app.core.security import is_test_environment
from app.database import AsyncSessionLocal, engine, is_postgres
from app.models.attendance import Base, EmailOTP
from app.routers import attendance, auth, library
from app.seed import seed_library_demo_books

logger = logging.getLogger("uvicorn.error")

POSTGRES_SCHEMA_SYNC_BLOCK = """
DO $$
BEGIN
    -- users
    ALTER TABLE users ADD COLUMN IF NOT EXISTS roll_number VARCHAR(50);
    ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(50) DEFAULT 'STUDENT';
    ALTER TABLE users ADD COLUMN IF NOT EXISTS department VARCHAR(50);
    ALTER TABLE users ADD COLUMN IF NOT EXISTS program VARCHAR(20);
    ALTER TABLE users ADD COLUMN IF NOT EXISTS semester INTEGER;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS section VARCHAR(10);
    ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE;
    ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();
    ALTER TABLE users ALTER COLUMN role TYPE VARCHAR(50) USING role::text;

    -- courses
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS course_code VARCHAR(30);
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS course_name VARCHAR(200);
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS teacher_id UUID;
    ALTER TABLE courses ALTER COLUMN teacher_id DROP NOT NULL;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS program VARCHAR(30) DEFAULT 'B.Tech';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS department VARCHAR(100) DEFAULT 'Computer Science & Engineering';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS semester INTEGER DEFAULT 1;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS category VARCHAR(20) DEFAULT 'DC';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS course_type VARCHAR(20) DEFAULT 'THEORY';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS credits INTEGER DEFAULT 4;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS periods_l INTEGER DEFAULT 3;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS periods_t INTEGER DEFAULT 1;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS periods_p INTEGER DEFAULT 0;
    -- Full-row ORM loads (create route etc.) select created_at on every Course load
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();
    CREATE INDEX IF NOT EXISTS ix_courses_dept_sem ON courses(department, semester);

    -- course_units (academic syllabus hierarchy)
    CREATE TABLE IF NOT EXISTS course_units (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        course_id UUID NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
        unit_number INTEGER NOT NULL,
        unit_title VARCHAR(255) NOT NULL,
        teaching_hours INTEGER,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT uq_course_unit UNIQUE (course_id, unit_number)
    );
    CREATE INDEX IF NOT EXISTS ix_course_units_course_id ON course_units(course_id);

    -- unit_topics (individual syllabus topics / lab experiments)
    CREATE TABLE IF NOT EXISTS unit_topics (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        unit_id UUID NOT NULL REFERENCES course_units(id) ON DELETE CASCADE,
        topic_order INTEGER NOT NULL,
        topic_name TEXT NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT uq_unit_topic UNIQUE (unit_id, topic_order)
    );
    CREATE INDEX IF NOT EXISTS ix_unit_topics_unit_id ON unit_topics(unit_id);

    -- attendance_sessions
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS course_id UUID;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS teacher_id UUID;
    ALTER TABLE courses ALTER COLUMN teacher_id DROP NOT NULL;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS eligibility_program VARCHAR(30);
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS eligibility_department VARCHAR(100);
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS eligibility_semester INTEGER;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS session_token VARCHAR(32);
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS session_salt VARCHAR(8);
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE';
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP WITH TIME ZONE;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS total_enrolled INTEGER DEFAULT 0;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS total_verified INTEGER DEFAULT 0;
    ALTER TABLE attendance_sessions ADD COLUMN IF NOT EXISTS total_spoofers INTEGER DEFAULT 0;
    ALTER TABLE attendance_sessions ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
    CREATE INDEX IF NOT EXISTS ix_attendance_sessions_session_token ON attendance_sessions(session_token);
    CREATE INDEX IF NOT EXISTS ix_attendance_sessions_teacher_started ON attendance_sessions(teacher_id, started_at);
    CREATE INDEX IF NOT EXISTS ix_attendance_sessions_course_started ON attendance_sessions(course_id, started_at);

    -- attendance_records
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS session_id UUID;
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS student_id UUID;
    ALTER TABLE attendance_records ALTER COLUMN student_id DROP NOT NULL;
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS ble_device_id VARCHAR(50);
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ABSENT';
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS verification_method VARCHAR(50) DEFAULT 'NONE';
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS affinity_score NUMERIC(3, 2) DEFAULT 0.00;
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS bridge_device_id VARCHAR(50);
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS reported_neighbors JSONB DEFAULT '[]'::jsonb;
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS remarks TEXT;
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();
    ALTER TABLE attendance_records ALTER COLUMN status TYPE VARCHAR(50) USING status::text;
    ALTER TABLE attendance_records ALTER COLUMN verification_method TYPE VARCHAR(50) USING verification_method::text;
    ALTER TABLE attendance_records ALTER COLUMN ble_device_id TYPE VARCHAR(50);
    ALTER TABLE attendance_records ALTER COLUMN bridge_device_id TYPE VARCHAR(50);
    CREATE INDEX IF NOT EXISTS ix_attendance_records_session_status ON attendance_records(session_id, status);
    CREATE INDEX IF NOT EXISTS ix_attendance_records_session_student ON attendance_records(session_id, student_id);
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_session_ble_device'
    ) THEN
        ALTER TABLE attendance_records ADD CONSTRAINT unique_session_ble_device UNIQUE (session_id, ble_device_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_session_student'
    ) THEN
        ALTER TABLE attendance_records ADD CONSTRAINT unique_session_student UNIQUE (session_id, student_id);
    END IF;

    -- session_submissions (for multi-worker staging)
    CREATE TABLE IF NOT EXISTS session_submissions (
        id UUID PRIMARY KEY,
        session_id UUID NOT NULL REFERENCES attendance_sessions(id) ON DELETE CASCADE,
        student_id UUID REFERENCES users(id) ON DELETE SET NULL,
        device_key VARCHAR(50) NOT NULL,
        reported_neighbors JSONB DEFAULT '[]'::jsonb,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT uq_session_submission_device UNIQUE (session_id, device_key)
    );
    CREATE INDEX IF NOT EXISTS ix_session_submissions_session_id ON session_submissions(session_id);

    -- books (library catalogue — isolated from attendance; Phase 4)
    CREATE TABLE IF NOT EXISTS books (
        id UUID PRIMARY KEY,
        title VARCHAR(255) NOT NULL,
        author VARCHAR(255) NOT NULL,
        isbn VARCHAR(32) NOT NULL UNIQUE,
        category VARCHAR(50) NOT NULL,
        total_copies INTEGER NOT NULL DEFAULT 1,
        available_copies INTEGER NOT NULL DEFAULT 1,
        shelf_location VARCHAR(100) NOT NULL DEFAULT 'Main Hall',
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT ck_books_copies CHECK (available_copies >= 0 AND available_copies <= total_copies)
    );
    CREATE INDEX IF NOT EXISTS ix_books_title ON books(title);
    CREATE INDEX IF NOT EXISTS ix_books_author ON books(author);
    CREATE INDEX IF NOT EXISTS ix_books_category ON books(category);
    ALTER TABLE books ADD COLUMN IF NOT EXISTS edition VARCHAR(100);
    ALTER TABLE books ADD COLUMN IF NOT EXISTS overview TEXT;
    ALTER TABLE books ADD COLUMN IF NOT EXISTS table_of_contents TEXT;

    -- book_reservations (one user holding one copy; Phase 4)
    CREATE TABLE IF NOT EXISTS book_reservations (
        id UUID PRIMARY KEY,
        book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
        user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
        reserved_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT ck_book_reservations_status CHECK (status IN ('ACTIVE', 'FULFILLED', 'CANCELLED'))
    );
    CREATE INDEX IF NOT EXISTS ix_book_reservations_book_id ON book_reservations(book_id);
    CREATE INDEX IF NOT EXISTS ix_book_reservations_user_id ON book_reservations(user_id);

    -- email_otps index on expires_at for fast periodic purging
    CREATE INDEX IF NOT EXISTS ix_email_otps_expires_at ON email_otps(expires_at);
END $$;
"""


# Periodically purges expired email OTP verification records from the database
async def purge_expired_otps_worker():
    while True:
        try:
            now = datetime.now(timezone.utc)
            async with AsyncSessionLocal() as session:
                stmt = delete(EmailOTP).where(EmailOTP.expires_at < now)
                result = await session.execute(stmt)
                await session.commit()
                if result.rowcount and result.rowcount > 0:
                    logger.info(f"[OTP Cleanup] Purged {result.rowcount} expired OTP records from database")
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error(f"[OTP Cleanup] Error purging expired OTPs: {e}")

        try:
            await asyncio.sleep(settings.OTP_CLEANUP_INTERVAL_SECONDS)
        except asyncio.CancelledError:
            break


# Manages application startup initialization and clean background task shutdown
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Fail fast rather than run with repository-committed secrets
    validate_production_secrets()

    # Auto-initialize database tables if not already existing
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            if is_postgres:
                await conn.execute(text(POSTGRES_SCHEMA_SYNC_BLOCK))
    except Exception as e:
        logger.warning(f"Warning during DB table auto-creation / schema sync: {e}")

    # Seed the demo library catalogue once (no-op whenever books already exist)
    try:
        if is_postgres:
            async with AsyncSessionLocal() as seed_session:
                await seed_library_demo_books(seed_session)
    except Exception as e:
        logger.warning(f"Library seed skipped: {e}")

    # Launch periodic background cleaner for expired email OTPs
    cleanup_task = asyncio.create_task(purge_expired_otps_worker())

    yield

    # Clean shutdown of background worker
    cleanup_task.cancel()
    try:
        await cleanup_task
    except asyncio.CancelledError:
        pass

    await engine.dispose()


# Initializes FastAPI application with metadata and lifespan handler
app = FastAPI(
    title="BLE Attendance Verification API",
    version="1.0.0",
    docs_url="/docs" if settings.APP_ENV != "production" else None,
    redoc_url="/redoc" if settings.APP_ENV != "production" else None,
    lifespan=lifespan,
)


# Catches unhandled server exceptions and returns structured JSON responses
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error("Unhandled server exception: %s", exc, exc_info=True)
    if settings.APP_ENV == "development":
        content = {
            "detail": str(exc),
            "type": type(exc).__name__,
            "traceback": traceback.format_exc(),
        }
    else:
        content = {
            "detail": "An internal server error occurred.",
            "type": "InternalServerError",
        }
    return JSONResponse(status_code=500, content=content)


# Enable cross-origin requests for mobile clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Logs non-health HTTP requests and response status codes
@app.middleware("http")
async def log_requests(request: Request, call_next):
    path = request.url.path
    if path in ("/health", "/"):
        return await call_next(request)
    method = request.method
    logger.info(f"==> Incoming {method} {path}")
    response = await call_next(request)
    logger.info(f"<== Finished {method} {path} - Status {response.status_code}")
    return response


# Registers attendance, authentication, and library API routers
app.include_router(attendance.router, prefix=settings.API_V1_STR)
app.include_router(auth.router, prefix=settings.API_V1_STR)
app.include_router(library.router, prefix=settings.API_V1_STR)


# Returns basic service discovery information
@app.get("/", tags=["System"])
async def root():
    return {
        "status": "online",
        "service": "BLE Attendance Verification API",
        "docs": "/docs",
        "health": "/health"
    }


# Health check probe for container platforms and load balancers
@app.get("/health", tags=["System"])
async def health_check():
    return {"status": "ok", "env": settings.APP_ENV}


# Diagnostic endpoint restricted to test environments
@app.get("/debug-db", tags=["System"])
async def debug_db():
    if not is_test_environment():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
            if is_postgres:
                await conn.execute(text(POSTGRES_SCHEMA_SYNC_BLOCK))
            sess_rows = (await conn.execute(text("SELECT id, session_token, status, total_verified, total_spoofers, started_at FROM attendance_sessions ORDER BY started_at DESC LIMIT 5;"))).mappings().all()
            rec_rows = (await conn.execute(text("SELECT id, session_id, ble_device_id, status, verification_method, recorded_at FROM attendance_records ORDER BY recorded_at DESC LIMIT 5;"))).mappings().all()

        return {
            "status": "connected",
            "recent_sessions": [dict(r) for r in sess_rows],
            "recent_records": [dict(r) for r in rec_rows],
        }
    except Exception as e:
        return {"status": "connection_error", "error": str(e), "type": type(e).__name__}
