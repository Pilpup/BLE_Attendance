# College Campus On-Premise Deployment & Self-Hosting Guide

This guide contains everything needed to transition the BLE Autonomous Attendance Verification System from cloud hosting (Render + Neon) to an **on-premise server and database hosted inside the college campus network**.

---

## 1. What You Need from the College IT Department

Before setting up the server, request the following details from your university/college IT administration:

| Item | What to Ask For | Why It Is Needed |
| :--- | :--- | :--- |
| **Server Machine** | A physical desktop PC, dedicated server, or VM (e.g. Ubuntu Server 22.04 or 24.04 LTS). Minimum specs: 4 CPU cores, 8GB RAM, 50GB SSD. | Runs the FastAPI application and PostgreSQL database. |
| **Static Internal IP** | A reserved static IP on the campus network (e.g., `10.20.1.50` or `192.168.1.100`). | Ensures the server address doesn't change every time it reboots. |
| **Firewall / Port Access** | Inbound access on **port 8000** (or **80/443** if using Nginx) from the student/faculty Wi-Fi subnet. | Allows student and teacher smartphones on campus Wi-Fi to submit check-ins and run audits. |
| **Subdomain (Optional)** | A campus DNS record (e.g., `attendance.yourcollege.edu` or `attendance.local`). | Lets users and the Android app connect via domain name instead of a numeric IP. |

> [!NOTE]
> **No Peer-to-Peer Wi-Fi Needed:**  
> University Wi-Fi networks typically enable "Client Isolation" (blocking phone-to-phone communication).  
> **Our system is immune to this:** peer discovery runs 100% via **Bluetooth Low Energy (BLE)** and **ultrasonic sound waves**. Phones only need outbound access to the college server IP.

---

## 2. Option A: 1-Command Turnkey Deployment via Docker Compose (Recommended)

Docker Compose bundles both the **PostgreSQL 16 Database** and the **FastAPI Backend** together with automatic database creation, schema migrations, and model weights mounting.

### Step 1: Install Docker & Docker Compose on the College Machine
On Ubuntu/Debian:
```bash
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER
# Log out and back in, or run: newgrp docker
```

### Step 2: Configure Environment Variables
In `backend/docker-compose.yml`, update passwords and secrets:
```yaml
POSTGRES_USER: college_admin
POSTGRES_PASSWORD: <ChooseStrongPassword>
POSTGRES_DB: college_attendance
...
DATABASE_URL: postgresql+asyncpg://college_admin:<ChooseStrongPassword>@db:5432/college_attendance
JWT_SECRET_KEY: <GenerateRandomHexKey>
FACULTY_SIGNUP_SECRET: <FacultySecretCode>
```
To generate secure 32-byte hex keys:
```bash
python3 -c "import secrets; print(secrets.token_hex(32))"
```

### Step 3: Start the Server & Database
Navigate to the `backend/` directory and run:
```bash
cd backend
docker compose up -d --build
```

### Step 4: Verify Deployment
Check that both containers are healthy:
```bash
docker compose ps
curl http://localhost:8000/health
```
Expected output:
```json
{"status":"healthy","environment":"production"}
```

Database tables (`users`, `courses`, `attendance_sessions`, `attendance_records`, `books`, etc.) are created automatically on startup by the app lifespan migration.

---

## 3. Option B: Native Host Deployment (Without Docker)

If the IT department prefers running directly on bare-metal Ubuntu/Debian:

### Step 1: Install PostgreSQL 16
```bash
sudo apt update
sudo apt install -y postgresql postgresql-contrib python3-pip python3-venv libglib2.0-0 libgomp1
```

### Step 2: Create the College Database
```bash
sudo -u postgres psql
```
Inside the `psql` console:
```sql
CREATE DATABASE college_attendance;
CREATE USER college_admin WITH ENCRYPTED PASSWORD 'StrongPasswordHere';
GRANT ALL PRIVILEGES ON DATABASE college_attendance TO college_admin;
ALTER DATABASE college_attendance OWNER TO college_admin;
\q
```

### Step 3: Set Up Python Virtual Environment
```bash
cd /opt/ble_attendance/backend
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
```

### Step 4: Configure `.env`
Create `/opt/ble_attendance/backend/.env`:
```ini
DATABASE_URL=postgresql+asyncpg://college_admin:StrongPasswordHere@localhost:5432/college_attendance
APP_ENV=production
API_V1_STR=/api/v1
JWT_SECRET_KEY=generate_with_python_secrets
JWT_ALGORITHM=HS256
ACCESS_TOKEN_EXPIRE_MINUTES=1440
FACULTY_SIGNUP_SECRET=faculty_passcode_2026
```

### Step 5: Create Systemd Background Service
Create `/etc/systemd/system/college-attendance.service`:
```ini
[Unit]
Description=College BLE Attendance Backend Service
After=network.target postgresql.service

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/ble_attendance/backend
ExecStart=/opt/ble_attendance/backend/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 4
Restart=always
RestartSec=5
EnvironmentFile=/opt/ble_attendance/backend/.env

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now college-attendance
sudo systemctl status college-attendance
```

---

## 4. Reverse Proxy & HTTPS via Nginx (Recommended for Production)

To expose the service on port 80/443 with a college domain name:

```bash
sudo apt install -y nginx
```

Create `/etc/nginx/sites-available/attendance`:
```nginx
server {
    listen 80;
    server_name attendance.college.edu 10.20.1.50;

    client_max_body_size 10M;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable configuration:
```bash
sudo ln -s /etc/nginx/sites-available/attendance /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

If the college provides an SSL certificate, add `listen 443 ssl;` and link `ssl_certificate` and `ssl_certificate_key`.

---

## 5. Connecting the Android App to the College Server

### Method 1: Set Default Base URL in Code (For College-Wide Distribution)
In [`core/network/src/main/java/com/college/core/network/ServerConfigManager.kt`](file:///home/wink/Projects/ble_attendance/core/network/src/main/java/com/college/core/network/ServerConfigManager.kt#L72):
```kotlin
// Replace with the college's internal IP or domain name:
const val DEFAULT_BASE_URL = "http://10.20.1.50:8000/"
// Or if Nginx / domain is used:
// const val DEFAULT_BASE_URL = "https://attendance.college.edu/"
```

Rebuild the release APK:
```bash
./gradlew assembleRelease
```
Every student and teacher who installs this APK will automatically connect to the college server without typing anything.

### Method 2: Dynamic Override in the App UI (For Testing)
Users can change the server at any time inside the app:
1. Tap the top-right **"ID / Server"** button on the Home or Attendance screen.
2. In the "Backend Server URL" field, enter the college server URL (`http://10.20.1.50:8000/`).
3. Tap **"Save & Connect"**. The app immediately verifies connectivity via `GET /health`.

---

## 6. Daily Operations & Database Backups

### Automated Daily Database Backup
Add a cron job on the college server to back up the PostgreSQL database every night at 2:00 AM:
```bash
sudo crontab -e
```
Add the line:
```cron
0 2 * * * docker exec college_attendance_db pg_dump -U college_admin college_attendance > /var/backups/attendance_$(date +\%F).sql
```
*(Or if running natively without Docker: `0 2 * * * pg_dump -U college_admin college_attendance > /var/backups/attendance_$(date +\%F).sql`)*

---

## 7. Data Privacy & Institutional Assurance

When speaking with the college dean or IT security office:
1. **100% On-Premise Data Residency:** All student attendance logs, enrollments, and roll numbers live inside the college server room. Zero data ever leaves the college intranet.
2. **Zero Permanent Biometric Storage:** Student selfie frames used for Layer 3 deduplication are held ephemerally in RAM and wiped as soon as the session ends. No student photos are stored in PostgreSQL.
3. **Bandwidth Friendly:** Each student check-in packet is under 15 KB (JSON + compressed preview frame). A lecture hall of 120 students generates under 2 MB total traffic per class session.
