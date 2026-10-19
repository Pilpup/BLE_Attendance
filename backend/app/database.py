from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings

# Sanitize Neon / PostgreSQL connection string for asyncpg
db_url = settings.DATABASE_URL
if db_url.startswith("postgres://"):
    db_url = db_url.replace("postgres://", "postgresql+asyncpg://", 1)
elif db_url.startswith("postgresql://") and not db_url.startswith("postgresql+asyncpg://"):
    db_url = db_url.replace("postgresql://", "postgresql+asyncpg://", 1)

# asyncpg expects 'ssl=require', whereas standard PG URLs use 'sslmode=require'
if "sslmode=" in db_url:
    db_url = db_url.replace("sslmode=", "ssl=")

is_postgres = db_url.startswith("postgresql")
connect_args = {
    "statement_cache_size": 0,
    "prepared_statement_cache_size": 0,
} if is_postgres else {}

engine = create_async_engine(
    db_url,
    echo=(settings.APP_ENV == "development"),
    connect_args=connect_args,
    pool_pre_ping=True,
    pool_recycle=300 if is_postgres else -1,
)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
)


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        try:
            yield session
        finally:
            await session.close()
