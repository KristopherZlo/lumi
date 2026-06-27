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
import { AdminDashboard } from './admin-dashboard.js';
import { BasicAuth, adminAuthFromEnv } from './admin-auth.js';

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
  admin = adminAuthFromEnv(),
  trustProxy = process.env.TRUST_PROXY === '1',
} = {}) {
  const service = new TelemetryIngestService({ repository, rateLimiter, now, maxRequestBytes });
  const scheduler = retentionScheduler ?? new RetentionScheduler(
    new RetentionJob(repository, now, DEFAULT_RETENTION_DAYS)
  );
  const adminAuth = normalizeAdminAuth(admin);
  const dashboard = adminAuth ? new AdminDashboard(repository) : null;
  const server = http.createServer(async (req, res) => {
    if (req.method === 'GET' && (req.url === '/' || req.url === '/admin')) {
      await handleAdminRequest(req, res, adminAuth, dashboard);
      return;
    }

    if (req.method !== 'POST' || req.url !== '/v1/events/batch') {
      writeJson(res, 404, { error: 'not_found' });
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
        ip: requestIp(req, trustProxy),
      });
      res.writeHead(result.status, jsonHeaders());
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
  res.writeHead(status, jsonHeaders());
  res.end(JSON.stringify(body));
}

async function handleAdminRequest(req, res, adminAuth, dashboard) {
  if (!adminAuth || !dashboard) {
    writeJson(res, 404, { error: 'not_found' });
    return;
  }
  if (!adminAuth.verifyHeader(req.headers.authorization)) {
    res.writeHead(401, {
      ...securityHeaders(),
      'content-type': 'application/json',
      'www-authenticate': 'Basic realm="Lumi telemetry", charset="UTF-8"',
    });
    res.end(JSON.stringify({ error: 'unauthorized' }));
    return;
  }
  const html = await dashboard.render();
  res.writeHead(200, {
    ...securityHeaders(),
    'content-type': 'text/html; charset=utf-8',
  });
  res.end(html);
}

function normalizeAdminAuth(admin) {
  if (!admin) {
    return null;
  }
  if (typeof admin.verifyHeader === 'function') {
    return admin;
  }
  return new BasicAuth(admin);
}

function requestIp(req, trustProxy) {
  if (trustProxy) {
    const forwardedFor = String(req.headers['x-forwarded-for'] ?? '').split(',')[0].trim();
    if (forwardedFor) {
      return forwardedFor.slice(0, 128);
    }
  }
  return req.socket.remoteAddress ?? '';
}

function jsonHeaders() {
  return {
    ...securityHeaders(),
    'content-type': 'application/json',
  };
}

function securityHeaders() {
  return {
    'cache-control': 'no-store',
    'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    'referrer-policy': 'no-referrer',
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY',
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 8787);
  const host = process.env.HOST ?? '127.0.0.1';
  createTelemetryServer().listen(port, host, () => {
    console.log(`Lumi telemetry backend listening on ${host}:${port}`);
  });
}
