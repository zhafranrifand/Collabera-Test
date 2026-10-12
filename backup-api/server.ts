import { serve } from '@hono/node-server';
import { createApp } from './src/app.js';
import { firebaseTokenVerifier } from './src/auth.js';
import { parseKeyRing } from './src/crypto.js';
import { createS3Client, S3SnapshotStore } from './src/s3-store.js';

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment setting: ${name}`);
  return value;
}

const projectId = required('FIREBASE_PROJECT_ID');
const allowedEmails = new Set(required('ALLOWED_EMAILS').split(',').map((email) => email.trim().toLowerCase()).filter(Boolean));
if (allowedEmails.size === 0 || [...allowedEmails].some((email) => !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) throw new Error('ALLOWED_EMAILS must contain valid email addresses');
const vaultId = required('BACKUP_VAULT_ID');
if (!/^[A-Za-z0-9_-]{24,96}$/.test(vaultId)) throw new Error('BACKUP_VAULT_ID must be an opaque random string of 24–96 URL-safe characters');
const activeKeyId = required('BACKUP_ACTIVE_KEY_ID');
const keyRing = parseKeyRing(required('BACKUP_RSA_PRIVATE_KEYS_JSON'), activeKeyId);
const bucket = required('S3_BUCKET');
const store = new S3SnapshotStore(createS3Client(process.env), bucket, vaultId);
const app = createApp({
  verifyToken: firebaseTokenVerifier({ projectId, allowedEmails, vaultId }),
  store,
  keyRing,
  activeKeyId,
});

const port = Number(process.env.PORT ?? '3000');
if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be a valid TCP port');
serve({ fetch: app.fetch, port, hostname: '0.0.0.0' });
