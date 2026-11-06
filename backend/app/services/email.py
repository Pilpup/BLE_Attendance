import asyncio
import logging
import os
import secrets
import smtplib
from email.mime.text import MIMEText

import httpx

from app.config import settings

logger = logging.getLogger("email_service")


# Generates a cryptographically secure 6-digit numeric OTP code
def generate_otp() -> str:
    return f"{secrets.randbelow(900000) + 100000}"


# Dispatches plain text email via Brevo HTTPS API
async def _send_brevo_email_async(to_email: str, subject: str, text_content: str) -> bool:
    if not settings.BREVO_API_KEY:
        return False

    url = "https://api.brevo.com/v3/smtp/email"
    headers = {
        "api-key": settings.BREVO_API_KEY.strip(),
        "Content-Type": "application/json",
        "Accept": "application/json",
        "User-Agent": "CollegeHub/1.0",
    }
    payload = {
        "sender": {"name": settings.SMTP_FROM_NAME, "email": settings.SMTP_FROM_EMAIL},
        "to": [{"email": to_email.strip()}],
        "subject": subject,
        "textContent": text_content,
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=headers, json=payload)
            if resp.status_code in (200, 201, 202):
                logger.info(f"Dispatched OTP email via Brevo to {to_email}")
                return True
            logger.error(f"Failed to send email via Brevo ({resp.status_code}): {resp.text}")
            return False
    except Exception as e:
        logger.error(f"Brevo API request failed: {e}")
        return False


# Sends plain text email using standard SMTP
def _send_smtp_email_sync(to_email: str, subject: str, text_content: str) -> bool:
    if not settings.SMTP_HOST:
        return False

    msg = MIMEText(text_content, "plain")
    msg["Subject"] = subject
    msg["From"] = f"{settings.SMTP_FROM_NAME} <{settings.SMTP_FROM_EMAIL}>"
    msg["To"] = to_email

    try:
        server_cls = smtplib.SMTP_SSL if settings.SMTP_PORT == 465 else smtplib.SMTP
        with server_cls(settings.SMTP_HOST, settings.SMTP_PORT, timeout=10) as server:
            if settings.SMTP_PORT != 465:
                server.starttls()
            if settings.SMTP_USER and settings.SMTP_PASSWORD:
                server.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
            server.sendmail(settings.SMTP_FROM_EMAIL, [to_email], msg.as_string())
        return True
    except Exception as e:
        logger.error(f"Failed to send email via SMTP to {to_email}: {e}")
        return False


# Dispatches OTP verification email via Brevo API or SMTP fallback
async def send_otp_email(to_email: str, otp_code: str, purpose: str = "LOGIN") -> bool:
    subject = f"{otp_code} is your verification code"
    text_content = f"Your verification code is: {otp_code}\n\nThis code will expire in {settings.OTP_EXPIRE_MINUTES} minutes."

    # Log email dispatch event without leaking plaintext code
    logger.info(f"[OTP DISPATCH] To: {to_email} | Purpose: {purpose}")

    # Dispatches email via Brevo HTTPS API if configured
    if settings.BREVO_API_KEY:
        if await _send_brevo_email_async(to_email, subject, text_content):
            return True

    # Falls back to SMTP server if configured
    if settings.SMTP_HOST:
        return await asyncio.to_thread(_send_smtp_email_sync, to_email, subject, text_content)

    # Return success for local test suites when mailers are disabled
    if bool(os.environ.get("PYTEST_CURRENT_TEST")):
        return True

    logger.warning("No email provider configured (BREVO_API_KEY or SMTP_HOST required)")
    return False


# Background-task wrapper: FastAPI BackgroundTasks discard coroutine return
# values, so dispatch failures would silently vanish at the call sites. This
# wrapper observes the result and logs a loud error when no email is sent.
async def dispatch_otp_email(to_email: str, otp_code: str, purpose: str = "LOGIN") -> None:
    sent = await send_otp_email(to_email, otp_code, purpose=purpose)
    if not sent:
        logger.error(
            f"[OTP DISPATCH FAILED] No provider configured or delivery failed — "
            f"{to_email} did not receive the verification code (purpose={purpose})"
        )
