# Smart Prayer Attendance for Android
Open the supplied APK on Android 8.0 or newer. The app's local register uses an app-private SQLite database. Install version 1.1 over version 1.0 using Android's Update button. The first staff member signs up with a username, password (8+ characters) and confirmation. If a password already protects your version 1.0 register, enter that same password twice to claim the administrator account. The upgrade keeps the students table intact.

Additional staff sign up on the same phone. The administrator approves them under Staff activity. Login usernames ignore case. Remember me keeps the staff account signed in across launches until logout. On shared phones use Log out when handing over the device. The latest 100 roster edits and additions show the responsible username, admission number and timestamp. Earlier edits have no recorded actor. Attendance marking and its correction history are future features. Accounts and activity are local to this device.

Export CSV before clearing data, uninstalling or moving phones. Local password recovery is unavailable. The CSV contains readable student records. Staff accounts and audit records are not included in roster CSV.

Institution workspace opens the institution's HTTPS URL in the Android browser. It uses the previously supplied FastAPI system, including its own login, verification and account permissions. Supply the URL of a running hosted installation. The phone's local list is independent. To transfer students use CSV export/import. Fingerprint scanning and prayer deadlines are outside this Phase 1 client.

## Build
Open this folder in Android Studio with SDK Platform 35, Build Tools 35 and Gradle 8.9 / Android Gradle Plugin 8.7.3, or use an installed Gradle 8.9 with JDK 17 and run gradle assembleDebug. The supplied APK was built with AAPT2, javac, D8, zipalign and apksigner from Android tools and signed with a development certificate. For institution-wide deployment make and retain your own release signing key before rollout. Moving to a differently signed build requires exporting the roster before uninstall and importing it after installation.
