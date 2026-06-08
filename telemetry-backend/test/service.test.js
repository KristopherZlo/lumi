import test from 'node:test';
import assert from 'node:assert/strict';
import { TelemetryIngestService } from '../src/service.js';
import { RateLimiter } from '../src/rate-limiter.js';

test('accepts a valid batch and sanitizes stored events', async () => {
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
          type: 'operation_failed',
          occurredAt: '2026-06-08T00:00:00Z',
          installationId: 'install-a',
          environment: { lumiVersion: '1', minecraftVersion: '2', fabricLoaderVersion: '3', javaVersion: '4', osFamily: 'windows', osArch: 'x64', mods: [] },
          fingerprint: 'fp-a',
          payload: { failure: 'C:\\Users\\Alex\\world' },
        },
      ],
    }),
  });

  assert.equal(result.status, 202);
  assert.equal(repository.events.length, 1);
  assert.equal(repository.events[0].payload.failure, '<path>');
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
