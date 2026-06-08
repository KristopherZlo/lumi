import test from 'node:test';
import assert from 'node:assert/strict';
import { RateLimiter } from '../src/rate-limiter.js';

test('denies new rate limit keys when the key budget is full', () => {
  const limiter = new RateLimiter({
    windowMs: 1_000,
    maxRequests: 10,
    maxKeys: 2,
    now: () => 0,
  });

  assert.equal(limiter.allow('ip:first'), true);
  assert.equal(limiter.allow('ip:second'), true);
  assert.equal(limiter.allow('ip:third'), false);
});

test('prunes expired rate limit keys before enforcing the key budget', () => {
  let now = 0;
  const limiter = new RateLimiter({
    windowMs: 1_000,
    maxRequests: 10,
    maxKeys: 1,
    now: () => now,
  });

  assert.equal(limiter.allow('ip:first'), true);
  now = 2_000;
  assert.equal(limiter.allow('ip:second'), true);
});
