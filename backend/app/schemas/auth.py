from typing import Optional
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field


# request payload for user self-registration
class RegisterRequest(BaseModel):
    name: str = Field(..., min_length=2, max_length=120)
    email: str = Field(..., min_length=3, max_length=255)
    password: str = Field(..., min_length=6)
    role: str = Field(default="STUDENT", description="STUDENT, TEACHER, or STAFF")
    # roll number for students (e.g. '22BCSE104') or employee ID for teachers
    roll_number: str = Field(..., min_length=2, max_length=50)
    department: str = Field(..., description="Academic department (e.g. CSE, CIVIL)")
    program: Optional[str] = Field(None, description="B.Tech or BCA")
    semester: Optional[int] = Field(None, ge=1, le=8)
    section: Optional[str] = Field(None, max_length=10)
    # required only when registering as a TEACHER
    faculty_code: Optional[str] = Field(None, description="Faculty registration passcode")


# request payload for logging in
class LoginRequest(BaseModel):
    email: str
    password: str


# public user response
class UserResponse(BaseModel):
    id: UUID
    name: str
    email: str
    role: str
    roll_number: Optional[str] = None
    department: Optional[str] = None
    program: Optional[str] = None
    semester: Optional[int] = None
    section: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


# token response with bearer token and user object
class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserResponse


# OTP verification request
class VerifyOtpRequest(BaseModel):
    email: str = Field(..., min_length=3, max_length=255)
    otp_code: str = Field(..., min_length=4, max_length=10)


# Resend OTP request
class ResendOtpRequest(BaseModel):
    email: str = Field(..., min_length=3, max_length=255)
    purpose: str = Field(default="LOGIN", description="LOGIN or REGISTER")


# Generic response when OTP is dispatched
class OtpSentResponse(BaseModel):
    message: str
    email: str
    expires_in_seconds: int = 600
    requires_otp: bool = True


# Flexible login response: either requires 2FA OTP or returns token directly
class LoginResponse(BaseModel):
    requires_otp: bool = True
    message: str = "Verification code sent to your email"
    email: str
    access_token: Optional[str] = None
    token_type: Optional[str] = "bearer"
    user: Optional[UserResponse] = None

