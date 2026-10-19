from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.routers import attendance

app = FastAPI(
    title="BLE Attendance Verification API",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Routers ───────────────────────────────────────────────────────────────────
app.include_router(attendance.router, prefix=settings.API_V1_STR)
# When JWT is ready, your teammate adds:
# from app.routers import auth
# app.include_router(auth.router, prefix=f"{settings.API_V1_STR}/auth", tags=["Auth"])


@app.get("/health", tags=["System"])
async def health_check():
    return {"status": "ok", "env": settings.APP_ENV}
