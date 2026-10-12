import { z } from 'zod';

export const MAX_ENVELOPE_BYTES = 14 * 1024 * 1024;
export const MAX_REQUEST_BYTES = 15 * 1024 * 1024;
export const MAX_SNAPSHOTS = 10_000;

const base64 = (maxBytes: number) => z.string()
  .max(Math.ceil(maxBytes / 3) * 4 + 4)
  .regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/)
  .refine((value) => Buffer.from(value, 'base64').length <= maxBytes);

export const envelopeSchema = z.object({
  version: z.literal(1),
  keyId: z.string().min(1).max(64).regex(/^[A-Za-z0-9._-]+$/),
  createdAt: z.string().datetime({ offset: true }),
  algorithm: z.literal('A256GCM+RSA-OAEP-256'),
  nonce: base64(12).refine((value) => Buffer.from(value, 'base64').length === 12),
  tag: base64(16).refine((value) => Buffer.from(value, 'base64').length === 16),
  ciphertext: base64(MAX_ENVELOPE_BYTES),
  wrappedKey: base64(512).refine((value) => {
    const length = Buffer.from(value, 'base64').length;
    return length >= 256 && length <= 512;
  }),
}).strict();

export type Envelope = z.infer<typeof envelopeSchema>;

export type SnapshotRecord = {
  id: string;
  uploadedAt: string;
  size: number;
};

export interface SnapshotStore {
  put(id: string, envelope: Envelope, uploadedAt: string): Promise<void>;
  get(id: string): Promise<{ envelope: Envelope; uploadedAt: string } | null>;
  list(): Promise<SnapshotRecord[]>;
  delete(id: string): Promise<void>;
  deleteMany(ids: string[]): Promise<void>;
}

export type Principal = { uid: string; ownerId: string };
export type TokenVerifier = (token: string) => Promise<Principal | null>;
