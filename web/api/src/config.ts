/** Environment, validated once at startup so a misconfigured server refuses to run. */
export interface Config {
  databaseUrl: string;
  syncToken: string;
  sessionSecret: string;
  ownerPasswordHash: string;
  port: number;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const required = (name: string, minLength = 1): string => {
    const value = env[name];
    if (!value || value.length < minLength) throw new Error(`${name} must be set${minLength > 1 ? ` (at least ${minLength} characters)` : ''}`);
    return value;
  };
  const ownerPasswordHash = required('OWNER_PASSWORD_HASH');
  if (!ownerPasswordHash.startsWith('scrypt$')) throw new Error('OWNER_PASSWORD_HASH must come from `npm run hash-password`');
  return {
    databaseUrl: required('DATABASE_URL'),
    syncToken: required('SYNC_TOKEN', 32),
    sessionSecret: required('SESSION_SECRET', 32),
    ownerPasswordHash,
    port: Number(env.PORT ?? 3001),
  };
}

export const CONFIG = Symbol('CONFIG');
