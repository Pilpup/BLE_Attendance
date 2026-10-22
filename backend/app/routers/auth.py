import uuid
from datetime import datetime, timedelta, timezone
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, status
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
from app.models.attendance import EmailOTP, User
from app.schemas.auth import (
    LoginRequest,
    LoginResponse,
    OtpSentResponse,
    RegisterRequest,
    ResendOtpRequest,
    TokenResponse,
    UserResponse,
    VerifyOtpRequest,
)
from app.services.email import generate_otp, send_otp_email

router = APIRouter(prefix="/auth", tags=["Auth"])


def ensure_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


# Step 1 of Registration: validate details and send verification OTP
@router.post(
    "/register",
    response_model=OtpSentResponse,
    status_code=status.HTTP_200_OK,
)
async def register_send_otp(
    payload: RegisterRequest,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db),
):
    normalized_role = payload.role.strip().upper()
    if normalized_role not in ["STUDENT", "TEACHER", "STAFF"]:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Role must be STUDENT, TEACHER, or STAFF",
        )

    # enforce faculty/staff secret passcode
    if normalized_role in ["TEACHER", "STAFF"]:
        if payload.faculty_code != settings.FACULTY_SIGNUP_SECRET:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Invalid faculty or staff registration passcode",
            )

    email_clean = payload.email.strip().lower()
    if "@mgail.com" in email_clean or "@gamil.com" in email_clean:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Did you mean @gmail.com? Please check your email address spelling.",
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

    # generate 6-digit OTP
    otp_code = generate_otp()
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=settings.OTP_EXPIRE_MINUTES)

    # store temporary registration payload alongside OTP
    temp_data = {
        "name": payload.name.strip(),
        "email": payload.email.strip().lower(),
        "hashed_password": hash_password(payload.password),
        "roll_number": payload.roll_number.strip(),
        "role": normalized_role,
        "department": payload.department.strip() if payload.department else None,
        "program": payload.program.strip() if payload.program else None,
        "semester": payload.semester,
        "section": payload.section.strip() if payload.section else None,
    }

    # remove any prior registration OTPs for this email
    prior = await db.execute(
        select(EmailOTP).where(
            EmailOTP.email == payload.email.strip().lower(),
            EmailOTP.purpose == "REGISTER",
        )
    )
    for p in prior.scalars().all():
        await db.delete(p)

    new_otp = EmailOTP(
        id=uuid.uuid4(),
        email=payload.email.strip().lower(),
        otp_code=otp_code,
        purpose="REGISTER",
        temp_payload=temp_data,
        attempts=0,
        expires_at=expires_at,
    )
    db.add(new_otp)
    await db.commit()

    # dispatch email in background
    background_tasks.add_task(send_otp_email, payload.email.strip().lower(), otp_code, purpose="REGISTER")

    return OtpSentResponse(
        message=f"Verification code sent to {payload.email}",
        email=payload.email.strip().lower(),
        expires_in_seconds=settings.OTP_EXPIRE_MINUTES * 60,
        requires_otp=True,
    )


# Step 2 of Registration: verify OTP and commit user to database
@router.post(
    "/register/verify-otp",
    response_model=TokenResponse,
    status_code=status.HTTP_201_CREATED,
)
async def register_verify_otp(
    payload: VerifyOtpRequest,
    db: AsyncSession = Depends(get_db),
):
    email = payload.email.strip().lower()
    now = datetime.now(timezone.utc)

    result = await db.execute(
        select(EmailOTP)
        .where(EmailOTP.email == email, EmailOTP.purpose == "REGISTER")
        .order_by(EmailOTP.created_at.desc())
    )
    otp_record = result.scalars().first()

    if not otp_record or now > ensure_utc(otp_record.expires_at):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Verification code is invalid or has expired. Please request a new code.",
        )

    if otp_record.attempts >= 5:
        await db.delete(otp_record)
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many incorrect attempts. Please request a new code.",
        )

    if otp_record.otp_code != payload.otp_code.strip():
        otp_record.attempts += 1
        await db.commit()
        remaining = 5 - otp_record.attempts
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Incorrect verification code ({remaining} attempts remaining).",
        )

    # Valid OTP -> commit user
    data = otp_record.temp_payload or {}
    new_user = User(
        id=uuid.uuid4(),
        email=data.get("email", email),
        hashed_password=data["hashed_password"],
        full_name=data["name"],
        roll_number=data.get("roll_number"),
        role=data.get("role", "STUDENT"),
        department=data.get("department"),
        program=data.get("program"),
        semester=data.get("semester"),
        section=data.get("section"),
    )
    db.add(new_user)
    await db.delete(otp_record)
    await db.commit()
    await db.refresh(new_user)

    token_data = {
        "sub": str(new_user.id),
        "name": new_user.full_name,
        "role": new_user.role,
        "roll_number": new_user.roll_number,
    }
    token = create_access_token(data=token_data)

    user_resp = UserResponse(
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

    return TokenResponse(
        access_token=token,
        token_type="bearer",
        user=user_resp,
    )


# Step 1 of Login (Two-Factor Auth): verify password, generate and send 2FA OTP
@router.post("/login", response_model=LoginResponse)
async def login(
    payload: LoginRequest,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db),
):
    email = payload.email.strip().lower()
    result = await db.execute(select(User).where(User.email == email))
    user = result.scalars().first()

    if not user or not verify_password(payload.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password",
        )

    # Password is correct! Generate and dispatch 2FA OTP
    otp_code = generate_otp()
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=settings.OTP_EXPIRE_MINUTES)

    # Remove any previous login OTPs
    prior = await db.execute(
        select(EmailOTP).where(
            EmailOTP.email == email,
            EmailOTP.purpose == "LOGIN",
        )
    )
    for p in prior.scalars().all():
        await db.delete(p)

    new_otp = EmailOTP(
        id=uuid.uuid4(),
        email=email,
        otp_code=otp_code,
        purpose="LOGIN",
        attempts=0,
        expires_at=expires_at,
    )
    db.add(new_otp)
    await db.commit()

    # Send 2FA email in background
    background_tasks.add_task(send_otp_email, email, otp_code, purpose="LOGIN")

    return LoginResponse(
        requires_otp=True,
        message=f"Two-factor verification code sent to {email}",
        email=email,
    )


# Step 2 of Login (Two-Factor Auth): verify OTP and return JWT access token
@router.post("/login/verify-otp", response_model=TokenResponse)
async def login_verify_otp(
    payload: VerifyOtpRequest,
    db: AsyncSession = Depends(get_db),
):
    email = payload.email.strip().lower()
    now = datetime.now(timezone.utc)

    result = await db.execute(
        select(EmailOTP)
        .where(EmailOTP.email == email, EmailOTP.purpose == "LOGIN")
        .order_by(EmailOTP.created_at.desc())
    )
    otp_record = result.scalars().first()

    if not otp_record or now > ensure_utc(otp_record.expires_at):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Verification code is invalid or has expired. Please request a new code.",
        )

    if otp_record.attempts >= 5:
        await db.delete(otp_record)
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many incorrect attempts. Please request a new code.",
        )

    if otp_record.otp_code != payload.otp_code.strip():
        otp_record.attempts += 1
        await db.commit()
        remaining = 5 - otp_record.attempts
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Incorrect verification code ({remaining} attempts remaining).",
        )

    # Valid OTP -> complete 2FA login
    await db.delete(otp_record)
    await db.commit()

    u_res = await db.execute(select(User).where(User.email == email))
    user = u_res.scalars().first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

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


# Resend OTP endpoint (with cooldown check)
@router.post("/otp/resend", response_model=OtpSentResponse)
async def resend_otp(
    payload: ResendOtpRequest,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db),
):
    email = payload.email.strip().lower()
    purpose = payload.purpose.strip().upper()
    now = datetime.now(timezone.utc)

    # Check for recent OTP to prevent spam (45 seconds cooldown)
    recent = await db.execute(
        select(EmailOTP)
        .where(EmailOTP.email == email, EmailOTP.purpose == purpose)
        .order_by(EmailOTP.created_at.desc())
    )
    last_otp = recent.scalars().first()
    if last_otp:
        elapsed = (now - ensure_utc(last_otp.created_at)).total_seconds()
        if elapsed < 45:
            wait_time = int(45 - elapsed)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"Please wait {wait_time}s before requesting a new code.",
            )

    otp_code = generate_otp()
    expires_at = now + timedelta(minutes=settings.OTP_EXPIRE_MINUTES)

    if last_otp:
        last_otp.otp_code = otp_code
        last_otp.attempts = 0
        last_otp.expires_at = expires_at
        last_otp.created_at = now
    else:
        new_otp = EmailOTP(
            id=uuid.uuid4(),
            email=email,
            otp_code=otp_code,
            purpose=purpose,
            attempts=0,
            expires_at=expires_at,
        )
        db.add(new_otp)

    await db.commit()
    background_tasks.add_task(send_otp_email, email, otp_code, purpose=purpose)

    return OtpSentResponse(
        message=f"New verification code sent to {email}",
        email=email,
        expires_in_seconds=settings.OTP_EXPIRE_MINUTES * 60,
        requires_otp=True,
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
