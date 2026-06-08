const WINDOWS_PATH = /[A-Za-z]:\\[^\r\n]+/g;
const LINUX_PATH = /\/(?:home|Users)\/[^\r\n]+/g;
const UUID_LIKE = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;
const BLOCK_POS = /BlockPos\{\s*x\s*=\s*-?\d+\s*,\s*y\s*=\s*-?\d+\s*,\s*z\s*=\s*-?\d+\s*}/gi;
const XYZ = /\b[xyz]\s*=\s*-?\d+(?:\s*,\s*[xyz]\s*=\s*-?\d+){2}\b/gi;
const SEED = /\bseed\s*=\s*-?\d+\b/gi;

export function sanitizeText(text) {
  if (typeof text !== 'string' || text.trim() === '') {
    return '';
  }

  return text
    .replace(WINDOWS_PATH, '<path>')
    .replace(LINUX_PATH, '<path>')
    .replace(BLOCK_POS, '<pos>')
    .replace(XYZ, '<pos>')
    .replace(SEED, 'seed=<redacted>')
    .replace(UUID_LIKE, '<uuid>');
}

export function sanitizeValue(value) {
  if (typeof value === 'string') {
    return sanitizeText(value);
  }
  if (Array.isArray(value)) {
    return value.map(item => sanitizeValue(item));
  }
  if (value && typeof value === 'object') {
    const result = {};
    for (const [key, nested] of Object.entries(value)) {
      result[key] = sanitizeValue(nested);
    }
    return result;
  }
  return value;
}

export function sanitizeEvent(event) {
  return sanitizeValue(event);
}
