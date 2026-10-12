# Pocketlog — Android MVP

Pocketlog is an offline-first Android expense tracker. It turns supported bank transaction emails into reviewable records, summarizes confirmed spending, and keeps a local copy of the user's data.

## Features

- Five-tab navigation: Coverage, Activity, Home, Spending, and Plan, with Home centered.
- Home dashboard with monthly totals, recent activity, Gmail scan status, and shortcuts to review alerts.
- Coverage for pending review items and skipped emails, including pagination, email evidence, recheck, add-to-review, and dismiss actions.
- Activity filters for date range and category, monthly spending summaries, and transaction history.
- Spending views for category breakdowns, recurring payments, and insights.
- Optional weekly or monthly spending targets; targets are guidance and do not block transactions.
- Review, edit, confirm, and delete flows. Transfers are tracked separately from expenses and excluded from expense totals.
- Local, rule-based category suggestions based on merchant text. Unknown merchants default to **Other**; review and correct a category before confirming. Categorization does not use an AI service.
- Bank hints from supported sender addresses: BCA, Livin’ by Mandiri, Bank Jago, UOB, BNI, and OCBC.
- Android Share target for bank email text and manual alert import.
- Gmail sync over the read-only `gmail.readonly` scope. Scans use a selected date range and supported sender filters; bounded batches can continue in the background with a cancelable notification.
- Encrypted cloud backup and restore through Firebase Authentication and the Railway backup API. Backup sign-in is separate from Gmail access.
- Versioned JSON export and merge-based restore, including local Gmail scan history; CSV export is for spreadsheet analysis and cannot restore app state.
- Local persistence in app-private storage. Email bodies and Gmail OAuth access tokens are not uploaded to the backup service.

## App structure

`ExpenseMailApp.kt` coordinates app state and navigation. Compose screens live in focused `*Screen.kt` files; `ExpenseViewModel.kt` owns screen-facing state and backup operations, while `ExpenseRepository.kt` handles local persistence and backup serialization. Gmail scanning, parsing, and synchronization remain in dedicated components.

## Run in Android Studio

Open this folder in Android Studio with JDK 17 and Android SDK platform 35 installed. Let Gradle sync, then run the `app` configuration on a device or emulator. The Gradle wrapper configuration is included; Android Studio or Gradle downloads the required distribution on first sync.

Builds install as **Pocketlog** (`com.expensemail.app`). Keep the package name stable for existing Firebase and OAuth configuration. APKs signed with different keys cannot update each other in place. Export a JSON backup before uninstalling an installation or replacing it with a build signed by another key; uninstalling deletes local app data. Firebase beta builds use a dedicated signing key so subsequent beta releases can update in place.

## Import bank emails

Open a supported bank transaction alert in Gmail and choose **Share → Pocketlog**. If Gmail only offers a link, copy the alert text and paste it into Review. Only text explicitly shared to the app is parsed; the original email is not saved. If sender details are missing, enter the sender address or domain in the optional field.

The parser recognizes structured transaction formats from BCA, Livin’ by Mandiri, Jago, UOB, BNI (`wondr@bni.co.id`), and OCBC (`notifikasi@ocbc.id`). Bank emails change over time, so inspect the parsed details before confirming a record.

## Gmail access and sync

Gmail sync requests Google's `gmail.readonly` scope. The app searches the selected date range and supported bank sender addresses, then parses messages on-device. Sync reports show fetched, added, skipped, and previously seen messages. Skipped alerts can be rechecked after parser improvements. Long scans run as a foreground service while you use another app; completed batches are saved so a later run can continue. Android may stop work after a force-stop, shutdown, or system time limit. Android 13 and newer may hide the foreground-service notice if notification permission is denied.

Google consent for `gmail.readonly` applies to Gmail messages broadly, even though the app's query is limited. Email bodies and Gmail OAuth access tokens are not uploaded to Pocketlog. Imported message IDs and sync history are stored locally to prevent duplicates. Disconnecting Gmail retains local transactions and scan history.

To configure Gmail sync, enable the Gmail API in Google Cloud, configure the OAuth consent screen with `gmail.readonly`, add your Google account as a test user while the app is in Testing, and register an Android OAuth client for `com.expensemail.app` using the signing certificate SHA-1 for the build you install. Public distribution requires Google's verification for the restricted scope. Register the Firebase beta signing certificate separately from a debug certificate; never commit private keystores or credentials.

If you prefer not to grant Gmail access, sharing a single alert or manually pasting its text remains available.

## Categories and expense totals

Merchant categories are local rule-based suggestions. Recognized examples include food and drink, transport, groceries, shopping, entertainment, subscriptions, health and personal care, and bills and utilities. A new or ambiguous merchant defaults to **Other** so the user can review it; the app does not learn merchant rules automatically. Confirmed transfers remain separate from expense totals unless edited to an expense.

## Local and cloud backups

In Settings, **Export restorable backup (JSON)** saves transactions and Gmail scan history. **Restore from backup** previews a merge before applying it; duplicates are skipped, existing device records and dismissal choices are preserved, and dismissed alerts are not resurrected. **Export transactions as CSV** is for spreadsheets and does not include the full scan history.

Cloud backup uses Firebase Google sign-in and is independent of Gmail authorization. On the device, Pocketlog serializes the local backup and encrypts it before upload. A snapshot includes transaction records and Gmail scan history; source evidence saved with a transaction can include its sender, subject, and received time. Email bodies and Gmail OAuth access tokens are not included. The encrypted snapshot is stored in the private Railway object-storage bucket `pocketlog-backups`. During an authenticated restore, the service unwraps the content key with its RSA private key and returns it over TLS so the app can decrypt the backup locally. This is device-side encryption with server-assisted restore, not end-to-end encryption. Protect the Railway secrets and keep a secure copy of the key ring; losing a key can make snapshots encrypted to it unrecoverable.

The `backup-api/` directory contains the Railway service source, API contract, configuration notes, and local checks. The deployed service health endpoint is `https://collabera-test-production.up.railway.app/health`. The source implements Firebase UID-scoped storage namespaces and a legacy-vault owner mapping; verify the deployed revision and test account isolation before relying on that source change for production isolation.

## Parser limits

Bank message templates vary and may change. The parser accepts supported structured receipts with a clear completed transaction, amount, date, and merchant or recipient details. Some Jago transfer receipts use Gmail's received time as an estimated transaction date. A sender domain is only a hint and is not proof that an email is genuine. Review every imported record, especially a new merchant or a record categorized as **Other**.

## Beta builds through Firebase App Distribution

The GitHub Actions workflow runs Android unit tests and lint, backup API tests and typecheck, and a debug APK build for branch pushes and pull requests to `main`. Only `main` builds and distributes a signed Firebase App Distribution beta, and only when repository variable `FIREBASE_BETA_ENABLED` is `true`. Testers receive an invitation and build notification from Firebase, then install from the tester page. The APK also remains available as a GitHub Actions artifact.

The Firebase distribution workflow needs repository variable `FIREBASE_APP_ID` and Actions secrets `FIREBASE_SERVICE_ACCOUNT_JSON`, `BETA_KEYSTORE_BASE64`, `BETA_STORE_PASSWORD`, `BETA_KEY_ALIAS`, and `BETA_KEY_PASSWORD`. Use a dedicated service account with the Firebase App Distribution Admin role. Keep the original keystore and passwords backed up securely: changing the signing certificate prevents in-place updates. The workflow assigns a fresh, increasing version code for each run.

Until `FIREBASE_BETA_ENABLED` is set, the workflow publishes a debug APK artifact. A beta APK signed with the dedicated key cannot update an existing APK signed with a different debug key. Export a JSON backup before uninstalling an existing installation. Add the beta signing certificate's SHA-1 to the Android Gmail OAuth configuration before using Gmail sync on beta builds; retain the existing OAuth client for existing installations.
