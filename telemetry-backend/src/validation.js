import { MAX_BATCH_EVENTS, MAX_EVENT_STRING_BYTES, TELEMETRY_SCHEMA_VERSION } from './constants.js';

export function parseBatch(body) {
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    return { ok: false, status: 400, error: 'invalid_json' };
  }

  if (!parsed || parsed.schemaVersion !== TELEMETRY_SCHEMA_VERSION || !Array.isArray(parsed.events)) {
    return { ok: false, status: 400, error: 'invalid_schema' };
  }
  if (parsed.events.length > MAX_BATCH_EVENTS) {
    return { ok: false, status: 413, error: 'batch_too_large' };
  }
  for (const event of parsed.events) {
    const validation = validateEvent(event);
    if (!validation.ok) {
      return validation;
    }
  }
  return { ok: true, batch: parsed };
}

function validateEvent(event) {
  if (!plainObject(event)) {
    return invalid('invalid_event');
  }
  if (event.environment !== undefined && !plainObject(event.environment)) {
    return invalid('invalid_environment');
  }
  if (event.environment?.mods !== undefined && !Array.isArray(event.environment.mods)) {
    return invalid('invalid_environment');
  }
  if (event.payload !== undefined && !plainObject(event.payload)) {
    return invalid('invalid_payload');
  }
  if (!validJsonShape(event, 0)) {
    return invalid('invalid_event');
  }
  return { ok: true };
}

function validJsonShape(value, depth) {
  if (depth > 8) {
    return false;
  }
  if (typeof value === 'string') {
    return Buffer.byteLength(value, 'utf8') <= MAX_EVENT_STRING_BYTES;
  }
  if (Array.isArray(value)) {
    return value.length <= MAX_BATCH_EVENTS && value.every(item => validJsonShape(item, depth + 1));
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).length <= 64
      && Object.values(value).every(nested => validJsonShape(nested, depth + 1));
  }
  return value === null || ['boolean', 'number'].includes(typeof value);
}

function plainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function invalid(error) {
  return { ok: false, status: 400, error };
}
