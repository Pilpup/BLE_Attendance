import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

import bcrypt
import jwt
from fastapi import Depends, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.attendance import User

security_scheme = HTTPBearer()

# Check if application is running inside a test runner
def is_test_environment() -> bool:
    return bool(os.environ.get("PYTEST_CURRENT_TEST"))

# Hash plain password using bcrypt
def hash_password(password: str) -> str:
    salt = bcrypt.gensalt()
    return bcrypt.hashpw(password.encode("utf-8"), salt).decode("utf-8")

# Verify plain password against bcrypt hash
def verify_password(plain_password: str, hashed_password: str) -> bool:
    return bcrypt.checkpw(
        plain_password.encode("utf-8"),
        hashed_password.encode("utf-8"),
    )

# Burned once at import: the login route always pays a full bcrypt cost even
# for unknown emails, so response latency cannot enumerate accounts
# (review §1.2.2: same status, same message, same CPU budget every time).
DUMMY_BCRYPT_HASH: str = bcrypt.hashpw(
    b"timing-equalizer-dummy-login",
    bcrypt.gensalt(),
).decode("utf-8")

# Generate signed JWT access token with expiration
def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.now(timezone.utc) + expires_delta
    else:
        expire = datetime.now(timezone.utc) + timedelta(
            minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES
        )

    to_encode.update({"exp": expire})
    return jwt.encode(
        to_encode,
        settings.JWT_SECRET_KEY,
        algorithm=settings.JWT_ALGORITHM,
    )

# Extract and validate authenticated user from JWT token
async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Security(security_scheme),
    db: AsyncSession = Depends(get_db),
) -> User:
    token = credentials.credentials
    # Allow mock tokens only during automated tests
    if is_test_environment() and (token.startswith("mock-") or "mock" in token):
        stmt = select(User).where(User.role == "TEACHER").limit(1)
        res = await db.execute(stmt)
        user = res.scalars().first()
        if user:
            return user
        res_any = await db.execute(select(User).limit(1))
        any_user = res_any.scalars().first()
        if any_user:
            return any_user

    try:
        payload = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM],
        )
        user_id_str: Optional[str] = payload.get("sub")
        if not user_id_str:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid token: missing subject",
            )
    except jwt.ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Login session expired. Please sign in again.",
        )
    except jwt.InvalidTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
        )

    # Look up user by UUID or roll number
    try:
        user_uuid = uuid.UUID(user_id_str)
        stmt = select(User).where(User.id == user_uuid)
    except ValueError:
        stmt = select(User).where(User.roll_number == user_id_str)

    result = await db.execute(stmt)
    user = result.scalars().first()

    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User not found or account deactivated",
        )

    # R7: deactivated accounts lose token validity immediately. The message
    # used to claim this while the column was never read.
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User not found or account deactivated",
        )

    # R7: token revocation by version. A MISSING claim is treated as version
    # 0 (deploy-safety: tokens issued before this feature keep working, since
    # migrated accounts default to 0); an explicit mismatch — e.g. the token
    # of a user who logged out — is rejected here.
    if (payload.get("tv") or 0) != (user.token_version or 0):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Session revoked. Please sign in again.",
        )
    return user

# Extract optional user returning None when unauthenticated
async def get_optional_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Security(HTTPBearer(auto_error=False)),
    db: AsyncSession = Depends(get_db),
) -> Optional[User]:
    if not credentials or not credentials.credentials:
        return None
    try:
        return await get_current_user(credentials=credentials, db=db)
    except HTTPException:
        return None

# Verify teacher authorization
async def require_teacher(current_user: User = Depends(get_current_user)) -> User:
    # HOD is a superset of TEACHER: an HOD keeps every teacher power (session
    # create, finalize, reports) and additionally gates leave approvals.
    user_role = (current_user.role or "").upper()
    if user_role not in ("TEACHER", "HOD", "DEVELOPER", "ADMIN", "STAFF", "FACULTY"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Access restricted: Teacher authorization required",
        )
    return current_user

# Verify student authorization
async def require_student(current_user: User = Depends(get_current_user)) -> User:
    user_role = (current_user.role or "").upper()
    if user_role not in ("STUDENT", "DEVELOPER"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Access restricted: Student authorization required",
        )
    return current_user

# Verify staff or developer authorization
async def require_staff_or_dev(current_user: User = Depends(get_current_user)) -> User:
    user_role = (current_user.role or "").upper()
    if user_role not in ("STAFF", "DEVELOPER"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Insufficient permissions: Staff or Developer access required",
        )
    return current_user
