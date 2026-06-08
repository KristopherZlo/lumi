import test from 'node:test';
import assert from 'node:assert/strict';
import { RetentionJob } from '../src/retention-job.js';

test('retention job deletes raw events older than ninety days', async () => {
  const repository = new FakeRepository();
  const job = new RetentionJob(repository, () => new Date('2026-06-08T00:00:00Z'));

  const cutoff = await job.run();

  assert.equal(repository.cutoff.toISOString(), '2026-03-10T00:00:00.000Z');
  assert.equal(cutoff.toISOString(), '2026-03-10T00:00:00.000Z');
});

class FakeRepository {
  async deleteEventsBefore(cutoff) {
    this.cutoff = cutoff;
  }
}
