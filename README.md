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
- Sample transactions so the app is useful immediately

## Run in Android Studio

### Version 1.1.0

The Home screen now offers **Import bank emails**, Settings scrolls on smaller screens, and the Gmail date range is shown first. The app displays its installed version on Home and in Settings.

Builds install as **ExpenseMail** (`com.expensemail.app`). If an older build uses a different signing key, uninstall it before installing this APK; this deletes its local transactions. The separate ExpenseMail Preview can also be uninstalled if it is no longer needed. Before distributing future updates, configure a stable private signing key; fresh CI debug keys cannot update an installed build in place.

Open this folder in Android Studio with JDK 17 and Android SDK platform 35 installed. Let Gradle sync, then run the `app` configuration on a device or emulator. The Gradle wrapper configuration is included; Android Studio or Gradle downloads the required distribution on first sync.

## Import a Gmail alert

Open a bank transaction alert in Gmail and choose **Share → ExpenseMail**. If Gmail only offers a link, copy the alert text and paste it into the Review screen. Only the text explicitly shared to the app is parsed. The original email is not saved. If sender details aren't included, enter the sender email or domain in the optional field.

## Gmail access and setup

Gmail sync uses Google's `gmail.readonly` scope. The app's sync button searches only the selected date range and the supported bank sender domains, then parses email content on-device. Google consent for this scope applies to Gmail messages broadly, even though the app's query is limited. Email bodies and OAuth tokens are not uploaded to an ExpenseMail server. Imported message IDs are stored locally to prevent duplicates; disconnecting revokes Google's grant and clears that import history.

Before sync can work, configure a Google Cloud project: enable the Gmail API, configure the OAuth consent screen with the `gmail.readonly` scope, add your Google account as a test user while the app is in Testing, and register an Android OAuth client for package `com.expensemail.app` using the SHA-1 signing certificate of the installed build. Public distribution needs Google's verification for the restricted scope. The GitHub Actions debug build must use a stable signing key if you register its fingerprint; do not publish private keystore files or credentials in the repository.

If you prefer not to grant Gmail access, the Share flow remains available and only receives the specific message you share.

## Parser limits

Bank email templates vary. The MVP recognizes the labeled amount, date, and recipient patterns shown in the supplied BCA, Livin’ by Mandiri, and Jago examples. It handles Indonesian thousands/decimal separators and suggests a bank from the sender domain. A domain is only a hint, not proof that an email is genuine; review every imported transaction. If no transaction date is found, the import date is used. Transfer alerts are excluded from expense totals unless you change their type in Edit.

## Build an APK from your phone with GitHub Actions

The project includes a cloud build workflow. From a phone, upload the extracted project files to a GitHub repository (a private repository is fine), then open **Actions → Build ExpenseMail APK → Run workflow**. When the run finishes, download the `ExpenseMail-debug.apk` artifact from that run and open it on your phone to install. GitHub requires you to be signed in to download a private repository's artifact. The workflow produces a debug APK for personal testing, not a Play Store release.
