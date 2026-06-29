# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Save the build. Try the idea. Undo the mistake.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is project history for Minecraft builders.

It runs inside your world: save named moments of a build, compare what changed, branch risky ideas, restore older states, and recover interrupted work without copying the whole world folder every time.

Status: alpha. Keep normal world backups.

## For Players

### Download

Download alpha builds from [GitHub Releases](https://github.com/KristopherZlo/lumi/releases). Use a release that lists your Minecraft version.

Current target:

- Minecraft `1.21.11`
- Fabric Loader `0.19.2` or newer compatible Fabric loader
- Java `21`
- Fabric API `0.141.3+1.21.11`

Lumi also declares owo-lib and cloth-config as Fabric dependencies. Install the dependency versions requested by the release if your launcher does not resolve them automatically.

### Install

1. Install Fabric for Minecraft `1.21.11`.
2. Put the Lumi jar and required dependency jars in your `mods` folder.
3. Start Minecraft and open a singleplayer world.
4. Keep a normal world backup before relying on an alpha build.

For dedicated servers, install Lumi on both the server and every client that uses Lumi screens or overlays. Dedicated server actions require operator-level permission; `/lumi save <message>` saves tracked work from the server side.

### What Lumi Does

- Tracks a whole dimension or a selected build area as a project.
- Saves named build versions with change counts and restore data.
- Compares saved versions, branches, and current unsaved work.
- Shows changed blocks with an in-world overlay.
- Restores a whole save, a selected area, or everything outside a selected area.
- Lets you branch risky ideas and merge local branches back into the active branch.
- Shows each branch history from its current head through reachable parent saves and restorable forward descendants.
- Imports and exports project history packages.
- Adds live undo/redo for recent tracked edits.
- Keeps recovery drafts for interrupted work.
- Lets you mark active work zones, save a zone separately, and keep unrelated pending work.
- Lets work zones grow from causal tree growth, hide boundary boxes, delete zone metadata without deleting commits, and optionally show zone commits in global history with zone color markers.
- Captures normal Minecraft edits plus supported WorldEdit, FAWE, and Axiom mutation paths on a best-effort basis.

### Quick Start

1. Create or open a project for the current build area.
2. Follow the quick tour: make 5 block edits, preview them with `Left Alt`, undo/redo with `Left Alt+Z` and `Left Alt+Y`, then save with `Left Alt+S`.
3. Press `U` to open Build History and inspect the created save card.
4. Use save cards for compare, Restore this save, branches, or older checkpoints when an idea goes wrong.

### Default Controls

| Key | Action |
| --- | --- |
| `U` | Open Build History, or Zones when an active zone is selected |
| `Left Alt+S` | Open Save build, or Save zone when an active zone is selected |
| `Left Alt+Z` | Undo |
| `Left Alt+Y` | Redo |
| `R` | Quick rollback |
| `H` | Toggle compare overlay |
| `Left Alt+I` | Show Lumi hotkeys |
| Wooden sword | Select a region; `Left Alt+MMB` resizes with wheel down pulling toward the player and wheel up pushing away, `Left Alt+LMB` switches mode, `Left Alt+RMB` clears, `Left Alt+Z/Y` undo/redo selection, `Ctrl` adds/removes active-zone cells |

Wooden sword hints appear under the crosshair at the GUI Scale 2 visual size; only `LMB`, `MMB`, and `RMB` use mouse icons, while keyboard inputs render as `[KEY]` text.

All keybinds are remappable in Minecraft controls.

### Privacy and Diagnostics

Lumi has diagnostic telemetry for crashes, failed operations, rejected actions, and severe performance problems. It is technical-only and can be turned off in Lumi settings.

Telemetry does not send raw logs, screen views, clicks, world names, project names, coordinates, seeds, exception messages, raw file paths, raw NBT, or block/entity payloads.

The default endpoint is `https://lumi.zloyxp.cc/v1/events/batch`. The receiver stores allowlisted diagnostic fields only, keeps raw events for 90 days, and exposes diagnostics through an authenticated Grafana dashboard.

## For Developers

### Build and Test

Build the mod:

```powershell
.\gradlew.bat build --no-daemon
```

Run the development client:

```powershell
.\gradlew.bat runClient --no-daemon
```

Run unit tests:

```powershell
.\gradlew.bat test --no-daemon
```

Run telemetry backend tests:

```powershell
cd telemetry-backend
npm test
```

### Telemetry Backend

The self-hosted telemetry receiver lives in `telemetry-backend`. It is a small Node/Postgres ingest service used with Grafana OSS for investigation:

- public ingest at `POST /v1/events/batch`
- strict JSON schema, event type allowlists, key allowlists, body size limits, and per-client rate limiting
- no IP storage
- Grafana at `https://lumi.zloyxp.cc/` with sign-up and anonymous access disabled
- a read-only Grafana database user
- default dashboard panels for installs, failures, event types, versions, and sanitized recent events
- 90-day retention for stored raw events

The Node service should stay behind nginx on localhost. Required runtime environment:

```text
DATABASE_URL=postgres://...
ADMIN_USERNAME=ZloyExperience
ADMIN_PASSWORD_HASH=scrypt$...
HOST=127.0.0.1
PORT=8787
TRUST_PROXY=1
```

Generate a random admin password and `ADMIN_PASSWORD_HASH`:

```powershell
node --input-type=module -e "import { randomBytes } from 'node:crypto'; import { hashPassword } from './src/admin-auth.js'; const password = randomBytes(24).toString('base64url'); console.log('password=' + password); console.log('hash=' + hashPassword(password));"
```

Run the local test-client profile:

```powershell
.\scripts\run-test-client.ps1
```

Run the server GameTest smoke suite:

```powershell
.\gradlew.bat runGameTest --no-daemon
```

Run the alpha gate:

```powershell
.\scripts\run-alpha-release-check.ps1
```

The runtime smoke behavior contract is tracked in [SMOKE_BEHAVIOR.md](SMOKE_BEHAVIOR.md). Developer workflow details are in [docs/development.md](docs/development.md).

### Stack

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Fabric API `0.141.3+1.21.11`
- Java `21`
- owo-lib `0.13.0+1.21.11`
- cloth-config `21.11.153`
- lz4-java `1.8.1`
- JUnit 5 and Fabric GameTest

### Code Map

Start with [modules.md](modules.md) before opening broad source trees.

| Area | Responsibility |
| --- | --- |
| `src/main/java/io/github/luma/domain/model` | Value objects, persisted records, summaries, runtime state |
| `src/main/java/io/github/luma/domain/service` | Product workflows and business rules |
| `src/main/java/io/github/luma/minecraft/capture` | Capture hooks, causal context, working drafts |
| `src/main/java/io/github/luma/minecraft/world` | Prepared restore application and tick-time mutation |
| `src/main/java/io/github/luma/storage` | Paths, JSON manifests, binary payloads, archives, atomic writes |
| `src/main/java/io/github/luma/integration` | Optional builder-tool adapters |
| `src/client/java/io/github/luma` | UI controllers, screens, previews, overlays |
| `src/main/java/io/github/luma/mixin` | Thin server-side Minecraft hook entrypoints |
| `src/client/java/io/github/luma/mixin` | Thin client-side Minecraft hook entrypoints |
| `src/test`, `src/gametest` | Unit tests, runtime tests, GameTests |

Layer rules:

- Domain services own product rules.
- Minecraft APIs stay in the Minecraft adapter layer.
- Repositories own storage layout and serialization.
- UI controllers coordinate services; they do not own domain logic.

### Storage Model

World-level Lumi data lives under:

```text
<world>/lumi/
```

Project data lives under:

```text
<world>/lumi/projects/<project>.mbp/
```

Important records:

- `world-origin.json`: shared world origin and restore-safety baseline
- `project.json`: project metadata and settings
- `variants.json`: branch heads
- `history-tombstones.json`: soft-deleted saves and branches
- `work-zones.json`: named work-zone metadata and active selections
- `versions/*.json`: saved version manifests
- `patches/*.meta.json`: patch metadata, indexes, and stats
- `patches/*.bin.lz4`: chunk-addressable block/entity deltas
- `snapshots/*.bin.lz4`: checkpoint anchors
- `entity-checkpoints/*.bin.lz4`: per-save entity snapshots used as the authoritative restore target
- `preview-requests/*.json`: queued client preview work
- `recovery/draft.bin.lz4`: compacted recovery draft
- `recovery/draft.wal.lz4`: append-only recovery draft log
- `recovery/expected-draft.marker`: clean pending-work marker
- `recovery/operation-draft.bin.lz4`: isolated in-progress save/amend draft
- `payloads/baseline-chunks/`: durable first-touch baseline chunks
- `cache/`: disposable UI and diagnostic cache

Current writers create patch payload schema v9 and snapshot payload schema v8. Current readers intentionally support patch payload schema v9 and snapshot payload schemas v7-v8. See [docs/storage-format.md](docs/storage-format.md) for exact layout and compatibility rules.
Project settings include `showHiddenCommits` for showing live-zone commits in global history; deleted-zone commits return to global history because deleting a zone only removes `work-zones.json` metadata.

### Runtime Model

Capture writes working drafts while the player builds. Startup metadata bootstrap waits for a 10-second post-join grace window before running on a low-priority background thread, and first-touch baseline chunk writes default to one low-priority writer to reduce client CPU and disk contention. Save turns a draft into patch metadata plus compressed chunk frames. Restore prepares file I/O, LZ4, block-state decode, and planning off the server tick thread, then applies prepared batches on the server thread with tick budgets.

Hard rules:

- One world operation runs per world at a time.
- Long operations publish progress and terminal success/failure UI feedback.
- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Restore, recovery, merge, and undo/redo replay must not capture themselves as new user edits.
- Client modal overlays consume pointer input so underlying workspace actions cannot fire while a modal is open.
- Saved commits keep entity checkpoints for entities present at save time. Whole-dimension saves include chunks with currently loaded live non-player entities, and player-spawned non-player entities make durable pending work so entity-only saves are possible. Restore can skip selected entity types for a single run without changing the saved commit.
- Restore confirmation entity summaries count only entities inside the resolved restore scope for zones and selected/outside partial restores.
- Live undo may track transient entities to clean up active fallout, but redo replays final deltas instead of respawning transient primed TNT; recovery drafts and saved commits must not persist undo-only transient entities.

### Diagnostics

Useful JVM flags:

```text
-Dlumi.debug=true
-Dlumi.loadLog=true
-Dlumi.clientLoadLog=true
-Dlumi.lightLog=true
-Dlumi.blockApplyLog=true
-Dlumi.partialRestoreLog=true
-Dlumi.testerDiagnostics=true
-Dlumi.ui.targetGuiScale=2
-Dlumi.ui.iconButtonWidth=26
-Dlumi.ui.iconButtonHeight=16
-Dlumi.ui.iconDrawSize=12
```

The `lumi.ui.*` flags are dev-only tuning knobs for Lumi's in-game menus.
`targetGuiScale=2` makes Lumi screens render as if Minecraft GUI Scale were set
to 2, and the icon button flags tune the button box and the 24x24 texture draw
size.

Runtime logs are written under the normal Minecraft `logs/` directory or the world-local `lumi/test-logs/` directory for test profiles, including multiplayer work-zone smoke behavior logs.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
