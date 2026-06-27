import pg from 'pg';

export class PgTelemetryRepository {
  constructor({ connectionString, pool = null } = {}) {
    this.pool = pool ?? new pg.Pool({ connectionString });
    this.schemaReady = false;
  }

  async ensureSchema() {
    if (this.schemaReady) {
      return;
    }
    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS telemetry_events (
        id BIGSERIAL PRIMARY KEY,
        event_id TEXT NOT NULL,
        schema_version INTEGER NOT NULL,
        event_type TEXT NOT NULL,
        occurred_at TIMESTAMPTZ NOT NULL,
        installation_id TEXT NOT NULL,
        lumi_version TEXT NOT NULL,
        minecraft_version TEXT NOT NULL,
        fabric_loader_version TEXT NOT NULL,
        java_version TEXT NOT NULL,
        os_family TEXT NOT NULL,
        os_arch TEXT NOT NULL,
        mods_json JSONB NOT NULL,
        fingerprint TEXT NOT NULL,
        payload_json JSONB NOT NULL,
        received_at TIMESTAMPTZ NOT NULL
      )
    `);
    await this.pool.query(`
      CREATE INDEX IF NOT EXISTS telemetry_events_received_at_idx
      ON telemetry_events (received_at)
    `);
    this.schemaReady = true;
  }

  async insertEvents(events) {
    if (!events.length) {
      return;
    }
    await this.ensureSchema();
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      for (const event of events) {
        await client.query(
          `
            INSERT INTO telemetry_events (
              event_id,
              schema_version,
              event_type,
              occurred_at,
              installation_id,
              lumi_version,
              minecraft_version,
              fabric_loader_version,
              java_version,
              os_family,
              os_arch,
              mods_json,
              fingerprint,
              payload_json,
              received_at
            ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
          `,
          [
            event.id ?? '',
            event.schemaVersion ?? 1,
            event.type ?? '',
            event.occurredAt ?? new Date().toISOString(),
            event.installationId ?? '',
            event.environment?.lumiVersion ?? '',
            event.environment?.minecraftVersion ?? '',
            event.environment?.fabricLoaderVersion ?? '',
            event.environment?.javaVersion ?? '',
            event.environment?.osFamily ?? '',
            event.environment?.osArch ?? '',
            JSON.stringify(event.environment?.mods ?? []),
            event.fingerprint ?? '',
            JSON.stringify(event.payload ?? {}),
            event.receivedAt ?? new Date().toISOString(),
          ]
        );
      }
      await client.query('COMMIT');
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  async deleteEventsBefore(cutoff) {
    await this.ensureSchema();
    await this.pool.query('DELETE FROM telemetry_events WHERE received_at < $1', [cutoff.toISOString()]);
  }

  async dashboardStats() {
    await this.ensureSchema();
    const [summary, eventTypes, lumiVersions, dailyEvents] = await Promise.all([
      this.pool.query(`
        SELECT
          COUNT(*) AS total_events,
          COUNT(DISTINCT installation_id) AS distinct_installations,
          MIN(received_at) AS first_received_at,
          MAX(received_at) AS last_received_at
        FROM telemetry_events
      `),
      this.pool.query(`
        SELECT event_type AS label, COUNT(*) AS count
        FROM telemetry_events
        GROUP BY event_type
        ORDER BY count DESC, event_type ASC
        LIMIT 20
      `),
      this.pool.query(`
        SELECT COALESCE(NULLIF(lumi_version, ''), 'unknown') AS label, COUNT(*) AS count
        FROM telemetry_events
        GROUP BY label
        ORDER BY count DESC, label ASC
        LIMIT 20
      `),
      this.pool.query(`
        SELECT to_char(date_trunc('day', received_at), 'YYYY-MM-DD') AS label, COUNT(*) AS count
        FROM telemetry_events
        GROUP BY label
        ORDER BY label DESC
        LIMIT 30
      `),
    ]);

    const row = summary.rows[0] ?? {};
    return {
      summary: {
        totalEvents: count(row.total_events),
        distinctInstallations: count(row.distinct_installations),
        firstReceivedAt: iso(row.first_received_at),
        lastReceivedAt: iso(row.last_received_at),
      },
      eventTypes: countRows(eventTypes.rows),
      lumiVersions: countRows(lumiVersions.rows),
      dailyEvents: countRows(dailyEvents.rows),
    };
  }
}

function countRows(rows) {
  return rows.map(row => ({
    label: String(row.label ?? ''),
    count: count(row.count),
  }));
}

function count(value) {
  return Number(value ?? 0);
}

function iso(value) {
  return value instanceof Date ? value.toISOString() : value;
}
