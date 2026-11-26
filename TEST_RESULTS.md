# Smart Prayer Attendance verification status

## Android encrypted backup and restore

- Implemented restore for the existing `SPABK001` encrypted phone-backup format.
- AES-GCM authentication is verified before the backup JSON is parsed or any database write begins.
- Restore uses one SQLite transaction; invalid files, wrong recovery passwords, parsing errors or database errors roll back the restore.
- Existing phone records are preserved. Missing students, events, attendance and audit records are merged in; configured prayer schedules are not overwritten.
- Students are matched by ID first, then case-insensitive admission number, so restored attendance can be attached to the matching local student.
- Historical staff names can be represented as archived, non-login identities for audit display.
- Staff passwords, invitation codes and fingerprint templates/slot assignments are not restored.
- The app uses a 64 MB encrypted-file limit and a 512 MB decompressed input limit.
- The Android database migrates from schema version 4 to version 5 by adding an archived staff identity flag.

## Automated CI results

- Android migration/base build, workflow run #23: **success**. Permanent APK signing and signature verification passed.
- Encrypted backup restore PR build, workflow run #26: **success**. JavaScript syntax check, Android Gradle build, APK signature verification and artifact upload passed.
- Merged main build, workflow run #27: **success**. Artifact: `SmartPrayer-Android-APK-v4.2.0-stable-signature`.
- The v4.2.0 APK signature certificate SHA-1 is `EA:36:F9:FF:7A:65:33:B3:C9:E6:ED:68:A3:99:64:AB:BA:14:BD:39`, matching the permanent migration signing key.
- Railway production deployment after the merge: **SUCCESS**.

## Not yet physically verified

- Creating a real encrypted backup from a physical Android phone and uploading it to Google Drive.
- Selecting that actual `.spab` file through Android's document picker and restoring it with the correct recovery password.
- Confirming the restored student, attendance and audit counts against the source phone.
- Testing wrong-password, damaged-file and oversized-file errors on a physical phone.
- R503Pro/ESP32 fingerprint-template backup or restore.

Do not treat a successful CI build as a physical-device acceptance test. Perform the first restore with a test backup and non-production/test data, and verify the result before relying on it for production records.

## Remaining backup roadmap

Backups are still manual. Scheduled automatic phone backup, automatic Google Drive retention, fingerprint-template backup and a user-facing list of available Drive backups remain future work. Phone-local backups are separate from the institution server's database and its separate Google Drive backup.
