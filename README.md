# ExpenseMail — Android

An offline-first Android expense tracker that turns bank transaction alerts shared from Gmail into reviewable expenses. It does not request mailbox-wide access.

## Features

- Monthly spend dashboard and recent activity
- Review, edit, confirm, and delete imported transactions
- IDR totals and merchant-based category suggestions
- Sender-domain hints for BCA, Livin’ by Mandiri, and Bank Jago
- Parsing for the supplied BCA, Mandiri, and Jago transaction formats
- Separate labeling for transfers
- Local app-private storage

## Import a transaction

Open a bank alert in Gmail and choose Share → ExpenseMail. If Gmail does not offer the app, copy the alert text and paste it into ExpenseMail. Check the parsed amount, merchant, date, and type before confirming. Only text you explicitly share is parsed; the original email is not saved.

## Run on Android

Open the project in Android Studio with JDK 17 and Android SDK 35, or use the Build ExpenseMail APK workflow under GitHub Actions. The workflow builds a debug APK artifact for personal testing.

## Privacy and parser notes

Inbox scanning is not enabled. Bank templates vary, so parsing is best-effort. Sender domains are hints and cannot prove that an email is genuine. Review every imported transaction. Transfers are excluded from expense totals by default.
