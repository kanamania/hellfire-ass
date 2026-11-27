import { BadRequestException, Body, Controller, Get, HttpCode, Injectable, Post, Query, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { SessionGuard, SyncGuard } from './auth.js';
import { Db } from './db.js';

/**
 * Web commands (audit HF-061): the browser asks, the phone decides. The phone pulls pending commands on its next sync,
 * applies those that are still valid with its own rules and audit, and reports APPLIED or STALE. Only reversible
 * changes are offered here; posting money and sending messages stay on the phone.
 */
export const COMMAND_KINDS = ['task.done', 'task.snooze', 'candidate.dismiss'] as const;
const KIND_SET = new Set<string>(COMMAND_KINDS);
const RESULTS = new Set(['APPLIED', 'STALE', 'REJECTED']);
const PULL_LIMIT = 200;

function parseCommand(body: unknown): { kind: string; target: string } {
  const { kind, target } = (body ?? {}) as { kind?: unknown; target?: unknown };
  if (typeof kind !== 'string' || !KIND_SET.has(kind)) throw new BadRequestException('unsupported command');
  if (typeof target !== 'string' || !/^[A-Za-z0-9:_-]{6,128}$/.test(target)) throw new BadRequestException('invalid target');
  return { kind, target };
}

@Injectable()
export class CommandService {
  constructor(private readonly db: Db) {}

  async add(body: unknown) {
    const command = parseCommand(body);
    const { rows } = await this.db.pool.query<{ seq: string }>(
      "INSERT INTO command (at, kind, target, status) VALUES ($1, $2, $3, 'PENDING') RETURNING seq", [Date.now(), command.kind, command.target]);
    return { seq: Number(rows[0].seq), status: 'PENDING' };
  }

  /** Recent commands and their outcome, for the site to show "waiting for the phone" or what happened. */
  async recent() {
    const { rows } = await this.db.pool.query('SELECT seq, at, kind, target, status, result FROM command ORDER BY seq DESC LIMIT 100');
    return { commands: rows.map((row) => ({ ...row, seq: Number(row.seq), at: Number(row.at) })) };
  }

  async pending(after: unknown) {
    const cursor = after === undefined || after === '' ? 0 : typeof after === 'string' && /^\d{1,16}$/.test(after) ? Number(after) : null;
    if (cursor === null) throw new BadRequestException('invalid after');
    const { rows } = await this.db.pool.query("SELECT seq, at, kind, target FROM command WHERE status = 'PENDING' AND seq > $1 ORDER BY seq LIMIT $2", [cursor, PULL_LIMIT]);
    return { commands: rows.map((row) => ({ ...row, seq: Number(row.seq), at: Number(row.at) })) };
  }

  async results(body: unknown) {
    const list = (body as { results?: unknown } | null)?.results;
    if (!Array.isArray(list) || list.length > PULL_LIMIT) throw new BadRequestException('results must be a list');
    let updated = 0;
    for (const item of list) {
      const { seq, status, result } = (item ?? {}) as { seq?: unknown; status?: unknown; result?: unknown };
      if (!Number.isSafeInteger(seq) || typeof status !== 'string' || !RESULTS.has(status)) throw new BadRequestException('invalid result');
      const note = typeof result === 'string' ? result.slice(0, 200) : null;
      const { rowCount } = await this.db.pool.query("UPDATE command SET status = $2, result = $3 WHERE seq = $1 AND status = 'PENDING'", [seq, status, note]);
      updated += rowCount ?? 0;
    }
    return { updated };
  }
}

@Controller('api/commands')
@UseGuards(SessionGuard)
export class CommandController {
  constructor(private readonly commands: CommandService) {}
  @Post() @HttpCode(200) @Throttle({ default: { limit: 60, ttl: 60_000 } }) add(@Body() body: unknown) { return this.commands.add(body); }
  @Get() recent() { return this.commands.recent(); }
}

@Controller('api/sync/commands')
@UseGuards(SyncGuard)
export class CommandSyncController {
  constructor(private readonly commands: CommandService) {}
  @Get() pending(@Query('after') after: unknown) { return this.commands.pending(after); }
  @Post('results') @HttpCode(200) results(@Body() body: unknown) { return this.commands.results(body); }
}
