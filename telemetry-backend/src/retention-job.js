import { DEFAULT_RETENTION_DAYS } from './constants.js';

export class RetentionJob {
  constructor(repository, now = () => new Date(), retentionDays = DEFAULT_RETENTION_DAYS) {
    this.repository = repository;
    this.now = now;
    this.retentionDays = retentionDays;
  }

  async run() {
    const cutoff = new Date(this.now().getTime() - this.retentionDays * 24 * 60 * 60 * 1000);
    await this.repository.deleteEventsBefore(cutoff);
    return cutoff;
  }
}
