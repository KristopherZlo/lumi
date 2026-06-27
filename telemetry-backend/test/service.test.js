import test from 'node:test';
import assert from 'node:assert/strict';
import { TelemetryIngestService } from '../src/service.js';
import { RateLimiter } from '../src/rate-limiter.js';

test('accepts a valid batch and stores allowlisted events', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({
    repository,
    rateLimiter: new RateLimiter({ windowMs: 1_000, maxRequests: 10, now: () => 0 }),
    now: () => new Date('2026-06-08T00:00:00Z'),
    maxRequestBytes: 4096,
  });

  const result = await service.handleBatch({
    ip: '127.0.0.1',
    body: JSON.stringify({
      schemaVersion: 1,
      events: [
        {
          id: 'event-a',
          type: 'OPERATION_FAILED',
          occurredAt: '2026-06-08T00:00:00Z',
          installationId: 'install-a',
          environment: { lumiVersion: '1', minecraftVersion: '2', fabricLoaderVersion: '3', javaVersion: '4', osFamily: 'windows', osArch: 'x64', mods: [] },
          fingerprint: 'fp-a',
          payload: {
            operation: 'restore-version',
            stage: 'APPLYING',
            failureClass: 'java.lang.IllegalStateException',
            failureFrame: 'io.github.luma.telemetry.TelemetryService#recordOperationFailed',
            failureTrace: 'io.github.luma.minecraft.world.BlockChangeApplier#apply:42\nio.github.luma.domain.service.RestoreService#restore:77',
            failureCauseChain: 'java.lang.IllegalStateException -> java.lang.IllegalArgumentException',
          },
        },
      ],
    }),
  });

  assert.equal(result.status, 202);
  assert.equal(repository.events.length, 1);
  assert.deepEqual(repository.events[0].payload, {
    operation: 'restore-version',
    stage: 'APPLYING',
    failureClass: 'java.lang.IllegalStateException',
    failureFrame: 'io.github.luma.telemetry.TelemetryService#recordOperationFailed',
    failureTrace: 'io.github.luma.minecraft.world.BlockChangeApplier#apply:42\nio.github.luma.domain.service.RestoreService#restore:77',
    failureCauseChain: 'java.lang.IllegalStateException -> java.lang.IllegalArgumentException',
  });
  assert.equal(repository.lastIp, null);
});

test('rejects invalid schema and oversized payloads', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({ repository, rateLimiter: null, maxRequestBytes: 20 });

  const invalid = await service.handleBatch({ body: '{}', ip: '127.0.0.1' });
  const oversized = await service.handleBatch({
    body: JSON.stringify({ schemaVersion: 1, events: new Array(200).fill({}) }),
    ip: '127.0.0.1',
  });

  assert.equal(invalid.status, 400);
  assert.equal(JSON.parse(invalid.body).error, 'invalid_schema');
  assert.equal(oversized.status, 413);
  assert.equal(JSON.parse(oversized.body).error, 'payload_too_large');
});

test('rejects non-object events before storage', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({ repository, rateLimiter: null, maxRequestBytes: 4096 });

  const result = await service.handleBatch({
    ip: '127.0.0.1',
    body: JSON.stringify({ schemaVersion: 1, events: [null] }),
  });

  assert.equal(result.status, 400);
  assert.equal(JSON.parse(result.body).error, 'invalid_event');
  assert.equal(repository.events.length, 0);
});

test('rejects payload fields outside the telemetry allowlist before storage', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({ repository, rateLimiter: null, maxRequestBytes: 4096 });

  const result = await service.handleBatch({
    ip: '127.0.0.1',
    body: JSON.stringify({
      schemaVersion: 1,
      events: [
        {
          id: 'event-a',
          type: 'OPERATION_FAILED',
          occurredAt: '2026-06-08T00:00:00Z',
          installationId: 'install-a',
          environment: { lumiVersion: '1', minecraftVersion: '2', fabricLoaderVersion: '3', javaVersion: '4', osFamily: 'windows', osArch: 'x64', mods: [] },
          fingerprint: 'fp-a',
          payload: { failure: 'C:\\Users\\Alex\\Castle World' },
        },
      ],
    }),
  });

  assert.equal(result.status, 400);
  assert.equal(JSON.parse(result.body).error, 'invalid_payload');
  assert.equal(repository.events.length, 0);
});

test('rejects event types outside the telemetry allowlist before storage', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({ repository, rateLimiter: null, maxRequestBytes: 4096 });

  const result = await service.handleBatch({
    ip: '127.0.0.1',
    body: JSON.stringify({
      schemaVersion: 1,
      events: [
        {
          id: 'event-a',
          type: 'RAW_LOG_UPLOAD',
          occurredAt: '2026-06-08T00:00:00Z',
          installationId: 'install-a',
          environment: { lumiVersion: '1', minecraftVersion: '2', fabricLoaderVersion: '3', javaVersion: '4', osFamily: 'windows', osArch: 'x64', mods: [] },
          fingerprint: 'fp-a',
          payload: {},
        },
      ],
    }),
  });

  assert.equal(result.status, 400);
  assert.equal(JSON.parse(result.body).error, 'invalid_event');
  assert.equal(repository.events.length, 0);
});

test('accepts the installation counter event', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({ repository, rateLimiter: null, maxRequestBytes: 4096 });

  const result = await service.handleBatch({
    ip: '127.0.0.1',
    body: JSON.stringify({
      schemaVersion: 1,
      events: [
        {
          id: 'event-a',
          type: 'INSTALLATION_SEEN',
          occurredAt: '2026-06-08T00:00:00Z',
          installationId: 'install-a',
          environment: { lumiVersion: '1', minecraftVersion: '2', fabricLoaderVersion: '3', javaVersion: '4', osFamily: 'windows', osArch: 'x64', mods: [] },
          fingerprint: 'fp-a',
          payload: {},
        },
      ],
    }),
  });

  assert.equal(result.status, 202);
  assert.equal(repository.events.length, 1);
  assert.equal(repository.events[0].type, 'INSTALLATION_SEEN');
});

test('rate limits malformed request floods before parsing', async () => {
  const repository = new FakeRepository();
  const service = new TelemetryIngestService({
    repository,
    rateLimiter: new RateLimiter({ windowMs: 1_000, maxRequests: 0, now: () => 0 }),
    maxRequestBytes: 4096,
  });

  const result = await service.handleBatch({ ip: '127.0.0.1', body: '{not-json' });

  assert.equal(result.status, 429);
  assert.equal(JSON.parse(result.body).error, 'rate_limited');
  assert.equal(repository.events.length, 0);
});

class FakeRepository {
  constructor() {
    this.events = [];
    this.lastIp = null;
  }

  async insertEvents(events) {
    this.events.push(...events);
  }

  async deleteEventsBefore() {
    return undefined;
  }
}
