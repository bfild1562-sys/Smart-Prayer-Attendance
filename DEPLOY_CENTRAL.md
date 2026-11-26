# Deploy the integrated central attendance server

This is the part that allows **all staff phones** to view a shared roster and attendance records, without connecting their personal Gmail accounts.

## Requirements
- An internet-reachable VPS or hosting machine with Docker Compose, persistent disk, DNS domain and HTTPS port 443.
- Your own Google Cloud project, with Google Drive API enabled and an **OAuth WEB application client** (separate from the Android OAuth client already created).

## First install

1. Create a DNS A record pointing your chosen domain to the host's public IP.
2. Copy `cloud-server/.env.example` to `cloud-server/.env` on the server; set `SPA_DOMAIN` and OAuth values. Generate `SPA_TOKEN_KEY` (Fernet, base64) and keep it stable, private and backed up. Never commit `.env` to GitHub.
3. In Google Cloud **Google Auth Platform → Clients**, create an OAuth client **Web application**. Authorized redirect URI must be exactly `https://YOUR_DOMAIN/oauth/drive/callback`. Add the institution's Gmail account to Test users when OAuth publishing status is Testing.
4. From `cloud-server/` execute `docker compose up -d --build`. Verify `https://YOUR_DOMAIN/health` returns `{"ok":true,"data":{"status":"ok",...}}`.
5. Create the institution's admin account once:

```
docker compose exec app python server.py init-admin --username institutionadmin
```

This prompts securely for the password. Do not share it with staff.

6. In Android app → **Cloud**, connect to `https://YOUR_DOMAIN` and log in using the central admin account. Create invitation codes for staff. Create an ESP32 device credential on the same page, save it privately.
7. In Android Cloud, select **Connect institution Google Drive**. Complete Google consent in Chrome. The server stores a protected refresh token; no staff Gmail authorization is required. The server performs encrypted daily backups once connected.

## Backup encryption and restoration

The server uses `SPA_TOKEN_KEY` both to encrypt the OAuth refresh token at rest and to encrypt exported backup data with Fernet (AES-128-CBC/HMAC, not AES-256-GCM). Keep this key outside the repository. A backup file is NOT readable without this key. To decrypt a downloaded backup to JSON:

```
python tools/decrypt_central_backup.py SmartPrayer_YYYY-MM-DD_encrypted.spabackup decoded.json
```

**Caution:** Restoring a full database automatically is not implemented. The decrypted JSON enables recovery with a controlled migration. Always preserve the original server SQLite volume and verify your backups.

## API overview

- `POST /api/v1/login`, `POST /api/v1/register`, `POST /api/v1/invitations` — central staff accounts
- `POST /api/v1/sync/batch`, `GET /api/v1/sync/snapshot` — phone synchronization
- `POST /api/v1/devices` (admin), `GET /api/v1/devices` — register readers
- `GET /api/v1/device/config`, `POST /api/v1/device/scans` — device key authentication
- `GET /api/v1/admin/drive/status`, `POST /api/v1/admin/drive/start`, `POST /api/v1/admin/drive/backup` — Google backups

The device sends `Authorization: Device <device-key>`; staff use `Authorization: Bearer <session-token>`. Avoid logging these headers.

## Operational notes

- Use a managed domain with valid CA certificates; do not disable HTTPS certificate verification.
- The SQLite database lives in Docker volume `central_data`, not in the APK or Google Drive.
- Google Drive backups are independent encrypted snapshots, distinct from the central transactional database.
- Event IDs make retransmission safe if the SD card sync was interrupted.
- The ESP32's DS3231 RTC should store UTC. The app and device use Asia/Colombo for display and window decisions.
- When schedules overlap, ambiguous automatic marks are rejected until a session is selected/unambiguous.
