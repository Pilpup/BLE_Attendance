from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings

is_postgres = settings.DATABASE_URL.startswith("postgresql")
connect_args = {
    "statement_cache_size": 0,
    "prepared_statement_cache_size": 0,
} if is_postgres else {}

engine = create_async_engine(
    settings.DATABASE_URL,
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
