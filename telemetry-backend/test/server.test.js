import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createTelemetryServer } from '../src/server.js';

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

function post(server, body) {
  return new Promise((resolve, reject) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const request = http.request({
        hostname: '127.0.0.1',
        port: address.port,
        path: '/v1/events/batch',
        method: 'POST',
        agent: false,
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(body),
        },
      }, response => {
        const chunks = [];
        response.on('data', chunk => chunks.push(Buffer.from(chunk)));
        response.on('end', () => resolve({
          status: response.statusCode,
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
    });
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
  constructor() {
    this.insertCount = 0;
  }

  async insertEvents() {
    this.insertCount += 1;
  }

  async deleteEventsBefore() {
    return undefined;
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
