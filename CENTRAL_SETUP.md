# Smart Prayer Attendance v3.0 — central database preview

## Status and limitations

This release adds a **working shared server**, **centrally issued staff invitation codes**, remote username/password authentication, manual upload of local attendance/roster/schedules/events, and **viewing shared attendance** inside the Android app. Existing v2.2 offline features and Google Drive backup are retained.

This release is **not** yet automatic, bidirectional synchronization, and it does **not** replace phone-local logins with shared logins. New local records stay in the phone SQLite database until an authorized user taps **Upload local data**. Conflict detection rejects differing records instead of choosing a winner. Do not treat it as a finished multi-phone school deployment.

The Google Drive account you connected in v2.1/v2.2 is still authorized **per phone**. An administrator-managed **central Google Drive connector** remains a later phase. Never paste Gmail credentials into the project.

## Files

- `android-client/`: upgraded Android app, package `lk.skilltech.prayer`, version code 6, original local DB unchanged.
- `cloud-server/server.py`: Python standard-library server with SQLite WAL data store, password-hashed staff accounts, one-time staff invitations, bearer sessions, idempotent uploads, manual corrections, audit entries, and shared summary API.
- `cloud-server/tests/`: server tests.
- `cloud-server/compose.yaml`, `Dockerfile`, `Caddyfile`: example HTTPS deployment with persistent volumes.
- `.github/workflows/build-android.yml`: original known-good APK build workflow (Android SDK action v4).

## What to do on GitHub

1. **Before updating:** In the installed Android app export student CSV and attendance CSV, and create the v2.2 encrypted Google Drive backup. Keep your recovery password.
2. Extract the v3 **GitHub update-only** ZIP. Upload its contents (not the ZIP itself) into the **existing repository root**. Replace files with the same names. Keep existing `.github` and unchanged Android files. Do not delete `android-client/gradle.properties` or your existing `README` until you have reviewed them.
3. GitHub **Actions > Build Smart Prayer Android APK**. Download the artifact if the run is green.
4. Install the debug APK as an update. **Do not uninstall the older app.** If Android reports a signing mismatch, stop; uninstalling would destroy its local database.
5. The Cloud tab is now visible, but it will not connect until you deploy the shared server with an HTTPS URL.

## Running the central server on a computer (test only)

Requires Python 3.10+. In the `cloud-server` directory:

```shell
python server.py init-admin --username schooladmin
python server.py serve
```

You will be prompted for a new central administrator password. It is **separate** from the Android local password and from the Gmail password. The server binds `127.0.0.1:8080` for a local test. Open `http://127.0.0.1:8080/health` from that computer to verify `status: ok`. The phone does **not** connect to this local-only HTTP endpoint. Do not expose bare HTTP to the internet.

## HTTPS deployment example (VPS with a domain)

Use a server that can run Docker Compose and has **persistent disk storage**. Point an owned domain's DNS A/AAAA record to the server and allow ports 80 and 443. The Docker Compose configuration includes Caddy, which manages HTTPS certificates.

From `cloud-server` on the server:

```shell
cp .env.example .env
# Edit .env to set SPA_DOMAIN to your real hostname
# e.g. attendance.yourschool.org

docker compose build
docker compose run --rm -it app python server.py init-admin --username schooladmin
docker compose up -d
```

The admin password must be securely recorded. Ensure the `central_data` Docker volume is included in server backups. Never delete it during redeployment. Public deployment should be reviewed for additional hardening, monitoring, firewall rules, abuse protection and backups before student use.

**Do not use a hosting plan with ephemeral storage for the SQLite database.** Otherwise data may disappear when the instance restarts.

## Staff workflow

1. Android local admin logs into the phone (unchanged).
2. Open the new **Cloud** tab and enter `https://attendance.yourschool.org`, central administrator username and password.
3. Once connected, tap **Generate central staff invitation**; copy the one-time code.
4. Another staff member installs the app and opens a local account (the current offline app still requires local login). In Cloud, they choose **Register central account** with the same HTTPS URL and the one-time code.
5. Staff log into Cloud with their own central username/password; their password is never stored in the APK source.
6. Central admin chooses **Upload local data** to sync the roster, schedules, events and attendance. Central staff can upload attendance only. **Conflict = stop and review**, not overwrite.
7. Any logged-in central staff member taps **Refresh shared records** to see central counts and recent attendance.

Security: Central session tokens are kept in app memory, not saved to disk. The server validates role on every operation. Central staff signup codes are random, hashed and valid once for seven days. The central backend rejects requests without a valid session. Uploads contain student and attendance data, **not fingerprint templates** or local staff passwords. Phone-local Google Drive test/backup remains separate.

## Next engineering phases

1. Server-side audit/conflict resolution UI and two-way, per-record sync with durable cursors.
2. Replace parallel phone-local staff accounts with central sign-in and offline cached permissions.
3. Configure central admin-only Google OAuth and scheduled institutional Drive backups.
4. ESP32/SD-card upload API, fingerprint-template export/restore and integrated live attendance.

## API endpoints

- `GET /health` — unauthenticated health.
- `POST /api/v1/login` — central username/password, returns short-lived session token.
- `POST /api/v1/register` — one-time invitation registration.
- `GET /api/v1/me` — current central identity.
- `POST /api/v1/invitations` — admin issues one-use code.
- `POST /api/v1/sync/batch` — role-checked upload, strict conflict handling, max 250 items per collection.
- `GET /api/v1/summary` — shared totals, prayer schedules and recent attendance.
- `GET /api/v1/students` — shared roster.
- `POST /api/v1/attendance/correct` — reason-required central attendance correction and audit.

## Testing

```shell
cd cloud-server
python -m unittest discover -s tests -v
```

The tests cover one-time invitations, role restrictions, idempotency, conflict rejection and DB transaction rollback. An APK build and physical two-phone synchronization have **not** been completed here.
