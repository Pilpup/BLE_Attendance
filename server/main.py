import uuid
import time
from enum import Enum
from typing import Dict, List, Optional
from fastapi import FastAPI, Depends, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from auth import (
    create_access_token,
    verify_password,
    hash_password,
    get_current_user,
    require_teacher,
    require_student
)

from graph_engine import audit_classroom_mesh

app = FastAPI(title = "BLE Attendance API", version = "1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins = ["*"],
    allow_credentials = True,
    allow_methods = ["*"],
    allow_headers = ["*"]
)

# temp storing in memory until priya connects database.py
users_db: Dict[str, dict] = {}

# in memory store for classroom session:
active_sessions: Dict[str, dict] = {}

class ProgramEnum(str, Enum):
    BTECH = "B.Tech"
    BCA = "BCA"

class BranchEnum(str, Enum):
    CSE = "CSE"
    CIVIL = "CIVIL"
    MECHANICAL = "MECHANICAL"
    ELECTRICAL = "ELECTRICAL"
    ELECTRONICS = "ELECTRONICS"
    BCA = "BCA"

class RegisterRequest(BaseModel):
    email: str
    password: str
    role: str
    name: str
    roll_number: str
    department: str
    program: Optional[str] = None
    semester: Optional[int] = None
    section: Optional[str] = None

class LoginRequest(BaseModel):
    email: str
    password: str

class StartSessionRequest(BaseModel):
    course_name: str

class SubmitAttendanceRequest(BaseModel):
    session_id: str
    neighbors: List[str]


