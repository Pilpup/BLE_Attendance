import asyncio
import logging
import secrets
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

import httpx

from app.config import settings

logger = logging.getLogger("email_service")


# Generates a cryptographically secure 6-digit numeric OTP code
def generate_otp() -> str:
    return f"{secrets.randbelow(900000) + 100000}"


# Dispatches email via Brevo HTTPS API
async def _send_brevo_email_async(to_email: str, subject: str, text_content: str, html_content: str) -> bool:
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
        "htmlContent": html_content,
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=headers, json=payload)
            if resp.status_code in (200, 201, 202):
                logger.info(f"Successfully dispatched OTP email via Brevo to {to_email}")
                return True
            logger.error(f"Failed to send email via Brevo API ({resp.status_code}): {resp.text}")
            return False
    except Exception as e:
        logger.error(f"Brevo API request failed: {e}")
        return False


# Sends email using standard SMTP with TLS or SSL
def _send_smtp_email_sync(to_email: str, subject: str, text_content: str, html_content: str) -> bool:
    if not settings.SMTP_HOST:
        return False

    msg = MIMEMultipart("alternative")
    msg["Subject"] = subject
    msg["From"] = f"{settings.SMTP_FROM_NAME} <{settings.SMTP_FROM_EMAIL}>"
    msg["To"] = to_email

    msg.attach(MIMEText(text_content, "plain"))
    msg.attach(MIMEText(html_content, "html"))

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
    action_text = "sign in to your account (Two-Factor Authentication)" if purpose == "LOGIN" else "complete your account registration"
    subject = f"{otp_code} is your College Hub verification code"

    # Formats plain text fallback body
    text_content = (
        f"College Hub Verification\n\n"
        f"Your verification code is: {otp_code}\n\n"
        f"Use this code to {action_text}.\n"
        f"This code will expire in {settings.OTP_EXPIRE_MINUTES} minutes.\n"
        f"If you did not request this, please ignore this email."
    )

    # Formats responsive HTML email template
    html_content = f"""
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8">
        <style>
            body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #0b0f17; color: #f8fafc; margin: 0; padding: 24px; }}
            .card {{ max-width: 460px; margin: 0 auto; background: #131b2a; border-radius: 16px; border: 1px solid #1e293b; padding: 32px; text-align: center; }}
            .title {{ font-size: 22px; font-weight: bold; color: #f8fafc; margin-bottom: 8px; }}
            .subtitle {{ font-size: 14px; color: #94a3b8; margin-bottom: 24px; }}
            .otp-box {{ background: #0f172a; border: 1px solid #3b82f6; border-radius: 12px; padding: 18px; font-size: 32px; font-weight: 800; letter-spacing: 8px; color: #60a5fa; font-family: monospace; margin: 20px 0; }}
            .footer {{ font-size: 12px; color: #64748b; margin-top: 24px; }}
        </style>
    </head>
    <body>
        <div class="card">
            <div class="title">College Hub</div>
            <div class="subtitle">Verification code to {action_text}:</div>
            <div class="otp-box">{otp_code}</div>
            <div class="subtitle">Valid for <strong>{settings.OTP_EXPIRE_MINUTES} minutes</strong>. Do not share this code with anyone.</div>
            <div class="footer">If you did not make this request, you can safely ignore this email.</div>
        </div>
    </body>
    </html>
    """

    # Logs OTP code to console for local testing and debugging
    print(f"\n==================================================================")
    print(f"[OTP DISPATCH] To: {to_email} | Purpose: {purpose}")
    print(f">>> CODE: {otp_code} <<< (Expires in {settings.OTP_EXPIRE_MINUTES} min)")
    print(f"==================================================================\n")

    # Dispatches email via Brevo HTTPS API if configured
    if settings.BREVO_API_KEY:
        if await _send_brevo_email_async(to_email, subject, text_content, html_content):
            return True

    # Falls back to SMTP server if configured
    if settings.SMTP_HOST:
        return await asyncio.to_thread(_send_smtp_email_sync, to_email, subject, text_content, html_content)

    return True
