import { constants, createPrivateKey, createPublicKey, privateDecrypt } from 'node:crypto';

export type KeyRing = Map<string, ReturnType<typeof createPrivateKey>>;

export function parseKeyRing(encodedJson: string, activeKeyId: string): KeyRing {
  let entries: Record<string, string>;
  try {
    entries = JSON.parse(encodedJson) as Record<string, string>;
  } catch {
    throw new Error('BACKUP_RSA_PRIVATE_KEYS_JSON must contain a JSON object');
  }
  const ring: KeyRing = new Map();
  for (const [id, base64Der] of Object.entries(entries)) {
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(id) || typeof base64Der !== 'string') throw new Error('Invalid RSA key ring entry');
    const der = Buffer.from(base64Der, 'base64');
    if (der.length < 1 || der.length > 16_384 || der.toString('base64') !== base64Der) throw new Error('Invalid RSA private key encoding');
    const key = createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
    const modulusBits = key.asymmetricKeyDetails?.modulusLength ?? 0;
    if (key.asymmetricKeyType !== 'rsa' || modulusBits < 2048 || modulusBits > 4096) throw new Error('RSA keys must be 2048–4096 bits');
    ring.set(id, key);
  }
  if (!ring.has(activeKeyId)) throw new Error('BACKUP_ACTIVE_KEY_ID is missing from the key ring');
  return ring;
}

export function publicKeySpkiBase64(key: ReturnType<typeof createPrivateKey>): string {
  return createPublicKey(key).export({ type: 'spki', format: 'der' }).toString('base64');
}

export function unwrapContentKey(wrappedKey: string, keyId: string, ring: KeyRing): Buffer | null {
  const key = ring.get(keyId);
  if (!key) return null;
  try {
    const contentKey = privateDecrypt({
      key,
      padding: constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256',
      oaepLabel: Buffer.from(`pocketlog-backup:v1:${keyId}`, 'utf8'),
    }, Buffer.from(wrappedKey, 'base64'));
    return contentKey.length === 32 ? contentKey : null;
  } catch {
    return null;
  }
}
