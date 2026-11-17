import { CanActivate, ExecutionContext, Inject, Injectable, UnauthorizedException } from '@nestjs/common';
import type { Request } from 'express';
import { createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { CONFIG, type Config } from './config.js';

const SCRYPT = { N: 1 << 15, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const SESSION_MS = 12 * 60 * 60 * 1000;

/** Format: scrypt:<salt base64>:<key base64>. No `$`, which Compose would interpolate in .env. */
export function hashPassword(password: string): string {
  const salt = randomBytes(16);
  const key = scryptSync(password, salt, 32, SCRYPT);
  return ['scrypt', salt.toString('base64'), key.toString('base64')].join(':');
}

export function verifyPassword(password: string, stored: string): boolean {
  const [scheme, salt, key] = stored.split(':');
  if (scheme !== 'scrypt' || !salt || !key) return false;
  const expected = Buffer.from(key, 'base64');
  const actual = scryptSync(password, Buffer.from(salt, 'base64'), expected.length, SCRYPT);
  return timingSafeEqual(actual, expected);
}

/** Stateless owner session: `<expiry ms>.<hmac>`. Changing SESSION_SECRET signs everyone out. */
export function issueSession(secret: string, now = Date.now()): { token: string; expiresAt: number } {
  const expiresAt = now + SESSION_MS;
  return { token: `${expiresAt}.${sign(secret, String(expiresAt))}`, expiresAt };
}

export function validSession(secret: string, token: string, now = Date.now()): boolean {
  const [expiry, mac] = token.split('.');
  if (!expiry || !mac || !/^\d+$/.test(expiry) || Number(expiry) <= now) return false;
  return sameSecret(mac, sign(secret, expiry));
}

function sign(secret: string, value: string): string {
  return createHmac('sha256', secret).update(`hellfire-session-v1:${value}`).digest('base64url');
}

/** Constant-time comparison of two strings of any length. */
export function sameSecret(a: string, b: string): boolean {
  const digest = (value: string) => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(a), digest(b));
}

function bearer(request: Request): string {
  const header = request.headers.authorization ?? '';
  return header.startsWith('Bearer ') ? header.slice(7) : '';
}

/** The phone, holding SYNC_TOKEN. */
@Injectable()
export class SyncGuard implements CanActivate {
  constructor(@Inject(CONFIG) private readonly config: Config) {}
  canActivate(context: ExecutionContext): boolean {
    const token = bearer(context.switchToHttp().getRequest<Request>());
    if (!token || !sameSecret(token, this.config.syncToken)) throw new UnauthorizedException();
    return true;
  }
}

/** Phone sync token or an owner session. Used by the encrypted library, not the plaintext mirror. */
@Injectable()
export class ClientGuard implements CanActivate {
  constructor(@Inject(CONFIG) private readonly config: Config) {}
  canActivate(context: ExecutionContext): boolean {
    const token = bearer(context.switchToHttp().getRequest<Request>());
    if (token && (sameSecret(token, this.config.syncToken) || validSession(this.config.sessionSecret, token))) return true;
    throw new UnauthorizedException();
  }
}

/** The owner, signed in through the web app. */
@Injectable()
export class SessionGuard implements CanActivate {
  constructor(@Inject(CONFIG) private readonly config: Config) {}
  canActivate(context: ExecutionContext): boolean {
    const token = bearer(context.switchToHttp().getRequest<Request>());
    if (!token || !validSession(this.config.sessionSecret, token)) throw new UnauthorizedException();
    return true;
  }
}
