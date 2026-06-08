import test from 'node:test';
import assert from 'node:assert/strict';
import { RetentionScheduler } from '../src/retention-scheduler.js';

test('runs retention immediately and schedules the next run', async () => {
  const job = new FakeJob();
  const scheduler = new RetentionScheduler(job, {
    intervalMs: 1_000,
    setIntervalFn: (callback, intervalMs) => {
      scheduler.capturedIntervalMs = intervalMs;
      scheduler.capturedCallback = callback;
      return { id: 'timer-a' };
    },
    clearIntervalFn: handle => {
      scheduler.clearedHandle = handle;
    },
  });

  await scheduler.start();

  assert.equal(job.runCount, 1);
  assert.equal(scheduler.capturedIntervalMs, 1_000);
  assert.equal(scheduler.clearedHandle, undefined);

  scheduler.stop();

  assert.equal(scheduler.clearedHandle.id, 'timer-a');
});

class FakeJob {
  constructor() {
    this.runCount = 0;
  }

  async run() {
    this.runCount += 1;
  }
}
