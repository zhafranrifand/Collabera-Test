# Pocketlog backup API

Small Node 22 / Bun-compatible API for encrypted Pocketlog snapshots. It verifies Firebase ID tokens directly against Google's rotating public signing keys; it does not use a Firebase service-account private key. The S3-compatible bucket must be private and dedicated to this data.

## Security and data flow

- Every `/v1` endpoint requires a Firebase ID token with a valid Google signature, the configured Firebase project as issuer and audience, a verified email, and an exact match in `ALLOWED_EMAILS`.
- Both allowed emails map to the same random `BACKUP_VAULT_ID`. The vault ID is not a user email or Firebase UID.
- The mobile client encrypts a versioned JSON backup before upload. The service stores the envelope as opaque data and does not persist email bodies, Gmail tokens, or plaintext transaction records.
- On an authenticated restore, the service unwraps the 32-byte AES key with the RSA private key and returns it over TLS with the envelope. The phone performs AES-GCM decryption. This is encryption at rest with server-assisted restore, **not end-to-end encryption**: the service operator and anyone who obtains the configured RSA private key can unwrap a content key.
- Use HTTPS only. API responses containing keys or snapshots have `Cache-Control: no-store`. The service intentionally emits no request or payload logs and returns generic internal errors.
- Per-process rate limits are best-effort; run one API replica unless a shared rate-limit store is added. Requests are limited to 120 per forwarded client IP per minute and 120 per owner per minute; uploads are limited to 10 per owner per hour, and deletes to 10 per owner per minute. Request bodies are capped at 15 MiB; ciphertext at 14 MiB.
- Retention keeps the newest snapshot for each of the seven newest UTC calendar dates and the newest snapshot from each of the next four distinct older ISO weeks. Other objects are deleted after successful upload. If listing or retention cleanup fails after the new object is durable, upload still returns success with `retentionPending: true`; a later upload retries cleanup. Keep S3 bucket versioning and provider lifecycle policies disabled unless their interaction with deletion is understood.

## Client envelope contract

1. Call `GET /v1/backup/key` with `Authorization: Bearer <Firebase ID token>`.
2. Generate a random 32-byte content key and 12-byte GCM nonce. Serialize the app backup as UTF-8 JSON and encrypt using AES-256-GCM with no additional authenticated data. Preserve the 16-byte authentication tag separately from ciphertext. Do not put plaintext or personally identifying values in the envelope metadata.
3. Wrap the content key with the returned SPKI DER RSA public key using RSA-OAEP, SHA-256, and the returned UTF-8 `oaepLabel`. The key must be 2048–4096 bits. Preserve `keyId` so future key rotation can retain old decryption keys.
4. Upload JSON with these exact fields to `POST /v1/backups`:

```json
{
  "version": 1,
  "keyId": "2026-10",
  "createdAt": "2026-10-12T12:00:00.000Z",
  "algorithm": "A256GCM+RSA-OAEP-256",
  "nonce": "base64-12-byte-value",
  "tag": "base64-16-byte-value",
  "ciphertext": "base64-ciphertext",
  "wrappedKey": "base64-RSA-OAEP-ciphertext"
}
```

`createdAt` must be an ISO-8601 timestamp and no more than five minutes in the future. The server's `uploadedAt` timestamp controls retention. `wrappedKey` is 256–512 bytes (RSA 2048–4096 bits). Unknown `keyId` values are rejected.

## HTTP API

| Method and path | Behavior |
| --- | --- |
| `GET /health` | Public process health only; does not report secrets or dependency state. |
| `GET /v1/backup/key` | Authenticated active public key and crypto parameters. |
| `POST /v1/backups` | Authenticated encrypted snapshot upload; returns `{id, uploadedAt, retentionPending}`. |
| `GET /v1/backups?limit=50&cursor=...` | Authenticated descending list; limit is 1–100. Cursor is opaque and returned as `nextCursor`. |
| `GET /v1/backups/{id}` | Authenticated restore envelope and base64 content key. |
| `DELETE /v1/backups/{id}` | Authenticated deletion from the shared vault. |

Errors use `{error, message}`. Snapshot IDs are server-generated UUIDs and object keys are stored beneath an opaque vault prefix. A client must not treat a successful upload as a successful device restore; it should authenticate/decrypt locally after download.

## Runtime configuration

Set these as Railway service variables; never add values to this repository:

| Variable | Purpose |
| --- | --- |
| `PORT` | Optional HTTP listen port; defaults to 3000. |
| `FIREBASE_PROJECT_ID` | Firebase Authentication project ID used for token issuer and audience validation. |
| `ALLOWED_EMAILS` | Comma-separated exact email allowlist; configure only the two selected testers. |
| `BACKUP_VAULT_ID` | Random 24–96 character URL-safe opaque identifier shared by those allowed accounts. |
| `BACKUP_ACTIVE_KEY_ID` | ID of the RSA public key currently returned to clients. |
| `BACKUP_RSA_PRIVATE_KEYS_JSON` | Secret JSON object mapping key IDs to base64 PKCS#8 DER RSA private keys. Retain old entries while snapshots encrypted to them remain. |
| `S3_ENDPOINT` | Private S3-compatible endpoint for the Railway bucket. |
| `S3_REGION` | Bucket's S3 region value. |
| `S3_BUCKET` | Private bucket name. |
| `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY` | Bucket-scoped credentials with list, get, put, and delete access only to this bucket. |

Generate a fresh RSA key outside the repository, for example with OpenSSL `genpkey` at 3072 bits. Convert the private key to PKCS#8 DER, base64-encode it, and put it into the Railway secret mapping. Never expose the private key through the API. To rotate, add a new key entry, change `BACKUP_ACTIVE_KEY_ID`, and retain prior private keys until all snapshots referencing those IDs expire or are deleted. Back up the key ring securely; losing an old private key makes its snapshots unrecoverable.

The bucket service account should have access only to its designated bucket. Enable server-side encryption at the provider. This API does not manage IAM, provision Railway resources, or configure billing.

## Local development and checks

Requirements: Node.js 22 or newer.

```sh
npm install
npm test
npm run typecheck
npm start
```

Tests use in-memory storage and generated ephemeral RSA keys. They do not contact Firebase, Railway, S3, or any external account.
