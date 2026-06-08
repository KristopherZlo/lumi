export class RetentionScheduler {
  constructor(job, {
    intervalMs = 24 * 60 * 60 * 1000,
    setIntervalFn = globalThis.setInterval,
    clearIntervalFn = globalThis.clearInterval,
    logger = console,
  } = {}) {
    this.job = job;
    this.intervalMs = intervalMs;
    this.setIntervalFn = setIntervalFn;
    this.clearIntervalFn = clearIntervalFn;
    this.logger = logger;
    this.intervalHandle = null;
    this.running = false;
    this.startPromise = null;
  }

  start() {
    if (this.running) {
      return this.startPromise ?? Promise.resolve();
    }
    this.running = true;
    this.startPromise = this.runOnce()
      .catch(error => {
        this.logger?.warn?.('Lumi telemetry retention failed during startup', error);
      })
      .finally(() => {
        if (!this.running) {
          return;
        }
        this.intervalHandle = this.setIntervalFn(() => {
          void this.runOnce().catch(error => {
            this.logger?.warn?.('Lumi telemetry retention failed during schedule', error);
          });
        }, this.intervalMs);
      });
    return this.startPromise;
  }

  stop() {
    this.running = false;
    if (this.intervalHandle != null) {
      this.clearIntervalFn(this.intervalHandle);
      this.intervalHandle = null;
    }
  }

  async runOnce() {
    if (!this.job || typeof this.job.run !== 'function') {
      return;
    }
    await this.job.run();
  }
}
