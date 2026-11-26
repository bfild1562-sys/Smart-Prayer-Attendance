# Smart Prayer Attendance verification notes

## Android encrypted backup and restore work

- Implemented a restore path for the existing `SPABK001` encrypted phone-backup format.
- Restore authenticates the AES-GCM tag before parsing the JSON or changing the database.
- Restore uses a single SQLite transaction; errors roll back imported rows.
- Existing students, configured schedules, events and attendance rows are preserved. Missing records are merged in.
- Student attendance is remapped by student ID, or by case-insensitive admission number where IDs differ.
- Historical staff identities can be kept as archived, non-login rows so old activity and attendance audit records retain staff names.
- Staff passwords, invitation codes, fingerprint templates and fingerprint slot assignments are not restored.
- Added a database migration for archived staff identities and an administrator-only Android document-picker restore flow.
- Backup/restore size limits: 64 MB encrypted file and 512 MB decompressed JSON.

## Automated checks

- Android GitHub Actions build before this restore change: **run #23 succeeded**, with APK signature verification using the permanent project signing certificate.
- A new pull-request workflow check includes `node --check android-client/assets/app.js` and a full Android Gradle build. **The restore branch check has not completed yet.**
- Previous repository checks documented in earlier results: Python central-server tests and JavaScript syntax check passed before the current restore changes. They do not prove the new restore code works on a phone.

## Not yet physically verified

- Creating a new encrypted backup on a physical Android phone and uploading it to Google Drive.
- Selecting that real `.spab` file through Android's document picker and restoring it with the correct password.
- Confirming counts and audit names against the source phone after restore.
- Wrong-password and damaged-file behavior on a physical phone.
- ESP32/R503Pro fingerprint-template backup or restore.

Do not treat this as a completed physical-device acceptance test until those checks have been performed with a test backup. Use a spare/test device or non-production test data for the first restore trial.
