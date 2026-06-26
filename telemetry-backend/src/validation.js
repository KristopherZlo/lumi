import { MAX_BATCH_EVENTS, MAX_EVENT_STRING_BYTES, TELEMETRY_SCHEMA_VERSION } from './constants.js';

const EVENT_KEYS = new Set([
  'id',
  'schemaVersion',
  'type',
  'occurredAt',
  'installationId',
  'environment',
  'fingerprint',
  'payload',
]);
const ENVIRONMENT_KEYS = new Set([
  'lumiVersion',
  'minecraftVersion',
  'fabricLoaderVersion',
  'javaVersion',
  'osFamily',
  'osArch',
  'mods',
]);
const MOD_KEYS = new Set(['id', 'version']);
const PAYLOAD_KEYS = new Set([
  'action',
  'statusKey',
  'operation',
  'stage',
  'completedUnits',
  'totalUnits',
  'unitLabel',
  'durationMs',
  'failureClass',
  'failureFrame',
  'failureTrace',
  'failureCauseChain',
  'overlay',
  'elapsedMicros',
  'budgetMicros',
]);

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
  if (!allowedKeys(event, EVENT_KEYS) || !validEventFieldTypes(event)) {
    return invalid('invalid_event');
  }

  const environment = validateEnvironment(event.environment);
  if (!environment.ok) {
    return environment;
  }
  const payload = validatePayload(event.payload);
  if (!payload.ok) {
    return payload;
  }

  if (!validJsonShape(event, 0)) {
    return invalid('invalid_event');
  }
  return { ok: true };
}

function validateEnvironment(environment) {
  if (environment === undefined) {
    return { ok: true };
  }
  if (!plainObject(environment) || !allowedKeys(environment, ENVIRONMENT_KEYS)) {
    return invalid('invalid_environment');
  }
  for (const [key, value] of Object.entries(environment)) {
    if (key === 'mods') {
      if (!Array.isArray(value) || !value.every(validModInfo)) {
        return invalid('invalid_environment');
      }
    } else if (typeof value !== 'string') {
      return invalid('invalid_environment');
    }
  }
  return { ok: true };
}

function validatePayload(payload) {
  if (payload === undefined) {
    return { ok: true };
  }
  if (!plainObject(payload) || !allowedKeys(payload, PAYLOAD_KEYS)) {
    return invalid('invalid_payload');
  }
  if (!Object.values(payload).every(value => typeof value === 'string')) {
    return invalid('invalid_payload');
  }
  return { ok: true };
}

function validModInfo(mod) {
  return plainObject(mod)
    && allowedKeys(mod, MOD_KEYS)
    && Object.values(mod).every(value => typeof value === 'string');
}

function validEventFieldTypes(event) {
  if (event.schemaVersion !== undefined && typeof event.schemaVersion !== 'number') {
    return false;
  }
  for (const key of ['id', 'type', 'occurredAt', 'installationId', 'fingerprint']) {
    if (event[key] !== undefined && typeof event[key] !== 'string') {
      return false;
    }
  }
  return true;
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

function allowedKeys(value, allowed) {
  return Object.keys(value).every(key => allowed.has(key));
}

function invalid(error) {
  return { ok: false, status: 400, error };
}
