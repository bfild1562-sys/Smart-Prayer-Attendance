# Smart Prayer Attendance — Integrated v4 Source

A real Android app + Python central service + ESP32/R503Pro firmware codebase. This replaces the previous sequence of small demonstration releases. The chosen mosque illustration is included as the Android launcher icon and on the login screen.

**Android package:** `lk.skilltech.prayer`. **Timezone:** Asia/Colombo. **Capacity:** 300 students / two fingerprint slots per student.

## Included features

**Android (local/offline):** Staff login and one-use invitations, student records, fingerprint number assignment (1–300), persistent schedules for Fajr/Dhuhr/Asr/Maghrib/Isha, linked minute shifts, individual RED MARK allowances, countdown, special events, manual attendance corrections and audit logs, filters and CSV export, monthly summary, on-device SQLite persistence.

**Central server (when deployed):** Shared staff accounts/invitations, authenticated attendance sync, cloud roster/configuration, conflict checking and idempotent event ingestion from an ESP32, device credentials, scan journal, prayer deadline enforcement, admin-controlled Google Drive authorization and AES-encrypted daily backups. No per-staff Gmail connection. Sync is triggered on phone edits and periodically while the app is open and logged in to central service.

**ESP32 source:** R503Pro native AutoEnroll 6+6 and AutoIdentify (adapted from the NodeMCU prototype), local device setup page, RGB LED, passive buzzer, ILI9341 TFT, DS3231 UTC clock, 8GB SD journal, cached configuration and Wi-Fi/TLS background upload. Sensor model protocol and electrical pinout must still be checked on physical hardware. The ESP32 programming libraries and firmware have **not been hardware-compiled** in this environment.

## Important limitations (not a UI demo, but not a finished deployment)

- A centralized system requires a deployed HTTPS server and Google Cloud **Web OAuth** client. The previously created Android OAuth client is not sufficient for server-side backup authorization.
- The Android source is complete for building, but **no APK was compiled in this environment** (Android SDK unavailable). Your existing GitHub workflow can create the debug APK from the source.
- Device firmware must be compiled and validated on the real ESP32, SD/TFT and R503Pro before school use. It is intentionally separate from the Android app.
- Fingerprint template export/restore to SD/Drive is **not yet implemented**. The R503Pro keeps its enrolled templates internally. Backups presently cover attendance and configuration, not biometric templates.
- The Android app has an existing on-device staff login in addition to the central staff login. For production across multiple staff phones, require central sign-in and harden enrollment/role provisioning before live use.
- Synchronization currently reads a recent server snapshot (last 500 attendance records), rather than fully paginating several months to every phone. The central server and SD journal hold the longer history.
- Scheduled Google Drive backup runs on the server's hourly maintenance cycle once an admin authorizes Drive. It does not require each staff member to upload anything.
- Android signing for production should use one stable release key rather than a rotating debug key.

## Repository structure

```
.github/workflows/build-android.yml   GitHub APK build
android-client/                      Android Java WebView + local app UI + SQLite
cloud-server/                        Python HTTPS API + device intake + backups
esp32-firmware/SmartPrayerESP32/      ESP32 Arduino sketch + configuration
```

## Recommended one-time setup

1. **Update GitHub with this complete ZIP**, keeping the directories at the repository root. Don't mix snippets from old versions. Run `Build Smart Prayer Android APK`. Install the resulting APK **over** your existing app; do not uninstall first because it holds local SQLite data.
2. Deploy the `cloud-server` using a real HTTPS domain and persistent disk. See `DEPLOY_CENTRAL.md`. Create an administrator on the server; local phone login is not the central account.
3. In the app **Cloud** tab, enter the HTTPS server URL, then sign in using the central staff account. Changes synchronize while the app is open; the server stores the central records.
4. As central admin, create an **ESP32 device key** on the Cloud tab. Configure the ESP32 sketch with that key, your Wi-Fi settings and the server TLS root certificate. Keep credentials out of GitHub.
5. Assign a numeric fingerprint number (1–300) to each student. Student N owns R503Pro template slots `2N-1` and `2N`. The device local enrollment page can perform two native six-scan enrollments.
6. Configure prayer start, normal deadline, end and late allowance. Device uses its SD-cached settings when offline. When Wi-Fi returns, the SD queue synchronizes in batches to the server.
7. The central admin connects institutional Google Drive once using the Web OAuth flow on the Cloud page (after configuring OAuth on the server).

Detailed deployment: `DEPLOY_CENTRAL.md`. Device details: `esp32-firmware/SmartPrayerESP32/README.md`.

## Data correctness

Each fingerprint event receives an immutable event ID. The ESP32 attempts to append it to SD before acknowledging locally, and later uploads via HTTPS. The server rejects unmapped or closed scans, records each received event, and never downgrades a previously marked PRESENT student to RED MARK. Manual changes preserve staff identity and correction history. Local SD failures must be treated as errors, not successful attendance.

## Safety and privacy

Do not commit passwords, device keys, OAuth secrets or any real fingerprint templates to GitHub. Keep the source repository private, limit staff permissions, and obtain school/guardian authorization for student biometric processing. Keep an alternative manual attendance method available.
