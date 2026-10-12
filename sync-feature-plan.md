# Sync report, skipped-alert recovery, and Review evidence

## Outcome

1. Every Gmail sync shows a durable report: checked, added to Review, skipped, previously seen, and whether more mail remains in the selected date range.
2. A user can recheck skipped Gmail alerts after parser improvements. A failed fetch remains eligible for retry.
3. Each newly imported Review item shows the source email sender, subject, received date, and account. Transaction date remains separately labelled. Email bodies are not stored.

## Data and migration

- Identify Gmail records by account email and Gmail message ID. Resolve the account with `users/me/profile` after read-only authorization.
- Add optional Gmail provenance to `Expense` and its JSON encoding. Older and manual records remain readable with null provenance.
- Replace the undifferentiated scanned-ID set with a versioned ledger containing `IMPORTED`, `SKIPPED`, `DISMISSED`, and `LEGACY_UNKNOWN` records. Preserve it across Gmail disconnects.
- Migrate old `gmail_scanned_ids` as `LEGACY_UNKNOWN`, because they mix imported and skipped mail. Exclude those IDs from routine imports to avoid duplicates. The explicit recheck action can revisit them in bounded batches; match an existing Gmail expense by transaction details and old subject before adding a recovered alert.
- Save an imported expense and its ledger entry in the same local preferences edit. Keep a dismissed tombstone when a Gmail Review item is deleted.
- Persist the last sync report and a catch-up cursor tied to account and date range. Changing the range starts a fresh scan.

## Scan behavior

- A normal sync starts with the newest matching bank emails. A separate Continue action resumes older pages using Gmail's page token.
- Fetch at most 50 full messages and traverse at most five list pages per run. Commit each processed message and completed page before advancing the cursor.
- Count parser failures as skipped and keep their message IDs for targeted recheck. Recheck skipped also revisits ambiguous legacy scanned IDs, reads at most 50 saved IDs per run, oldest checked first, then updates each result.
- On quota or network failure, keep completed work and show a partial report. Retrying resumes at the last completed page; an invalid page token restarts from newest with ID deduplication.
- Gmail's per-user quota still applies. The app will expose rate-limit status and avoid an unbounded scan.

## UI

- Home and Settings show the last sync report, time, selected range, and partial or complete state.
- Show Continue scanning when more matching pages remain, and Recheck skipped when safely recheckable records exist.
- Review cards show source evidence for Gmail items and keep manual imports clearly labelled. Avoid an unverified Gmail deep link.

## Verification

- Unit checks for ledger migration, JSON compatibility, deduplication, skipped-to-imported transition, dismissal, cursor/range behavior, and quota classification.
- Build the debug APK and run the existing unit suite.
- Emulator checks for empty and populated UI, report actions, and readable Review evidence. A live Gmail sync requires account authorization and an available Gmail quota window; do not claim it from emulator-only checks.

## Completed

- Implemented the versioned ledger, bounded sync engine, continuation, legacy scanned-ID recheck, durable report, and Review source details.
- Android unit tests passed and the debug app was installed on the emulator.
- Live Gmail verification exercised a normal scan, skipped-alert recheck, and continuation. Rechecked messages did not create duplicate review items, and imported alerts remained unconfirmed until the user reviewed them.
- Confirmed report and action buttons persist across app restart and Review source details render on the emulator. Additional matching pages and skipped alerts can be processed in bounded batches.
