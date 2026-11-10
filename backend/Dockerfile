# R10 (review §1.8): two-stage build — the build toolchain (build-essential)
# never ships in the runtime image. Wheels are built once against the same
# base python and installed into a prefix copied into the runtime layer.
FROM python:3.11-slim AS builder

WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# Runtime stage: python + OpenCV's native runtime libraries + curl for the
# healthcheck. Nothing in this layer can compile or execute arbitrary builds.
FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    libglib2.0-0 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /install /usr/local

COPY . .

# R10 (review §1.8): never serve as root. The app binds an unprivileged port
# and needs no system writes; only /app belongs to the runtime user.
RUN useradd --create-home --shell /usr/sbin/nologin nonroot \
    && chown -R nonroot:nonroot /app
USER nonroot

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# R10 (review §1.8): behind a proxy, uvicorn must honor X-Forwarded-* or every
# request's client IP reads as the proxy address — which collapses any
# IP-keyed rate limiting (chunk 1's per-gateway floor) into a single global
# budget. "*" is correct for the container boundary: only the internal
# network can reach the app.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
