# Pocketlog — Android MVP

An offline-first Android expense tracker that turns transaction alerts into reviewable expenses. Alerts can be shared from Gmail, or the app can manually search a selected date range for BCA, Livin’ by Mandiri, Bank Jago, and UOB emails after you grant Google read-only Gmail access. Parsed transactions always wait for your review.

## Included

- Monthly spend dashboard and recent activity
- Review, edit, confirm, and delete flows
- IDR display and basic merchant-based category suggestions
- Bank hints from sender domains, including BCA, Livin’ by Mandiri, Bank Jago, and UOB
- Parsing of supplied bank templates for BCA, Mandiri, Jago, and UOB
- Transaction dates from the message body; transfer alerts labeled separately from expenses
- Android Share target for transaction email text from Gmail
- Gmail sync with a selectable date range and supported bank sender filters; long scans continue in the background with a cancelable notification
- Local parsing; no email bodies or OAuth tokens are uploaded to a Pocketlog server
- Local persistence with app-private SharedPreferences
- Versioned JSON backup and restore, including Gmail scan history; restored data merges without replacing current transactions
- CSV export for spreadsheet analysis (CSV cannot be restored)
- Empty states until real transactions are imported or confirmed

## Run in Android Studio

### Version 1.1.0

The Home screen now offers **Import bank emails**, Settings scrolls on smaller screens, and the Gmail date range is shown first. The app displays its installed version on Home and in Settings.

Builds install as **Pocketlog** (`com.expensemail.app`); the package name remains stable so existing Firebase and OAuth configuration can continue to work. Older builds may use a different signing key and cannot be updated in place. Export a backup before replacing an installation signed with another key or uninstalling, because uninstalling deletes local data. Firebase beta builds use a stable private signing key for future updates.

Open this folder in Android Studio with JDK 17 and Android SDK platform 35 installed. Let Gradle sync, then run the `app` configuration on a device or emulator. The Gradle wrapper configuration is included; Android Studio or Gradle downloads the required distribution on first sync.

## Import a Gmail alert

Open a bank transaction alert in Gmail and choose **Share → Pocketlog**. If Gmail only offers a link, copy the alert text and paste it into the Review screen. Only the text explicitly shared to the app is parsed. The original email is not saved. If sender details aren't included, enter the sender email or domain in the optional field.

## Gmail access and setup

Gmail sync uses Google's `gmail.readonly` scope. The app's sync button searches only the selected date range and the supported bank sender domains, then parses email content on-device. Long scans continue while you use another app; the ongoing notification shows status and lets you cancel. Android may still stop work after a force-stop, shutdown, or system time limit; completed batches are saved so you can resume when you return. Android 13 and newer require notification permission for the background scan. Google consent for this scope applies to Gmail messages broadly, even though the app's query is limited. Email bodies and OAuth tokens are not uploaded to a Pocketlog server. Imported message IDs are stored locally to prevent duplicates; disconnecting revokes Google's grant and clears that import history.

In Settings, **Export restorable backup (JSON)** saves transactions and Gmail scan history. **Restore from backup** previews the merge first; it skips duplicates, preserves current-device records and dismissal choices, and does not resurrect dismissed alerts. **Export transactions as CSV** is for spreadsheets and does not contain the full scan history, so it cannot be used to restore the app.

The `backup-api/` folder contains the encrypted Railway service source and its checks. The Android app does not yet sign in to Firebase Auth or upload backups to that service; local JSON export and restore are available now. Railway resources and runtime secrets have not been created or deployed.

Before sync can work, configure a Google Cloud project: enable the Gmail API, configure the OAuth consent screen with the `gmail.readonly` scope, add your Google account as a test user while the app is in Testing, and register an Android OAuth client for package `com.expensemail.app` using the SHA-1 signing certificate of the installed build. Public distribution needs Google's verification for the restricted scope. Register the Firebase beta signing certificate separately from any Android Studio debug certificate; do not publish private keystore files or credentials in the repository.

If you prefer not to grant Gmail access, the Share flow remains available and only receives the specific message you share.

## Parser limits

Bank email templates vary. The MVP recognizes the labeled amount, date, and recipient patterns implemented for BCA, Livin’ by Mandiri, Jago, and UOB. It handles Indonesian thousands/decimal separators and suggests a bank from the sender domain. A domain is only a hint, not proof that an email is genuine; review every imported transaction. If no transaction date is found, the import date is used. Transfer alerts are excluded from expense totals unless you change their type in Edit.

## Beta builds through Firebase App Distribution

The GitHub Actions workflow runs Android tests, lint, backup API tests, and a debug APK build for branch pushes and pull requests. Only `main` builds the signed release and distributes it to the Firebase App Distribution group `mvp-testers` when `FIREBASE_BETA_ENABLED` is `true`. Testers receive an invitation and new-build notification from Firebase, then install from its tester page. The APK remains available as a GitHub Actions artifact.

The Firebase path needs repository variable `FIREBASE_APP_ID` and Actions secrets `FIREBASE_SERVICE_ACCOUNT_JSON`, `BETA_KEYSTORE_BASE64`, `BETA_STORE_PASSWORD`, `BETA_KEY_ALIAS`, and `BETA_KEY_PASSWORD`. Use a dedicated service account with the Firebase App Distribution Admin role. Keep the original keystore and passwords backed up securely: changing the signing certificate prevents in-place updates. The workflow sets a fresh, increasing version code for each run.

Until `FIREBASE_BETA_ENABLED` is set, the workflow continues to publish a debug APK artifact. A Firebase beta APK signed with the new key cannot update an existing APK signed with a different debug key. **Export a JSON backup before uninstalling an existing installation.** Uninstalling deletes local data. Create a second Android Gmail OAuth client for the beta key's SHA-1 before using Gmail sign-in on beta builds; retain the old client for existing installs.
