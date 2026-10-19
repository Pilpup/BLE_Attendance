import os
import bcrypt
import jwt
from datetime import datetime, timedelta, timezone
from fastapi import HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from dotenv import load_dotenv

# self explainatory
load_dotenv()

# temp secret key
SECRET_KEY = os.getenv("JWT_SECRET", "dev-secret-key-change-in-production-12345")
# HMAC with SHA-256
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRY = 30

# fastapi helper - 'Authorization: Bearer<token>'
security = HTTPBearer()

# random crypt salt and scrambles the password using bcrypt
def hash_password(password: str) -> str:
    salt = bcrypt.gensalt(rounds = 10)
    return bcrypt.hashpw(password.encode("utf-8"), salt).decode("utf-8")

# password check
def verify_password(plain_password: str, hashed_password: str) -> bool:
    return bcrypt.checkpw(plain_password.encode("utf-8"), hashed_password.encode("utf-8"))

# create digital jwt(json web token) for the user (ID card)
# package user ID, Name, Role with expiry date
# signs it with SECRET_KEY so no one can mod it
def create_access_token(user_id: str, name: str, role: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(days= ACCESS_TOKEN_EXPIRY)
    payload = {
        "sub": user_id,
        "name": name,
        "role": role,
        "exp": expire
    }
    return jwt.encode(payload, SECRET_KEY, algorithm = ALGORITHM)

# fastapi dependancy: intercepts incoming HTTP request, extracts token from
# Authorization: Bearer<token> header, and verify crypt sig
# blocks with error 401 if expired or false cred
def get_current_user(credentials: HTTPAuthorizationCredentials = Security(security)) -> dict:
    token = credentials.credentials
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms = [ALGORITHM])
        user_id: str = payload.get("sub")
        role: str = payload.get("role")
        name: str = payload.get("name")

        if(user_id is None or role is None):
            raise HTTPException(
                status_code = status.HTTP_401_UNAUTHORIZED,
                detail = "Invalid token payload"
            )
        return {"id": user_id, "name": name, "role": role}

    except jwt.ExpiredSignatureError:
        raise HTTPException(
            status_code = status.HTTP_401_UNAUTHORIZED,
            detail = "Session token has expired"
        )
    except jwt.InvalidTokenError:
        raise HTTPException(
            status_code = status.HTTP_401_UNAUTHORIZED,
            detail = "Could not validate credentials"
        )

# ensure only user with role teacher can access the endpoint
def require_teacher(user: dict = Security(get_current_user)) -> dict:
    if user.get("role") != "TEACHER":
        raise HTTPException(
            status_code = status.HTTP_403_FORBIDDEN,
            detail = "Access restricted: teacher permission required"
        )
    return user

# ensure only user with role student can access the endpoint
def require_student(user: dict = Security(get_current_user)) -> dict:
    if user.get("role") != "STUDENT":
        raise HTTPException(
            status_code = status.HTTP_403_FORBIDDEN,
            detail = "Access restricted: student permission required"
        )
    return user

