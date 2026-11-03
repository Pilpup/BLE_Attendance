from typing import AsyncGenerator
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings


# Sanitizes database URL for asyncpg by stripping unsupported libpq query parameters
def clean_asyncpg_url(raw_url: str) -> str:
    if raw_url.startswith("postgres://"):
        raw_url = raw_url.replace("postgres://", "postgresql+asyncpg://", 1)
    elif raw_url.startswith("postgresql://") and not raw_url.startswith("postgresql+asyncpg://"):
        raw_url = raw_url.replace("postgresql://", "postgresql+asyncpg://", 1)

    parsed = urlparse(raw_url)
    if not parsed.scheme.startswith("postgresql"):
        return raw_url

    # Strips libpq parameters incompatible with asyncpg
    unsupported_params = {"channel_binding", "sslmode", "target_session_attrs", "gssencmode"}
    query_pairs = parse_qsl(parsed.query)
    clean_pairs = []
    has_ssl = False

    for k, v in query_pairs:
        if k in unsupported_params:
            if k == "sslmode" and v in ("require", "verify-ca", "verify-full"):
                has_ssl = True
            continue
        if k == "ssl":
            has_ssl = True
        clean_pairs.append((k, v))

    if has_ssl or "neon.tech" in (parsed.hostname or ""):
        if not any(k == "ssl" for k, _ in clean_pairs):
            clean_pairs.append(("ssl", "require"))

    new_query = urlencode(clean_pairs)
    return urlunparse(parsed._replace(query=new_query))


# Configures database connection engine and pool parameters
db_url = clean_asyncpg_url(settings.DATABASE_URL)
is_postgres = db_url.startswith("postgresql")
connect_args = {
    "statement_cache_size": 0,
    "prepared_statement_cache_size": 0,
} if is_postgres else ({"check_same_thread": False} if db_url.startswith("sqlite") else {})

# Creates asynchronous SQLAlchemy database engine
engine = create_async_engine(
    db_url,
    echo=(settings.APP_ENV == "development"),
    connect_args=connect_args,
    pool_pre_ping=True,
    pool_recycle=300 if is_postgres else -1,
)

# Configures session factory for async database transactions
AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
)


# Yields an asynchronous database session dependency for FastAPI routes
async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        yield session
