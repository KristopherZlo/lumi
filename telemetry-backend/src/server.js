import http from 'node:http';
import { DEFAULT_RATE_LIMIT_MAX_REQUESTS, DEFAULT_RATE_LIMIT_WINDOW_MS } from './constants.js';
import { RateLimiter } from './rate-limiter.js';
import { TelemetryIngestService } from './service.js';
import { PgTelemetryRepository } from './pg-repository.js';

export function createTelemetryServer({
  repository = new PgTelemetryRepository({ connectionString: process.env.DATABASE_URL }),
  rateLimiter = new RateLimiter({
    windowMs: DEFAULT_RATE_LIMIT_WINDOW_MS,
    maxRequests: DEFAULT_RATE_LIMIT_MAX_REQUESTS,
  }),
  now = () => new Date(),
  maxRequestBytes,
} = {}) {
  const service = new TelemetryIngestService({ repository, rateLimiter, now, maxRequestBytes });

  return http.createServer(async (req, res) => {
    if (req.method !== 'POST' || req.url !== '/v1/events/batch') {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'not_found' }));
      return;
    }

    const chunks = [];
    for await (const chunk of req) {
      chunks.push(Buffer.from(chunk));
    }
    const body = Buffer.concat(chunks).toString('utf8');
    const result = await service.handleBatch({
      body,
      ip: req.socket.remoteAddress ?? '',
    });
    res.writeHead(result.status, { 'content-type': 'application/json' });
    res.end(result.body);
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 8787);
  createTelemetryServer().listen(port, () => {
    console.log(`Lumi telemetry backend listening on ${port}`);
  });
}
