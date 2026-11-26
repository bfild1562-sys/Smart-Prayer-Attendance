# v4 integrated release notes

## Visible changes
- The user-approved white/gold mosque on teal/night-blue artwork is now the launcher icon and Android login branding.
- The app retains real local prayer times, RED MARK windows, special programs, manual corrections, staff invitations and reports; the regular manual-encrypted-backup panel was removed.
- Student profile editor now assigns the sensor's 1–300 fingerprint number (slots 2N-1 and 2N).
- Central Cloud page adds institution Google Drive authorization and ESP32 device credentials.

## Code changes
- Multi-staff central Python server accepts authenticated ESP32 scan events, computes attendance status by server schedule, prevents duplicate effective records and stores scan events.
- ESP32 source adds SD-first journaling, TLS upload queues, local schedule cache, RTC, TFT and three note patterns on top of the R503Pro 6+6 / AutoIdentify prototype.
- Android central session refreshes shared data and synchronizes edits while signed in and foregrounded.
- Server can perform daily encrypted Google Drive snapshots after one admin's OAuth authorization.

## Still needs physical/external setup
- Hosting with domain and HTTPS; Google Cloud Web OAuth credentials; compiling and flashing ESP32 firmware; wiring TFT/RTC/SD/buzzer and provisioning Wi-Fi/device credentials; verifying R503Pro native commands on your actual module.
- Complete fingerprint **template** export/restore, exhaustive multi-month phone-side pagination, background sync with phone app closed, conflict-resolution UI and production staff provisioning still require additional development. Do not claim them completed.
