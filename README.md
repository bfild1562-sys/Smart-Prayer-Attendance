# Smart Prayer Attendance v3.0 (central sync preview)

This updates the working offline Android v2.2 app with a **real, separately hosted central database** and an optional **Cloud** screen. Existing Google Drive test uploads and manual encrypted backups remain available from Settings.

Start with [CENTRAL_SETUP.md](CENTRAL_SETUP.md). New central server files are in [cloud-server/](cloud-server/). No Gmail password or Google OAuth token is bundled in this source.

**Limitations:** Manual upload only; central login currently separate from local phone login; Google Drive access remains on the phone until the administrator's central OAuth flow is implemented. No ESP32 integration in this release.
