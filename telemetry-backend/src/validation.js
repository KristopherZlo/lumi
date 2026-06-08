import { MAX_BATCH_EVENTS, TELEMETRY_SCHEMA_VERSION } from './constants.js';

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
  return { ok: true, batch: parsed };
}
