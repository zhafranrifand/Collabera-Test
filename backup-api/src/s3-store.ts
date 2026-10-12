import {
  DeleteObjectCommand,
  DeleteObjectsCommand,
  GetObjectCommand,
  ListObjectsV2Command,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import type { Envelope, SnapshotRecord, SnapshotStore } from './contracts.js';
import { MAX_ENVELOPE_BYTES, MAX_SNAPSHOTS, envelopeSchema } from './contracts.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class S3SnapshotStore implements SnapshotStore {
  private readonly prefix: string;
  constructor(private readonly client: S3Client, private readonly bucket: string, vaultId: string) {
    this.prefix = `${vaultId}/snapshots/`;
  }

  private key(id: string): string {
    if (!UUID.test(id)) throw new Error('Invalid snapshot ID');
    return `${this.prefix}${id}.json`;
  }

  async put(id: string, envelope: Envelope, uploadedAt: string): Promise<void> {
    const body = JSON.stringify({ envelope, uploadedAt });
    if (Buffer.byteLength(body) > MAX_ENVELOPE_BYTES) throw new Error('Envelope exceeds storage limit');
    await this.client.send(new PutObjectCommand({
      Bucket: this.bucket,
      Key: this.key(id),
      Body: body,
      ContentType: 'application/json',
    }));
  }

  async get(id: string): Promise<{ envelope: Envelope; uploadedAt: string } | null> {
    try {
      const response = await this.client.send(new GetObjectCommand({ Bucket: this.bucket, Key: this.key(id) }));
      if (!response.Body) return null;
      const raw = await response.Body.transformToString();
      if (Buffer.byteLength(raw) > MAX_ENVELOPE_BYTES) return null;
      const decoded = JSON.parse(raw) as { envelope?: unknown; uploadedAt?: unknown };
      const parsed = envelopeSchema.safeParse(decoded.envelope);
      if (!parsed.success || typeof decoded.uploadedAt !== 'string' || !Number.isFinite(Date.parse(decoded.uploadedAt))) return null;
      return { envelope: parsed.data, uploadedAt: decoded.uploadedAt };
    } catch (error) {
      if (isMissing(error)) return null;
      throw error;
    }
  }

  async list(): Promise<SnapshotRecord[]> {
    const records: SnapshotRecord[] = [];
    let continuationToken: string | undefined;
    do {
      const response = await this.client.send(new ListObjectsV2Command({
        Bucket: this.bucket,
        Prefix: this.prefix,
        ContinuationToken: continuationToken,
        MaxKeys: 1000,
      }));
      for (const object of response.Contents ?? []) {
        if (!object.Key || !object.LastModified || object.Size === undefined) continue;
        const name = object.Key.slice(this.prefix.length);
        if (!name.endsWith('.json')) continue;
        const id = name.slice(0, -5);
        if (!UUID.test(id)) continue;
        records.push({ id, uploadedAt: object.LastModified.toISOString(), size: object.Size });
        if (records.length > MAX_SNAPSHOTS) throw new Error('Snapshot count exceeds safe listing limit');
      }
      continuationToken = response.IsTruncated ? response.NextContinuationToken : undefined;
    } while (continuationToken);
    return records;
  }

  async delete(id: string): Promise<void> {
    await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: this.key(id) }));
  }

  async deleteMany(ids: string[]): Promise<void> {
    for (let offset = 0; offset < ids.length; offset += 1000) {
      const response = await this.client.send(new DeleteObjectsCommand({
        Bucket: this.bucket,
        Delete: { Objects: ids.slice(offset, offset + 1000).map((id) => ({ Key: this.key(id) })), Quiet: true },
      }));
      if (response.Errors?.length) throw new Error('Snapshot cleanup reported object deletion failures');
    }
  }
}

function isMissing(error: unknown): boolean {
  return typeof error === 'object' && error !== null && '$metadata' in error &&
    (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode === 404;
}

export function createS3Client(env: NodeJS.ProcessEnv): S3Client {
  const endpoint = env.S3_ENDPOINT;
  const region = env.S3_REGION;
  const accessKeyId = env.S3_ACCESS_KEY_ID;
  const secretAccessKey = env.S3_SECRET_ACCESS_KEY;
  if (!endpoint || !region || !accessKeyId || !secretAccessKey) throw new Error('S3 storage configuration is incomplete');
  return new S3Client({
    endpoint,
    region,
    forcePathStyle: true,
    credentials: { accessKeyId, secretAccessKey },
  });
}
