import http from 'node:http';
import {
  DEFAULT_RATE_LIMIT_MAX_KEYS,
  DEFAULT_RATE_LIMIT_MAX_REQUESTS,
  DEFAULT_RATE_LIMIT_WINDOW_MS,
  DEFAULT_RETENTION_DAYS,
  MAX_REQUEST_BYTES,
} from './constants.js';
import { RateLimiter } from './rate-limiter.js';
import { TelemetryIngestService } from './service.js';
import { PgTelemetryRepository } from './pg-repository.js';
import { RetentionJob } from './retention-job.js';
import { RetentionScheduler } from './retention-scheduler.js';

export function createTelemetryServer({
  repository = new PgTelemetryRepository({ connectionString: process.env.DATABASE_URL }),
  rateLimiter = new RateLimiter({
    windowMs: DEFAULT_RATE_LIMIT_WINDOW_MS,
    maxRequests: DEFAULT_RATE_LIMIT_MAX_REQUESTS,
    maxKeys: DEFAULT_RATE_LIMIT_MAX_KEYS,
  }),
  now = () => new Date(),
  maxRequestBytes = MAX_REQUEST_BYTES,
  retentionScheduler = null,
} = {}) {
  const service = new TelemetryIngestService({ repository, rateLimiter, now, maxRequestBytes });
  const scheduler = retentionScheduler ?? new RetentionScheduler(
    new RetentionJob(repository, now, DEFAULT_RETENTION_DAYS)
  );
  const server = http.createServer(async (req, res) => {
    if (req.method !== 'POST' || req.url !== '/v1/events/batch') {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'not_found' }));
      return;
    }

    try {
      const body = await readRequestBody(req, maxRequestBytes);
      if (!body.ok) {
        writeJson(res, body.status, { error: body.error });
        return;
      }
      const result = await service.handleBatch({
        body: body.value,
        ip: req.socket.remoteAddress ?? '',
      });
      res.writeHead(result.status, { 'content-type': 'application/json' });
      res.end(result.body);
    } catch (error) {
      writeJson(res, 500, { error: 'internal_error' });
    }
  });

  server.on('listening', () => {
    void scheduler.start();
  });
  server.on('close', () => {
    scheduler.stop();
  });

  return server;
}

async function readRequestBody(req, maxRequestBytes) {
  const contentLength = Number(req.headers['content-length'] ?? '0');
  if (Number.isFinite(contentLength) && contentLength > maxRequestBytes) {
    req.resume();
    return { ok: false, status: 413, error: 'payload_too_large' };
  }

  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of req) {
    totalBytes += chunk.length;
    if (totalBytes > maxRequestBytes) {
      req.destroy();
      return { ok: false, status: 413, error: 'payload_too_large' };
    }
    chunks.push(Buffer.from(chunk));
  }
  return { ok: true, value: Buffer.concat(chunks, totalBytes).toString('utf8') };
}

function writeJson(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 8787);
  createTelemetryServer().listen(port, () => {
    console.log(`Lumi telemetry backend listening on ${port}`);
  });
}
