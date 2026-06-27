import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createTelemetryServer } from '../src/server.js';
import { RateLimiter } from '../src/rate-limiter.js';
import { hashPassword } from '../src/admin-auth.js';

test('returns json problem response when repository insert fails', { timeout: 2_000 }, async () => {
  const server = createTelemetryServer({
    repository: {
      async insertEvents() {
        throw new Error('database unavailable');
      },
      async deleteEventsBefore() {
        return undefined;
      },
    },
    rateLimiter: null,
    maxRequestBytes: 4096,
  });

  try {
    const response = await post(server, JSON.stringify({
      schemaVersion: 1,
      events: [
        {
          id: 'event-a',
          type: 'operation_failed',
          occurredAt: '2026-06-08T00:00:00Z',
          installationId: 'install-a',
          environment: {
            lumiVersion: '1',
            minecraftVersion: '2',
            fabricLoaderVersion: '3',
            javaVersion: '4',
            osFamily: 'windows',
            osArch: 'x64',
            mods: [],
          },
          fingerprint: 'fp-a',
          payload: {},
        },
      ],
    }));

    assert.equal(response.status, 500);
    assert.equal(JSON.parse(response.body).error, 'internal_error');
  } finally {
    server.close();
  }
});

test('rejects oversized request bodies at the server boundary', async () => {
  const repository = new FakeRepository();
  const server = createTelemetryServer({
    repository,
    rateLimiter: null,
    maxRequestBytes: 8,
  });

  try {
    const response = await post(server, '{"schemaVersion":1,"events":[]}');

    assert.equal(response.status, 413);
    assert.equal(JSON.parse(response.body).error, 'payload_too_large');
    assert.equal(repository.insertCount, 0);
  } finally {
    server.close();
  }
});

test('starts and stops retention scheduler with server lifecycle', async () => {
  const retentionScheduler = new FakeRetentionScheduler();
  const server = createTelemetryServer({
    repository: new FakeRepository(),
    rateLimiter: null,
    retentionScheduler,
  });

  try {
    await listen(server);
    assert.equal(retentionScheduler.startCount, 1);
  } finally {
    await close(server);
  }

  assert.equal(retentionScheduler.stopCount, 1);
});

test('protects the admin dashboard with basic auth', async () => {
  const server = createTelemetryServer({
    repository: new FakeRepository(),
    rateLimiter: null,
    admin: {
      username: 'owner',
      passwordHash: hashPassword('correct-password', Buffer.alloc(16, 1)),
    },
  });

  try {
    const unauthenticated = await get(server, '/');
    const rejected = await get(server, '/', {
      authorization: basicAuth('owner', 'wrong-password'),
    });

    assert.equal(unauthenticated.status, 401);
    assert.match(unauthenticated.headers['www-authenticate'], /Basic realm="Lumi telemetry"/);
    assert.equal(unauthenticated.headers['x-content-type-options'], 'nosniff');
    assert.equal(rejected.status, 401);
  } finally {
    await close(server);
  }
});

test('renders escaped aggregate stats without raw event data', async () => {
  const server = createTelemetryServer({
    repository: new FakeRepository({
      dashboardStats: {
        summary: {
          totalEvents: 3,
          distinctInstallations: 2,
          firstReceivedAt: '2026-06-01T00:00:00.000Z',
          lastReceivedAt: '2026-06-08T00:00:00.000Z',
        },
        eventTypes: [{ label: 'operation_failed<script>', count: 2 }],
        lumiVersions: [{ label: '1.0.0', count: 3 }],
        dailyEvents: [{ label: '2026-06-08', count: 3 }],
      },
    }),
    rateLimiter: null,
    admin: {
      username: 'owner',
      passwordHash: hashPassword('correct-password', Buffer.alloc(16, 2)),
    },
  });

  try {
    const response = await get(server, '/admin', {
      authorization: basicAuth('owner', 'correct-password'),
    });

    assert.equal(response.status, 200);
    assert.equal(response.headers['x-frame-options'], 'DENY');
    assert.match(response.body, /Total events/);
    assert.match(response.body, /operation_failed&lt;script&gt;/);
    assert.doesNotMatch(response.body, /operation_failed<script>/);
    assert.doesNotMatch(response.body, /installation-a/);
  } finally {
    await close(server);
  }
});

test('uses forwarded client ip for rate limiting behind a trusted proxy', async () => {
  const repository = new FakeRepository();
  const server = createTelemetryServer({
    repository,
    rateLimiter: new RateLimiter({ windowMs: 1_000, maxRequests: 1, now: () => 0 }),
    maxRequestBytes: 4096,
    trustProxy: true,
  });
  const body = JSON.stringify({
    schemaVersion: 1,
    events: [validEvent('event-a')],
  });

  try {
    const first = await post(server, body, { 'x-forwarded-for': '198.51.100.1' });
    const second = await post(server, body, { 'x-forwarded-for': '203.0.113.2' });
    const repeated = await post(server, body, { 'x-forwarded-for': '198.51.100.1' });

    assert.equal(first.status, 202);
    assert.equal(second.status, 202);
    assert.equal(repeated.status, 429);
    assert.equal(repository.insertCount, 2);
  } finally {
    await close(server);
  }
});

function post(server, body, headers = {}) {
  return request(server, {
    path: '/v1/events/batch',
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'content-length': Buffer.byteLength(body),
      ...headers,
    },
  }, body);
}

function get(server, path, headers = {}) {
  return request(server, { path, method: 'GET', headers });
}

function request(server, options, body = '') {
  return new Promise((resolve, reject) => {
    const send = () => {
      const address = server.address();
      const request = http.request({
        hostname: '127.0.0.1',
        port: address.port,
        path: options.path,
        method: options.method,
        agent: false,
        headers: options.headers,
      }, response => {
        const chunks = [];
        response.on('data', chunk => chunks.push(Buffer.from(chunk)));
        response.on('end', () => resolve({
          status: response.statusCode,
          headers: response.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        }));
      });
      const timeout = setTimeout(() => {
        request.destroy(new Error('request timed out'));
      }, 500);
      request.on('error', error => {
        clearTimeout(timeout);
        reject(error);
      });
      request.on('close', () => clearTimeout(timeout));
      request.end(body);
    };

    if (server.listening) {
      send();
    } else {
      server.listen(0, '127.0.0.1', send);
    }
  });
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.listen(0, '127.0.0.1', resolve);
    server.on('error', reject);
  });
}

function close(server) {
  return new Promise(resolve => server.close(resolve));
}

class FakeRepository {
  constructor({ dashboardStats = null } = {}) {
    this.insertCount = 0;
    this.dashboardStatsValue = dashboardStats ?? {
      summary: {
        totalEvents: 0,
        distinctInstallations: 0,
        firstReceivedAt: null,
        lastReceivedAt: null,
      },
      eventTypes: [],
      lumiVersions: [],
      dailyEvents: [],
    };
  }

  async insertEvents() {
    this.insertCount += 1;
  }

  async deleteEventsBefore() {
    return undefined;
  }

  async dashboardStats() {
    return this.dashboardStatsValue;
  }
}

class FakeRetentionScheduler {
  constructor() {
    this.startCount = 0;
    this.stopCount = 0;
  }

  start() {
    this.startCount += 1;
  }

  stop() {
    this.stopCount += 1;
  }
}

function validEvent(id) {
  return {
    id,
    type: 'operation_failed',
    occurredAt: '2026-06-08T00:00:00Z',
    installationId: 'install-a',
    environment: {
      lumiVersion: '1',
      minecraftVersion: '2',
      fabricLoaderVersion: '3',
      javaVersion: '4',
      osFamily: 'windows',
      osArch: 'x64',
      mods: [],
    },
    fingerprint: 'fp-a',
    payload: {},
  };
}

function basicAuth(username, password) {
  return `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`;
}
