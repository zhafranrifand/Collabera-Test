import { createRemoteJWKSet, jwtVerify } from 'jose';
import type { TokenVerifier } from './contracts.js';

export function firebaseTokenVerifier(config: {
  projectId: string;
  allowedEmails: Set<string>;
  vaultId: string;
  legacyOwnerUid: string;
}): TokenVerifier {
  const issuer = `https://securetoken.google.com/${config.projectId}`;
  const jwks = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));
  return async (token) => {
    try {
      const { payload } = await jwtVerify(token, jwks, {
        issuer,
        audience: config.projectId,
        algorithms: ['RS256'],
        requiredClaims: ['exp', 'iat', 'auth_time', 'sub', 'email', 'email_verified'],
        maxTokenAge: '1h',
        clockTolerance: 30,
      });
      const email = typeof payload.email === 'string' ? payload.email.trim().toLowerCase() : '';
      if (payload.email_verified !== true || !config.allowedEmails.has(email) || typeof payload.sub !== 'string' || !/^[A-Za-z0-9:_-]{1,128}$/.test(payload.sub)) return null;
      // Keep historical snapshots in their original opaque bucket prefix, but
      // grant that legacy namespace to exactly one configured Firebase UID.
      // All other authorized accounts receive a UID-scoped namespace.
      const ownerId = payload.sub === config.legacyOwnerUid ? `legacy:${config.vaultId}` : `user:${payload.sub}`;
      return { uid: payload.sub, ownerId };
    } catch {
      return null;
    }
  };
}
