# Google Drive connection — Smart Prayer Attendance Android v2.1

## What has actually been implemented

- The **Settings → Google Drive connection** section invokes Google Identity Services AuthorizationClient.
- The app requests the narrow `drive.file` scope; you choose a Google account on Google's consent screen.
- It uploads a **test TXT file** to your Google Drive root through Drive API v3 and saves only the test file ID and time in local preferences.
- Settings shows the APK's actual package name and signing certificate SHA-1, with a Copy button.
- No Gmail password, OAuth access token, fingerprint template, or student information is uploaded/stored by this diagnostic feature.

**NOT implemented**: live multi-phone synchronization, actual student/attendance/biometric backups, automatic upload after each scan, scheduled backups, restorations, cross-device staff auth. Do not mistake a successful connection test for these features.

## Google Cloud Console setup

1. On Android, install the new v2.1 debug APK and open Settings. Copy the APK SHA-1. This is the certificate of the actual installed APK.
2. Visit https://console.cloud.google.com/ and create a Google Cloud project named `Smart Prayer Attendance`.
3. Enable **Google Drive API** in APIs & Services → Library.
4. In Google Auth Platform, configure consent/branding, choose the right audience (External if personal Gmail), and add your own Gmail address as a test user while in Testing mode. Supply any URLs Google requires if relevant.
5. Create an **OAuth client ID → Android**. Set:
   - Package name: `lk.skilltech.prayer`
   - SHA-1: paste the exact SHA-1 displayed in the installed app.
6. On your Android phone, open **Settings → Connect Google Drive & upload test**. Select your Gmail in Google's consent screen; approve file creation.
7. Find `SmartPrayer_Connection_Test_...txt` in Google Drive. It contains no personal data.

You do not need a Google API key or to paste Gmail passwords into the app.

## Signing certificate — important for GitHub Actions

The GitHub-hosted runner typically creates a **new debug signing certificate per workflow run** unless you configure a stable keystore. Therefore:
- This project's GitHub workflow now caches a development-only debug keystore to keep the certificate consistent across successful builds while that cache remains available. Cache eviction can still change the SHA-1 and the key must NOT be used for production.
- The printed SHA-1 can change between APKs if the cache expires; OAuth authorization may stop working after rebuilding.
- Installing a new APK with a different signing certificate also prevents updating over your previous APK. **Export your student records before uninstalling.**
- For a deployable app, generate a stable release keystore, store it as GitHub Actions encrypted secrets, update the signing config and create an OAuth client for its SHA-1.
- Never publish a real signing keystore or passwords in your Git repository.

## Troubleshooting

- `DEVELOPER_ERROR` / error 10: verify Google Cloud Android OAuth package name + exact installed APK SHA-1.
- `403 Drive API has not been used`: enable Google Drive API in the same Cloud project as your OAuth client and allow propagation.
- `Access blocked`: configure the OAuth consent screen and add the account as a test user if the app is in Testing mode.
- If consent succeeds but the upload fails, the app shows the Drive API error. Retry after resolving it.
- The app needs Google Play services; some Android devices without them cannot use this integration.

## References
- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://developers.google.com/workspace/drive/api/guides/manage-uploads
- https://developers.google.com/android/guides/client-auth
