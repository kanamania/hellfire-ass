import { CanActivate, ExecutionContext, Inject, Injectable, UnauthorizedException } from '@nestjs/common';
import type { Request } from 'express';
import { createHash, createHmac, randomBytes, scrypt, scryptSync, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { ThrottlerGuard } from '@nestjs/throttler';
import { CONFIG, type Config } from './config.js';
import { Db } from './db.js';

const SCRYPT = { N: 1 << 15, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const SESSION_MS = 12 * 60 * 60 * 1000;

/** Format: scrypt:<salt base64>:<key base64>. No `$`, which Compose would interpolate in .env. */
export function hashPassword(password: string): string {
  const salt = randomBytes(16);
  const key = scryptSync(password, salt, 32, SCRYPT);
  return ['scrypt', salt.toString('base64'), key.toString('base64')].join(':');
}

const scryptAsync = promisify(scrypt) as (password: string, salt: Buffer, length: number, options: typeof SCRYPT) => Promise<Buffer>;

/** Async so a login attempt does not block the event loop for other requests. */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, salt, key] = stored.split(':');
  if (scheme !== 'scrypt' || !salt || !key) return false;
  const expected = Buffer.from(key, 'base64');
  const actual = await scryptAsync(password, Buffer.from(salt, 'base64'), expected.length, SCRYPT);
  return timingSafeEqual(actual, expected);
}

/** Stateless owner session: `<expiry ms>.<hmac>`. Changing SESSION_SECRET signs everyone out. */
export function issueSession(secret: string, now = Date.now()): { token: string; expiresAt: number } {
  const expiresAt = now + SESSION_MS;
  return { token: `${expiresAt}.${sign(secret, String(expiresAt))}`, expiresAt };
}

export function validSession(secret: string, token: string, now = Date.now(), issuedAfter = 0): boolean {
  const [expiry, mac] = token.split('.');
  if (!expiry || !mac || !/^\d+$/.test(expiry) || Number(expiry) <= now) return false;
  if (Number(expiry) - SESSION_MS <= issuedAfter) return false;
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
  constructor(@Inject(CONFIG) private readonly config: Config, private readonly db: Db) {}
  canActivate(context: ExecutionContext): boolean {
    const token = bearer(context.switchToHttp().getRequest<Request>());
    if (token && (sameSecret(token, this.config.syncToken) || validSession(this.config.sessionSecret, token, Date.now(), this.db.sessionsAfter))) return true;
    throw new UnauthorizedException();
  }
}

/** The owner, signed in through the web app. */
@Injectable()
export class SessionGuard implements CanActivate {
  constructor(@Inject(CONFIG) private readonly config: Config, private readonly db: Db) {}
  canActivate(context: ExecutionContext): boolean {
    const token = bearer(context.switchToHttp().getRequest<Request>());
    if (!token || !validSession(this.config.sessionSecret, token, Date.now(), this.db.sessionsAfter)) throw new UnauthorizedException();
    return true;
  }
}

/**
 * Rate limits per visitor. The API listens on loopback only, so its callers are the website and nginx-fronted
 * rewrites; both pass nginx's X-Real-IP. Without it every visitor would share the website's address.
 */
@Injectable()
export class VisitorThrottlerGuard extends ThrottlerGuard {
  protected override async getTracker(request: Record<string, any>): Promise<string> {
    const visitor = request.headers?.['x-real-ip'];
    return typeof visitor === 'string' && visitor ? visitor : request.ip;
  }
}
