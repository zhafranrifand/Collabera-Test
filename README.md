# ExpenseMail — Android MVP

An offline-first Android expense tracker that turns transaction alerts into reviewable expenses. Alerts can be shared from Gmail, or the app can manually search a selected date range for BCA, Livin’ by Mandiri, and Bank Jago emails after you grant Google read-only Gmail access. Parsed transactions always wait for your review.

## Included

- Monthly spend dashboard and recent activity
- Review, edit, confirm, and delete flows
- IDR display and basic merchant-based category suggestions
- Bank hints from sender domains, including BCA, Livin’ by Mandiri, and Bank Jago
- Parsing of the supplied bank templates: BCA `Total Payment`, Mandiri `Total Transaksi`, and Jago `Jumlah`
- Transaction dates from the message body; transfer alerts labeled separately from expenses
- Android Share target for transaction email text from Gmail
- Manual Gmail sync with a selectable start/end date and supported bank sender filters
- Local parsing; no email bodies or OAuth tokens are sent to an ExpenseMail server
- Local persistence with app-private SharedPreferences
- Empty states until real transactions are imported or confirmed

## Run in Android Studio

### Version 1.1.0

The Home screen now offers **Import bank emails**, Settings scrolls on smaller screens, and the Gmail date range is shown first. The app displays its installed version on Home and in Settings.

Builds install as **ExpenseMail** (`com.expensemail.app`). Older builds may use a different signing key and cannot be updated in place. Check for locally saved transactions before replacing an installation, because uninstalling deletes them. The separate ExpenseMail Preview can also be uninstalled if it is no longer needed. Firebase beta builds use a stable private signing key for future updates.

Open this folder in Android Studio with JDK 17 and Android SDK platform 35 installed. Let Gradle sync, then run the `app` configuration on a device or emulator. The Gradle wrapper configuration is included; Android Studio or Gradle downloads the required distribution on first sync.

## Import a Gmail alert

Open a bank transaction alert in Gmail and choose **Share → ExpenseMail**. If Gmail only offers a link, copy the alert text and paste it into the Review screen. Only the text explicitly shared to the app is parsed. The original email is not saved. If sender details aren't included, enter the sender email or domain in the optional field.

## Gmail access and setup

Gmail sync uses Google's `gmail.readonly` scope. The app's sync button searches only the selected date range and the supported bank sender domains, then parses email content on-device. Google consent for this scope applies to Gmail messages broadly, even though the app's query is limited. Email bodies and OAuth tokens are not uploaded to an ExpenseMail server. Imported message IDs are stored locally to prevent duplicates; disconnecting revokes Google's grant and clears that import history.

Before sync can work, configure a Google Cloud project: enable the Gmail API, configure the OAuth consent screen with the `gmail.readonly` scope, add your Google account as a test user while the app is in Testing, and register an Android OAuth client for package `com.expensemail.app` using the SHA-1 signing certificate of the installed build. Public distribution needs Google's verification for the restricted scope. Register the Firebase beta signing certificate separately from any Android Studio debug certificate; do not publish private keystore files or credentials in the repository.

If you prefer not to grant Gmail access, the Share flow remains available and only receives the specific message you share.

## Parser limits

Bank email templates vary. The MVP recognizes the labeled amount, date, and recipient patterns shown in the supplied BCA, Livin’ by Mandiri, and Jago examples. It handles Indonesian thousands/decimal separators and suggests a bank from the sender domain. A domain is only a hint, not proof that an email is genuine; review every imported transaction. If no transaction date is found, the import date is used. Transfer alerts are excluded from expense totals unless you change their type in Edit.

## Beta builds through Firebase App Distribution

The GitHub Actions workflow builds on each push to `main` and can also be started manually. When the repository variable `FIREBASE_BETA_ENABLED` is `true`, it signs a release APK with a persistent beta key and uploads it to the Firebase App Distribution group `mvp-testers`. Testers receive an invitation and new-build notification from Firebase, then install from its tester page. The APK remains available as a GitHub Actions artifact.

The Firebase path needs repository variable `FIREBASE_APP_ID` and Actions secrets `FIREBASE_SERVICE_ACCOUNT_JSON`, `BETA_KEYSTORE_BASE64`, `BETA_STORE_PASSWORD`, `BETA_KEY_ALIAS`, and `BETA_KEY_PASSWORD`. Use a dedicated service account with the Firebase App Distribution Admin role. Keep the original keystore and passwords backed up securely: changing the signing certificate prevents in-place updates. The workflow sets a fresh, increasing version code for each run.

Until `FIREBASE_BETA_ENABLED` is set, the workflow continues to publish a debug APK artifact. A Firebase beta APK signed with the new key cannot update an existing APK signed with a different debug key. **Check and export any locally saved transactions before uninstalling an existing installation.** This app disables Android backup and currently has no in-app export, so uninstalling it deletes its local data. Create a second Android Gmail OAuth client for the beta key's SHA-1 before using Gmail sign-in on beta builds; retain the old client for existing installs.
