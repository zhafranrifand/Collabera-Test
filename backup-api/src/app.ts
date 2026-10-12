import { Hono, type Context } from 'hono';
import { randomUUID } from 'node:crypto';
import { isIP } from 'node:net';
import type { Envelope, Principal, SnapshotRecord, SnapshotStore, TokenVerifier } from './contracts.js';
import { MAX_ENVELOPE_BYTES, MAX_REQUEST_BYTES, MAX_SNAPSHOTS, envelopeSchema } from './contracts.js';
import { type KeyRing, publicKeySpkiBase64, unwrapContentKey } from './crypto.js';
import { snapshotsToDelete } from './retention.js';

type Rate = { count: number; resetsAt: number };

export type AppDependencies = {
  verifyToken: TokenVerifier;
  store: SnapshotStore;
  keyRing: KeyRing;
  activeKeyId: string;
  now?: () => Date;
  id?: () => string;
};

export function createApp(deps: AppDependencies) {
  const app = new Hono();
  const rates = new Map<string, Rate>();
  const now = deps.now ?? (() => new Date());
  const makeId = deps.id ?? randomUUID;

  const limited = (key: string, max: number, windowMs: number): boolean => {
    const time = now().getTime();
    let rate = rates.get(key);
    if (!rate || rate.resetsAt <= time) {
      if (rates.size >= 20_000) {
        for (const [candidate, value] of rates) if (value.resetsAt <= time) rates.delete(candidate);
      }
      if (!rates.has(key) && rates.size >= 20_000) return false;
      rate = { count: 0, resetsAt: time + windowMs };
    }
    rate.count += 1;
    rates.set(key, rate);
    return rate.count <= max;
  };

  app.onError((_error, c) => c.json({ error: 'internal_error', message: 'Request could not be completed.' }, 500));
  app.get('/health', (c) => c.json({ status: 'ok' }));
  app.use('/v1/*', async (c, next) => {
    // Railway sets X-Real-IP at its edge. Do not trust a client-controlled
    // X-Forwarded-For chain for the unauthenticated IP rate limit.
    const realIp = c.req.header('x-real-ip')?.trim();
    const clientIp = realIp && isIP(realIp) ? realIp : 'unknown';
    if (!limited(`ip:${clientIp}`, 120, 60_000)) return c.json({ error: 'rate_limited', message: 'Too many requests. Try again shortly.' }, 429);
    await next();
  });

  const getPrincipal = async (c: Context): Promise<Principal | null> => {
    const auth = c.req.header('authorization');
    if (!auth || !/^Bearer\s+\S+$/i.test(auth)) return null;
    const token = auth.replace(/^Bearer\s+/i, '');
    try { return await deps.verifyToken(token); } catch { return null; }
  };

  const authenticate = async (c: Context) => {
    const principal = await getPrincipal(c);
    if (!principal) return { response: c.json({ error: 'unauthorized', message: 'A valid authorized Firebase account is required.' }, 401) };
    if (!limited(`owner:${principal.uid}:read`, 120, 60_000)) return { response: c.json({ error: 'rate_limited', message: 'Too many requests. Try again shortly.' }, 429) };
    return { principal };
  };

  app.get('/v1/backup/key', async (c) => {
    const auth = await authenticate(c);
    if ('response' in auth) return auth.response;
    if (!limited(`owner:${auth.principal.uid}:key`, 30, 60_000)) return c.json({ error: 'rate_limited', message: 'Too many requests. Try again shortly.' }, 429);
    const key = deps.keyRing.get(deps.activeKeyId);
    if (!key) return c.json({ error: 'key_unavailable', message: 'Backup encryption is temporarily unavailable.' }, 503);
    return c.json({
      keyId: deps.activeKeyId,
      algorithm: 'RSA-OAEP-256',
      publicKeyFormat: 'spki-der-base64',
      publicKey: publicKeySpkiBase64(key),
      oaepLabel: `pocketlog-backup:v1:${deps.activeKeyId}`,
      contentEncryption: 'AES-256-GCM',
    }, 200, noStoreHeaders);
  });

  app.post('/v1/backups', async (c) => {
    const auth = await authenticate(c);
    if ('response' in auth) return auth.response;
    if (!limited(`owner:${auth.principal.uid}:write`, 10, 60 * 60_000)) return c.json({ error: 'rate_limited', message: 'Backup limit reached. Try again later.' }, 429);
    const contentLength = Number(c.req.header('content-length') ?? '0');
    if (Number.isFinite(contentLength) && contentLength > MAX_REQUEST_BYTES) return c.json({ error: 'payload_too_large', message: 'Backup is larger than the supported limit.' }, 413);
    const parsedJson = await readLimitedJson(c.req.raw, MAX_REQUEST_BYTES);
    if (!parsedJson.ok) return c.json({ error: parsedJson.tooLarge ? 'payload_too_large' : 'invalid_json', message: parsedJson.tooLarge ? 'Backup is larger than the supported limit.' : 'Request body must be valid JSON.' }, parsedJson.tooLarge ? 413 : 400);
    const parsed = envelopeSchema.safeParse(parsedJson.value);
    if (!parsed.success) return c.json({ error: 'invalid_envelope', message: 'Backup envelope does not match version 1.' }, 400);
    if (!deps.keyRing.has(parsed.data.keyId)) return c.json({ error: 'unknown_key', message: 'Backup uses an unsupported encryption key.' }, 400);
    if (Buffer.byteLength(JSON.stringify(parsed.data)) > MAX_ENVELOPE_BYTES) return c.json({ error: 'payload_too_large', message: 'Backup is larger than the supported limit.' }, 413);
    if (Date.parse(parsed.data.createdAt) > now().getTime() + 5 * 60_000) return c.json({ error: 'invalid_timestamp', message: 'Backup creation time is too far in the future.' }, 400);

    const uploadedAt = now().toISOString();
    const id = makeId();
    try {
      await deps.store.put(auth.principal.ownerId, id, parsed.data, uploadedAt);
    } catch {
      return c.json({ error: 'storage_unavailable', message: 'Backup could not be saved.' }, 503);
    }
    let retentionPending = false;
    try {
      const snapshots = await deps.store.list(auth.principal.ownerId);
      if (snapshots.length > MAX_SNAPSHOTS) throw new Error('Snapshot count exceeds safe retention limit');
      const obsolete = snapshotsToDelete(snapshots);
      if (obsolete.length) await deps.store.deleteMany(auth.principal.ownerId, obsolete);
    } catch {
      // The object is already durable; report success and let a later upload retry retention.
      retentionPending = true;
    }
    return c.json({ id, uploadedAt, retentionPending }, 201, noStoreHeaders);
  });

  app.get('/v1/backups', async (c) => {
    const auth = await authenticate(c);
    if ('response' in auth) return auth.response;
    const limitRaw = c.req.query('limit') ?? '50';
    const limit = Number(limitRaw);
    if (!/^\d+$/.test(limitRaw) || limit < 1 || limit > 100) return c.json({ error: 'invalid_limit', message: 'Limit must be from 1 through 100.' }, 400);
    const cursor = decodeCursor(c.req.query('cursor'));
    if (cursor === false) return c.json({ error: 'invalid_cursor', message: 'Cursor is invalid.' }, 400);
    let snapshots: SnapshotRecord[];
    try { snapshots = await deps.store.list(auth.principal.ownerId); }
    catch { return c.json({ error: 'storage_unavailable', message: 'Snapshots could not be listed.' }, 503); }
    if (snapshots.length > MAX_SNAPSHOTS) return c.json({ error: 'storage_unavailable', message: 'Snapshot listing exceeds the safe limit.' }, 503);
    snapshots.sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || b.id.localeCompare(a.id));
    const after = cursor ? snapshots.filter((s) => s.uploadedAt < cursor.uploadedAt || (s.uploadedAt === cursor.uploadedAt && s.id < cursor.id)) : snapshots;
    const page = after.slice(0, limit);
    const nextCursor = after.length > limit ? encodeCursor(page[page.length - 1]!) : null;
    return c.json({ snapshots: page, nextCursor }, 200, noStoreHeaders);
  });

  app.get('/v1/backups/:id', async (c) => {
    const auth = await authenticate(c);
    if ('response' in auth) return auth.response;
    const id = c.req.param('id');
    if (!isUuid(id)) return c.json({ error: 'not_found', message: 'Snapshot not found.' }, 404);
    try {
      const stored = await deps.store.get(auth.principal.ownerId, id);
      if (!stored) return c.json({ error: 'not_found', message: 'Snapshot not found.' }, 404);
      const contentKey = unwrapContentKey(stored.envelope.wrappedKey, stored.envelope.keyId, deps.keyRing);
      if (!contentKey) return c.json({ error: 'restore_key_unavailable', message: 'This backup cannot be restored with the available keys.' }, 409);
      return c.json({
        id,
        uploadedAt: stored.uploadedAt,
        envelope: stored.envelope,
        contentKey: contentKey.toString('base64'),
      }, 200, noStoreHeaders);
    } catch {
      return c.json({ error: 'storage_unavailable', message: 'Snapshot could not be restored.' }, 503);
    }
  });

  app.delete('/v1/backups/:id', async (c) => {
    const auth = await authenticate(c);
    if ('response' in auth) return auth.response;
    if (!limited(`owner:${auth.principal.uid}:delete`, 10, 60_000)) return c.json({ error: 'rate_limited', message: 'Too many delete requests. Try again shortly.' }, 429);
    const id = c.req.param('id');
    if (!isUuid(id)) return c.json({ error: 'not_found', message: 'Snapshot not found.' }, 404);
    try {
      if (!await deps.store.get(auth.principal.ownerId, id)) return c.json({ error: 'not_found', message: 'Snapshot not found.' }, 404);
      await deps.store.delete(auth.principal.ownerId, id);
      return c.body(null, 204, noStoreHeaders);
    } catch {
      return c.json({ error: 'storage_unavailable', message: 'Snapshot could not be deleted.' }, 503);
    }
  });

  return app;
}

const noStoreHeaders = { 'Cache-Control': 'no-store, max-age=0', Pragma: 'no-cache' };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isUuid = (value: string) => UUID.test(value);

async function readLimitedJson(request: Request, maxBytes: number): Promise<{ ok: true; value: unknown } | { ok: false; tooLarge: boolean }> {
  if (!request.body) return { ok: false, tooLarge: false };
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) {
        await reader.cancel();
        return { ok: false, tooLarge: true };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false, tooLarge: false };
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return { ok: true, value: JSON.parse(new TextDecoder().decode(bytes)) as unknown }; }
  catch { return { ok: false, tooLarge: false }; }
}

type Cursor = { uploadedAt: string; id: string };
function encodeCursor(snapshot: SnapshotRecord): string {
  return Buffer.from(JSON.stringify({ uploadedAt: snapshot.uploadedAt, id: snapshot.id })).toString('base64url');
}
function decodeCursor(value: string | undefined): Cursor | null | false {
  if (value === undefined) return null;
  if (value.length > 512 || !/^[A-Za-z0-9_-]+$/.test(value)) return false;
  try {
    const parsed = JSON.parse(Buffer.from(value, 'base64url').toString('utf8')) as Partial<Cursor>;
    return typeof parsed.uploadedAt === 'string' && Number.isFinite(Date.parse(parsed.uploadedAt)) && typeof parsed.id === 'string' && isUuid(parsed.id)
      ? { uploadedAt: parsed.uploadedAt, id: parsed.id }
      : false;
  } catch { return false; }
}
