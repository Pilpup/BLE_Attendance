import io

import numpy as np
import pytest
from httpx import ASGITransport, AsyncClient
from PIL import Image
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.database import get_db
from app.main import app
from app.models.attendance import AttendanceRecord, Base, FaceEnrollment, User
from app.services.face_engine import face_registry
from tests.test_backend import _register_battery_student


@pytest.mark.anyio
async def test_flipped_student_1to1_match_and_remark_grade_mismatch():
    face_registry.clear_all()
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db

    img = Image.new("RGB", (100, 100), color=(180, 140, 120))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    raw = buf.getvalue()

    headers = {
        "Content-Type": "image/jpeg",
        "X-Session-Salt": "DEMO1",
        "X-Student-Id": "FLIP1",
        "X-Neighbors": "PEER-A1",
        "X-Game-Score": "20",
    }

    try:
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            token = await _register_battery_student(client, factory)
            headers["Authorization"] = f"Bearer {token}"
            async with factory() as session:
                student = (await session.execute(select(User).where(User.role == "STUDENT"))).scalars().first()
                assert student is not None
                capture = face_registry.engine.extract_capture(raw)
                assert capture.has_face
                vec = capture.embedding.flatten().tolist()
                refs = [
                    {"vec": list(vec), "day": "2026-01-01", "quality": 100.0,
                     "yaw": 0.0, "exposure": 120.0, "created_at": "2026-01-01T00:00:00+00:00"}
                    for _ in range(5)
                ]
                session.add(FaceEnrollment(
                    student_id=student.id, embeddings=refs, centroid=list(vec),
                    ref_count=5, ref_days=2, distinct_days=["2026-01-01", "2026-01-02"],
                ))
                await session.commit()
                student_id = student.id

            # Match: verified, no classmate cross-comparison, no 403.
            res = await client.post(
                "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
                content=raw, headers=headers,
            )
            assert res.status_code == 200, res.text
            assert res.json()["face_verified"] is True
            assert "1:1 FACE VERIFIED" in res.json()["face_diagnostic"]

            # Mismatch: remark-grade hold, never a hard 403.
            orthogonal = [0.0] * len(vec)
            orthogonal[0], orthogonal[1] = -vec[1], vec[0]
            norm = float(np.linalg.norm(orthogonal))
            orthogonal = [x / norm for x in orthogonal]
            async with factory() as session:
                row = (await session.execute(
                    select(FaceEnrollment).where(FaceEnrollment.student_id == student_id)
                )).scalars().first()
                row.embeddings = [
                    {"vec": list(orthogonal), "day": "2026-01-01", "quality": 100.0,
                     "yaw": 0.0, "exposure": 120.0, "created_at": "2026-01-01T00:00:00+00:00"}
                    for _ in range(5)
                ]
                await session.commit()

            res2 = await client.post(
                "/api/v1/attendance/sessions/AUTO-101/submit-neighbors",
                content=raw, headers=headers,
            )
            assert res2.status_code == 200, res2.text
            assert res2.json()["face_verified"] is False
            assert "IDENTITY MISMATCH" in res2.json()["face_diagnostic"]

            async with factory() as session:
                rec = (await session.execute(
                    select(AttendanceRecord).where(AttendanceRecord.student_id == student_id)
                )).scalars().first()
                assert rec is not None
                assert rec.status == "IDENTITY_MISMATCH_REVIEW"
    finally:
        app.dependency_overrides.pop(get_db, None)
        await engine.dispose()
