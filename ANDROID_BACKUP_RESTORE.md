# Android encrypted backup and restore

## What this feature does

The Android app can create a manual encrypted phone snapshot and restore a selected `.spab` file through the Android document picker. The snapshot belongs to the phone's local SQLite database. It is separate from the Railway central-server database and its institution-level backup.

The restore operation is a **merge**, not a replacement:

- It never deletes records from the phone.
- It never overwrites a student, existing attendance entry, event or configured prayer schedule on the phone.
- It adds records that are missing.
- It uses one SQLite transaction. A wrong password, damaged file, unsupported format or database error causes the transaction to roll back.
- The restoration is written to staff activity using the staff account that performed it.

## Create a backup

1. Sign in to the Android app as an administrator.
2. Open **Settings** and find **Phone Google Drive backup**.
3. If Drive has not been authorized on this app yet, use **Connect Google Drive & upload test** and complete Google's consent screen.
4. Enter a recovery password of 12–128 characters, confirm it, and press **Encrypt & upload backup**.
5. Keep that exact password somewhere safe and separate from the phone. It is not saved by the app and cannot be recovered by the developer.

The uploaded file is named similar to `SmartPrayer_Encrypted_Backup_<timestamp>.spab`.

## Restore a backup

1. Sign in as an administrator on the phone where the records should be restored.
2. Open **Settings** and find **Restore from encrypted backup**.
3. Enter the exact recovery password used when the backup was created, then confirm it.
4. Press **Choose backup file & restore**.
5. In the Android document picker, open Google Drive (or the location where the `.spab` file was saved) and select the backup.
6. Wait for the completion or error message. Do not close the app while the restore is running.

The recovery file is decrypted and authenticated before database changes begin. No need to unzip or rename the file.

## Merge rules

| Data | Restore behavior |
|---|---|
| Students | Adds missing students. It matches an existing student by stored ID first, then by admission number (case-insensitive). Existing student details are kept unchanged. |
| Prayer schedules | Fills a schedule only when the phone's schedule is still blank/default. A schedule already configured on the phone is preserved. |
| Special events | Adds events whose IDs do not already exist. Existing events are preserved. |
| Attendance | Adds missing entries. Existing entries for the same student, session and date are preserved. |
| Attendance audit | Appends historical audit rows with new local row IDs; original dates, reasons and actor IDs are retained. |
| Schedule audit | Appends historical schedule changes with new local row IDs. |
| Staff activity | Appends historical actions. Historical staff IDs are retained. |
| Staff directory | Creates archived, non-login identity rows only when needed so old audit entries can still show the staff member's name. |

When a backup student matches a student already on the phone by admission number but has a different ID, attendance from the backup is linked to the matching phone student.

## What is deliberately excluded

- Staff passwords, password hashes and active login credentials are not backed up or restored.
- Staff invitation codes are not backed up or restored.
- Google OAuth access tokens are not backed up or restored.
- Fingerprint templates are not part of this archive. Fingerprint slot numbers are not assigned to restored students, to avoid linking a student to a sensor slot that may contain somebody else's fingerprint. Students need fingerprint enrolment on the target sensor.
- The archive contains this phone's local data only. It does not export the Railway central-server database or data that exists only on another phone.

Archived staff identity rows are hidden from normal staff management and cannot sign in. They remain available to audit-history joins so old actions can still display the original staff name.

## Size and security limits

- Backup recovery password: 12–128 characters.
- Maximum encrypted backup size accepted by Android restore: 64 MB.
- Maximum decompressed JSON size: 512 MB.
- Encryption: PBKDF2-HMAC-SHA256 (210,000 iterations) and AES-256-GCM.
- The authentication tag is checked before parsing or importing records.
- Plaintext is streamed through the JSON parser and is not written as a plaintext backup file.

## Important limitations

Backups are still manual. This feature does not implement scheduled automatic phone backups, automatic monthly retention, fingerprint-template backup, or an in-app list of Drive files. The user selects the backup through Android's document picker. The existing central synchronization and the institution server's separate Drive backup remain separate systems.

A successful GitHub Actions Android build verifies that the source compiles and the APK is signed. A real phone test with a real `.spab` file is still required before relying on restore for important production records.
