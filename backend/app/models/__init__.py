from app.models.attendance import AttendanceRecord, AttendanceSession, Base, Course, EmailOTP, SessionSubmission, User
from app.models.library import Book, BookReservation

# Central database models export
__all__ = [
    "Base",
    "User",
    "Course",
    "AttendanceSession",
    "AttendanceRecord",
    "SessionSubmission",
    "EmailOTP",
    "Book",
    "BookReservation",
]
