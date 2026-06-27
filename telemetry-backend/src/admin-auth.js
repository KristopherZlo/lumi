import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';

const HASH_PREFIX = 'scrypt';
const KEY_BYTES = 32;

export class BasicAuth {
  constructor({ username, passwordHash }) {
    this.username = username ?? '';
    this.passwordHash = passwordHash ?? '';
  }

  verifyHeader(header) {
    const credentials = parseBasicHeader(header);
    if (!credentials) {
      return false;
    }
    return safeEqual(credentials.username, this.username)
      && verifyPassword(credentials.password, this.passwordHash);
  }
}

export function adminAuthFromEnv(env = process.env) {
  if (!env.ADMIN_USERNAME || !env.ADMIN_PASSWORD_HASH) {
    return null;
  }
  return new BasicAuth({
    username: env.ADMIN_USERNAME,
    passwordHash: env.ADMIN_PASSWORD_HASH,
  });
}

export function hashPassword(password, salt = randomBytes(16)) {
  const saltBuffer = Buffer.isBuffer(salt) ? salt : Buffer.from(salt, 'base64url');
  const hash = scryptSync(String(password), saltBuffer, KEY_BYTES);
  return `${HASH_PREFIX}$${saltBuffer.toString('base64url')}$${hash.toString('base64url')}`;
}

export function verifyPassword(password, encoded) {
  const parts = String(encoded ?? '').split('$');
  if (parts.length !== 3 || parts[0] !== HASH_PREFIX) {
    return false;
  }
  try {
    const salt = Buffer.from(parts[1], 'base64url');
    const expected = Buffer.from(parts[2], 'base64url');
    const actual = scryptSync(String(password), salt, expected.length);
    return expected.length > 0 && timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

function parseBasicHeader(header) {
  if (typeof header !== 'string' || !header.startsWith('Basic ')) {
    return null;
  }
  try {
    const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
    const separator = decoded.indexOf(':');
    if (separator <= 0) {
      return null;
    }
    return {
      username: decoded.slice(0, separator),
      password: decoded.slice(separator + 1),
    };
  } catch {
    return null;
  }
}

function safeEqual(left, right) {
  const leftBytes = Buffer.from(String(left));
  const rightBytes = Buffer.from(String(right));
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}
