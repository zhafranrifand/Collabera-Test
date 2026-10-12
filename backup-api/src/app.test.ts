import assert from 'node:assert/strict';
import { generateKeyPairSync, publicEncrypt, constants } from 'node:crypto';
import test from 'node:test';
import { createApp } from './app.js';
import type { Envelope, SnapshotRecord, SnapshotStore } from './contracts.js';
import { snapshotsToDelete } from './retention.js';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const KEY_ID = 'test-key-1';
const TOKEN = 'valid-test-token';
const OWNER = 'opaque-vault-abcdefghijklmnopqrstuvwxyz';
const SNAPSHOT_ID = '6d18fd3a-10c5-4d09-9a9f-13c240c02d92';
const NOW = new Date('2026-10-12T12:00:00.000Z');

function envelope(keyId = KEY_ID): Envelope {
  const contentKey = Buffer.alloc(32, 0x5a);
  const wrappedKey = publicEncrypt({
    key: publicKey,
    padding: constants.RSA_PKCS1_OAEP_PADDING,
    oaepHash: 'sha256',
    oaepLabel: Buffer.from(`pocketlog-backup:v1:${keyId}`),
  }, contentKey);
  return {
    version: 1,
    keyId,
    createdAt: NOW.toISOString(),
    algorithm: 'A256GCM+RSA-OAEP-256',
    nonce: Buffer.alloc(12, 1).toString('base64'),
    tag: Buffer.alloc(16, 2).toString('base64'),
    ciphertext: Buffer.from('{"encrypted":true}').toString('base64'),
    wrappedKey: wrappedKey.toString('base64'),
  };
}

class MemoryStore implements SnapshotStore {
  data = new Map<string, { envelope: Envelope; uploadedAt: string }>();
  async put(id: string, value: Envelope, uploadedAt: string) { this.data.set(id, { envelope: value, uploadedAt }); }
  async get(id: string) { return this.data.get(id) ?? null; }
  async list(): Promise<SnapshotRecord[]> {
    return [...this.data.entries()].map(([id, value]) => ({
      id,
      uploadedAt: value.uploadedAt,
      size: Buffer.byteLength(JSON.stringify(value)),
    }));
  }
  async delete(id: string) { this.data.delete(id); }
  async deleteMany(ids: string[]) { for (const id of ids) this.data.delete(id); }
}

function fixture() {
  const store = new MemoryStore();
  let issuedId = SNAPSHOT_ID;
  const app = createApp({
    verifyToken: async (token) => token === TOKEN ? { uid: 'firebase-user', ownerId: OWNER } : null,
    store,
    keyRing: new Map([[KEY_ID, privateKey]]),
    activeKeyId: KEY_ID,
    now: () => new Date(NOW),
    id: () => issuedId,
  });
  return { app, store, useId: (id: string) => { issuedId = id; } };
}

const request = (path: string, init: RequestInit = {}, authenticated = true) => new Request(`http://localhost${path}`, {
  ...init,
  headers: {
    ...(init.body === undefined ? {} : { 'content-type': 'application/json' }),
    ...(authenticated ? { authorization: `Bearer ${TOKEN}` } : {}),
    ...init.headers,
  },
});

test('health is public and does not reveal environment configuration', async () => {
  const { app } = fixture();
  const response = await app.request('/health');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok' });
});

test('backup endpoints require a valid bearer token', async () => {
  const { app } = fixture();
  const response = await app.request(request('/v1/backups', {}, false));
  assert.equal(response.status, 401);
});

test('rate limits unauthenticated requests by forwarded client IP', async () => {
  const { app } = fixture();
  let blocked: Response | undefined;
  for (let index = 0; index < 121; index += 1) {
    blocked = await app.request(new Request('http://localhost/v1/backup/key', {
      headers: { 'x-forwarded-for': '198.51.100.12', authorization: 'Bearer invalid' },
    }));
    if (index < 120) assert.equal(blocked.status, 401);
  }
  assert.equal(blocked?.status, 429);
});

test('key metadata publishes only the active public key and disables caching', async () => {
  const { app } = fixture();
  const response = await app.request('/v1/backup/key', { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('cache-control') ?? '', /no-store/);
  const body = await response.json() as Record<string, string>;
  assert.equal(body.keyId, KEY_ID);
  assert.equal(body.algorithm, 'RSA-OAEP-256');
  assert.equal(body.oaepLabel, `pocketlog-backup:v1:${KEY_ID}`);
  assert.equal(Buffer.from(body.publicKey!, 'base64').byteLength > 256, true);
  assert.equal('privateKey' in body, false);
});

test('encrypted snapshot upload, list, authenticated restore, and delete work end to end', async () => {
  const { app, store } = fixture();
  const uploaded = await app.request(request('/v1/backups', { method: 'POST', body: JSON.stringify(envelope()) }));
  assert.equal(uploaded.status, 201);
  const created = await uploaded.json() as { id: string; uploadedAt: string };
  assert.equal(created.id, SNAPSHOT_ID);
  assert.equal(store.data.size, 1);

  const listed = await app.request('/v1/backups', { headers: { authorization: `Bearer ${TOKEN}` } });
  const page = await listed.json() as { snapshots: SnapshotRecord[]; nextCursor: string | null };
  assert.equal(page.snapshots[0]?.id, SNAPSHOT_ID);
  assert.equal(page.nextCursor, null);

  const restored = await app.request(`/v1/backups/${SNAPSHOT_ID}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.equal(restored.status, 200);
  const restoreBody = await restored.json() as { envelope: Envelope; contentKey: string };
  assert.deepEqual(Buffer.from(restoreBody.contentKey, 'base64'), Buffer.alloc(32, 0x5a));
  const restoredEnvelope = restoreBody.envelope;
  assert.equal(restoredEnvelope.version, 1);
  assert.equal(restoredEnvelope.keyId, KEY_ID);
  assert.equal(restoredEnvelope.ciphertext, envelope().ciphertext);
  assert.match(restored.headers.get('cache-control') ?? '', /no-store/);

  const deniedRestore = await app.request(request(`/v1/backups/${SNAPSHOT_ID}`, {}, false));
  assert.equal(deniedRestore.status, 401);

  const deleted = await app.request(request(`/v1/backups/${SNAPSHOT_ID}`, { method: 'DELETE' }));
  assert.equal(deleted.status, 204);
  assert.equal(store.data.size, 0);
});

test('rejects malformed envelopes, unknown RSA keys, future timestamps, and traversal IDs', async () => {
  const { app } = fixture();
  const malformed = await app.request(request('/v1/backups', { method: 'POST', body: '{"version":2}' }));
  assert.equal(malformed.status, 400);
  const unknown = await app.request(request('/v1/backups', { method: 'POST', body: JSON.stringify(envelope('retired-key')) }));
  assert.equal(unknown.status, 400);
  const future = envelope();
  future.createdAt = '2026-10-12T12:06:00.000Z';
  const futureResponse = await app.request(request('/v1/backups', { method: 'POST', body: JSON.stringify(future) }));
  assert.equal(futureResponse.status, 400);
  const traversal = await app.request('/v1/backups/../../other-vault', { headers: { authorization: `Bearer ${TOKEN}` } });
  assert.ok([404, 400].includes(traversal.status));
});

test('validates list pagination bounds and opaque cursor', async () => {
  const { app, store, useId } = fixture();
  const first = envelope();
  await store.put(SNAPSHOT_ID, first, '2026-10-12T11:00:00.000Z');
  const secondId = '6d18fd3a-10c5-4d09-9a9f-13c240c02d93';
  useId(secondId);
  await store.put(secondId, first, '2026-10-12T10:00:00.000Z');
  const firstPage = await app.request('/v1/backups?limit=1', { headers: { authorization: `Bearer ${TOKEN}` } });
  const firstBody = await firstPage.json() as { snapshots: SnapshotRecord[]; nextCursor: string };
  assert.equal(firstBody.snapshots.length, 1);
  assert.ok(firstBody.nextCursor);
  const secondPage = await app.request(`/v1/backups?limit=1&cursor=${encodeURIComponent(firstBody.nextCursor)}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  const secondBody = await secondPage.json() as { snapshots: SnapshotRecord[]; nextCursor: string | null };
  assert.equal(secondBody.snapshots[0]?.id, secondId);
  assert.equal(secondBody.nextCursor, null);
  assert.equal((await app.request('/v1/backups?limit=101', { headers: { authorization: `Bearer ${TOKEN}` } })).status, 400);
  assert.equal((await app.request('/v1/backups?cursor=bad', { headers: { authorization: `Bearer ${TOKEN}` } })).status, 400);
});

test('rate limits snapshot uploads', async () => {
  const { app, useId } = fixture();
  for (let index = 0; index < 10; index += 1) {
    useId(`6d18fd3a-10c5-4d09-9a9f-13c240c02d${String(10 + index).padStart(2, '0')}`);
    const response = await app.request(request('/v1/backups', { method: 'POST', body: JSON.stringify(envelope()) }));
    assert.equal(response.status, 201);
  }
  const blocked = await app.request(request('/v1/backups', { method: 'POST', body: JSON.stringify(envelope()) }));
  assert.equal(blocked.status, 429);
});

test('retention keeps latest snapshot per seven newest UTC dates and four older weeks', () => {
  const records: SnapshotRecord[] = [];
  for (let daysAgo = 0; daysAgo <= 70; daysAgo += 1) {
    const date = new Date(NOW.getTime() - daysAgo * 86_400_000);
    for (let duplicate = 0; duplicate < 2; duplicate += 1) {
      records.push({
        id: `id-${daysAgo}-${duplicate}`,
        uploadedAt: new Date(date.getTime() + duplicate * 60_000).toISOString(),
        size: 100,
      });
    }
  }
  const obsolete = new Set(snapshotsToDelete(records));
  assert.equal(records.length - obsolete.size, 11);
  assert.equal(obsolete.has('id-0-1'), false);
  assert.equal(obsolete.has('id-0-0'), true);
});
