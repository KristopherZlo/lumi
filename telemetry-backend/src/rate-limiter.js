export class RateLimiter {
  constructor({ windowMs, maxRequests, maxKeys = Number.POSITIVE_INFINITY, now = () => Date.now() }) {
    this.windowMs = windowMs;
    this.maxRequests = maxRequests;
    this.maxKeys = maxKeys;
    this.now = now;
    this.bucket = new Map();
  }

  allow(key) {
    const now = this.now();
    const windowStart = now - this.windowMs;
    this.prune(windowStart);
    const existing = this.bucket.get(key);
    if (!existing && this.bucket.size >= this.maxKeys) {
      return false;
    }
    const timestamps = (existing ?? []).filter(timestamp => timestamp >= windowStart);
    timestamps.push(now);
    this.bucket.set(key, timestamps);
    return timestamps.length <= this.maxRequests;
  }

  prune(windowStart) {
    for (const [key, timestamps] of this.bucket.entries()) {
      const active = timestamps.filter(timestamp => timestamp >= windowStart);
      if (active.length === 0) {
        this.bucket.delete(key);
      } else {
        this.bucket.set(key, active);
      }
    }
  }
}
