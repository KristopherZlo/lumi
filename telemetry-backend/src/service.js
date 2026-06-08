import { MAX_REQUEST_BYTES, TELEMETRY_SCHEMA_VERSION } from './constants.js';
import { parseBatch } from './validation.js';

export class TelemetryIngestService {
  constructor({
    repository,
    rateLimiter,
    now = () => new Date(),
    maxRequestBytes = MAX_REQUEST_BYTES,
  }) {
    this.repository = repository;
    this.rateLimiter = rateLimiter;
    this.now = now;
    this.maxRequestBytes = maxRequestBytes;
  }

  async handleBatch({ body, ip = '' }) {
    if (Buffer.byteLength(body ?? '', 'utf8') > this.maxRequestBytes) {
      return this.problem(413, 'payload_too_large');
    }

    if (this.rateLimiter && !this.rateLimiter.allow(`ip:${ip}`)) {
      return this.problem(429, 'rate_limited');
    }

    const parsed = parseBatch(body ?? '');
    if (!parsed.ok) {
      return this.problem(parsed.status, parsed.error);
    }

    const events = parsed.batch.events.map(event => ({
      ...event,
      schemaVersion: TELEMETRY_SCHEMA_VERSION,
      receivedAt: this.now().toISOString(),
    }));

    await this.repository.insertEvents(events);
    return {
      status: 202,
      body: JSON.stringify({ accepted: events.length }),
    };
  }

  problem(status, error) {
    return {
      status,
      body: JSON.stringify({ error }),
    };
  }
}
