import 'reflect-metadata';
import { Module, type DynamicModule } from '@nestjs/common';
import { APP_GUARD, NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { CONFIG, type Config } from './config.js';
import { Db } from './db.js';
import { LibraryController, LibraryService } from './library.js';
import { SyncController, SyncService } from './sync.js';
import { ViewController, ViewService } from './view.js';

@Module({})
class AppModule {
  static with(config: Config): DynamicModule {
    return {
      module: AppModule,
      imports: [ThrottlerModule.forRoot([{ ttl: 60_000, limit: 600 }])],
      controllers: [SyncController, ViewController, LibraryController],
      providers: [{ provide: CONFIG, useValue: config }, { provide: APP_GUARD, useClass: ThrottlerGuard }, Db, SyncService, ViewService, LibraryService],
    };
  }
}

export async function createApp(config: Config): Promise<NestExpressApplication> {
  const app = await NestFactory.create<NestExpressApplication>(AppModule.with(config), { logger: ['error', 'warn'] });
  // Phone batches are capped at 500 rows; transcripts and message bodies make them larger than the 100 kB default.
  app.useBodyParser('json', { limit: '4mb' });
  app.enableShutdownHooks();
  return app;
}
