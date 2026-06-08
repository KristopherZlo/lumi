export class RateLimiter {
  constructor({ windowMs, maxRequests, now = () => Date.now() }) {
    this.windowMs = windowMs;
    this.maxRequests = maxRequests;
    this.now = now;
    this.bucket = new Map();
  }

  allow(key) {
    const now = this.now();
    const windowStart = now - this.windowMs;
    const timestamps = (this.bucket.get(key) ?? []).filter(timestamp => timestamp >= windowStart);
    timestamps.push(now);
    this.bucket.set(key, timestamps);
    return timestamps.length <= this.maxRequests;
  }
}
