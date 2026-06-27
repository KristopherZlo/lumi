export class AdminDashboard {
  constructor(repository) {
    this.repository = repository;
  }

  async render() {
    const stats = await this.repository.dashboardStats();
    const timestamp = new Date().toISOString();
    return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Lumi telemetry</title>
  <style>
    body { margin: 0; background: #f7f7f4; color: #1f211d; font: 14px/1.5 ui-sans-serif, system-ui, sans-serif; }
    main { max-width: 980px; margin: 0 auto; padding: 32px 20px 48px; }
    header { display: flex; justify-content: space-between; gap: 16px; align-items: baseline; }
    h1 { margin: 0; font-size: 24px; font-weight: 650; }
    h2 { margin: 0 0 10px; font-size: 16px; font-weight: 650; }
    time, th { color: #64675f; }
    section { background: #fff; border: 1px solid #dedbd2; border-radius: 8px; margin-top: 16px; padding: 16px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border-top: 1px solid #dedbd2; padding: 9px 0; text-align: left; vertical-align: top; }
    td:last-child, th:last-child { text-align: right; font-variant-numeric: tabular-nums; }
    tr:first-child th, tr:first-child td { border-top: 0; }
  </style>
</head>
<body>
  <main>
    <header><h1>Lumi telemetry</h1><time datetime="${escapeHtml(timestamp)}">${escapeHtml(timestamp)}</time></header>
    ${overviewTable(stats.summary)}
    <div class="grid">
      ${countTable('Event types', stats.eventTypes)}
      ${countTable('Lumi versions', stats.lumiVersions)}
      ${countTable('Recent days', stats.dailyEvents)}
    </div>
  </main>
</body>
</html>`;
  }
}

function overviewTable(summary) {
  return `<section><h2>Overview</h2><table><tbody>
    ${row('Total events', formatCount(summary.totalEvents))}
    ${row('Distinct installations', formatCount(summary.distinctInstallations))}
    ${row('First received', summary.firstReceivedAt ?? 'No data')}
    ${row('Last received', summary.lastReceivedAt ?? 'No data')}
  </tbody></table></section>`;
}

function countTable(title, rows) {
  const body = rows.length
    ? rows.map(item => row(item.label, formatCount(item.count))).join('')
    : '<tr><td colspan="2">No data</td></tr>';
  return `<section><h2>${escapeHtml(title)}</h2><table><tbody>${body}</tbody></table></section>`;
}

function row(label, value) {
  return `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(value)}</td></tr>`;
}

function formatCount(value) {
  return Number(value ?? 0).toLocaleString('en-US');
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
