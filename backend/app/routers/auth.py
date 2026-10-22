import uuid
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.core.security import (
    create_access_token,
    get_current_user,
    hash_password,
    verify_password,
)
from app.database import get_db
from app.models.attendance import User
from app.schemas.auth import LoginRequest, RegisterRequest, TokenResponse, UserResponse

router = APIRouter(prefix="/auth", tags=["Auth"])


# self-registration for students and faculty
@router.post(
    "/register",
    response_model=UserResponse,
    status_code=status.HTTP_201_CREATED,
)
async def register(
    payload: RegisterRequest,
    db: AsyncSession = Depends(get_db),
):
    normalized_role = payload.role.strip().upper()
    if normalized_role not in ["STUDENT", "TEACHER", "STAFF"]:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Role must be STUDENT, TEACHER, or STAFF",
        )

    # enforce faculty/staff secret passcode to prevent students from claiming privileged roles
    if normalized_role in ["TEACHER", "STAFF"]:
        if payload.faculty_code != settings.FACULTY_SIGNUP_SECRET:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Invalid faculty or staff registration passcode",
            )

    # check if user with same email or roll number already exists
    existing = await db.execute(
        select(User).where(
            or_(User.email == payload.email, User.roll_number == payload.roll_number)
        )
    )
    if existing.scalars().first():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="User with this email or roll number already registered",
        )

    # create user in Neon PostgreSQL
    new_user = User(
        id=uuid.uuid4(),
        email=payload.email,
        hashed_password=hash_password(payload.password),
        full_name=payload.name,
        roll_number=payload.roll_number,
        role=normalized_role,
        department=payload.department,
        program=payload.program,
        semester=payload.semester,
        section=payload.section,
    )
    db.add(new_user)
    await db.commit()
    await db.refresh(new_user)

    return UserResponse(
        id=new_user.id,
        name=new_user.full_name,
        email=new_user.email,
        role=new_user.role,
        roll_number=new_user.roll_number,
        department=new_user.department,
        program=new_user.program,
        semester=new_user.semester,
        section=new_user.section,
    )


# login endpoint returning bearer JWT token
@router.post("/login", response_model=TokenResponse)
async def login(
    payload: LoginRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(User).where(User.email == payload.email))
    user = result.scalars().first()

    if not user or not verify_password(payload.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password",
        )

    token_data = {
        "sub": str(user.id),
        "name": user.full_name,
        "role": user.role,
        "roll_number": user.roll_number,
    }
    token = create_access_token(data=token_data)

    user_resp = UserResponse(
        id=user.id,
        name=user.full_name,
        email=user.email,
        role=user.role,
        roll_number=user.roll_number,
        department=user.department,
        program=user.program,
        semester=user.semester,
        section=user.section,
    )

    return TokenResponse(
        access_token=token,
        token_type="bearer",
        user=user_resp,
    )


# retrieve profile of current authenticated user
@router.get("/me", response_model=UserResponse)
async def get_my_profile(current_user: User = Depends(get_current_user)):
    return UserResponse(
        id=current_user.id,
        name=current_user.full_name,
        email=current_user.email,
        role=current_user.role,
        roll_number=current_user.roll_number,
        department=current_user.department,
        program=current_user.program,
        semester=current_user.semester,
        section=current_user.section,
    )
