# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Build history for Minecraft builders.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is a singleplayer-first Fabric mod for Minecraft `1.21.11`.

It gives builders project history inside a world: save versions, compare changes, branch ideas, restore old states, roll back mistakes, and recover interrupted work.

Status: alpha. Keep normal world backups.

## For Players

### What It Does

- Tracks a dimension or selected build area as a project.
- Saves named versions with change stats and restore data.
- Lets saves carry searchable tags, with tag input fields capped at 128 characters.
- Soft-deletes non-root saves without removing their stored files.
- Compares versions, branches, and the current world.
- Shows changed blocks with an in-world overlay.
- Restores a full version, only a selected area, or everything except a selected area.
- Supports branches, local merges, import, and export.
- Lets builders label current work with named work zones, save an active zone without consuming other pending work, and review zone saves on a separate zone-history surface.
- Keeps live undo/redo for recent tracked edits.
- Keeps recovery drafts for interrupted work.
- Captures supported WorldEdit, FAWE, Axiom, and normal Minecraft mutation paths on a best-effort basis.

### What It Is Not

- Not a backup replacement.
- Not a survival content mod.
- Not a full multiplayer conflict-resolution system.
- Not a guarantee that every builder tool path is captured.

### Default Controls

| Key | Action |
| --- | --- |
| `U` | Open Build History |
| `Left Alt+S` | Open the Save build modal, or Save zone while viewing an active zone |
| `Left Alt+Z` | Undo |
| `Left Alt+Y` | Redo |
| `R` | Quick rollback |
| `H` | Toggle compare overlay |
| `Left Alt+I` | Show Lumi hotkeys |
| Wooden sword | Select a region for partial restore |

All keybinds are remappable in Minecraft controls.

### Runtime Support

- Client: required
- Server: required on dedicated servers
- Singleplayer: primary target
- Integrated server: supported
- Dedicated server: supported for the Zones workflow when Lumi is installed on both client and server
- Dedicated server actions require operator-level permission; use `/lumi save <message>` to save tracked work from the server side

## For Developers

### Stack

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Fabric API `0.141.3+1.21.11`
- Java `21`
- owo-lib `0.13.0+1.21.11`
- cloth-config `21.11.153`
- lz4-java `1.8.1`
- JUnit 5 and Fabric GameTest

### Build

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

Run the local test-client profile:

```powershell
.\scripts\run-test-client.ps1
```

Run the alpha gate:

```powershell
.\scripts\run-alpha-release-check.ps1
```

### Project Shape

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

High-level rules:

- Domain services own product rules.
- Minecraft APIs stay in the Minecraft adapter layer.
- Repositories own storage layout and serialization.
- UI controllers coordinate services; they do not own domain logic.

### History Model

Lumi stores project data under:

```text
<world>/lumi/projects/<project>.mbp/
```

Main records:

- `project.json`: project metadata
- `versions/*.json`: saved version manifests
- `variants.json`: branch heads
- `patches/*.meta.json`: patch metadata and indexes
- `patches/*.bin.lz4`: chunk-addressable block/entity deltas
- `snapshots/*.bin.lz4`: checkpoint anchors
- `recovery/*.lz4`: crash-safe working drafts
- `recovery/expected-draft.marker`: pending draft marker for clean exits and expected unsaved work
- `cache/baseline-chunks/`: first-touch baseline chunks

### Runtime Model

Capture writes working drafts while the player builds. Save turns a draft into patch metadata plus compressed chunk frames. Restore prepares file I/O, LZ4, block-state decode, and planning off the server tick thread, then applies prepared batches on the server thread with tick budgets.

Hard rules:

- One world operation runs per world at a time.
- Long operations publish progress.
- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Restore, recovery, merge, and undo/redo replay must not capture themselves as new user edits.

### Useful Diagnostics

```text
-Dlumi.debug=true
-Dlumi.loadLog=true
-Dlumi.clientLoadLog=true
-Dlumi.lightLog=true
-Dlumi.blockApplyLog=true
-Dlumi.partialRestoreLog=true
-Dlumi.testerDiagnostics=true
```

Runtime logs are written under the normal Minecraft `logs/` directory or the world-local `lumi/test-logs/` directory for test profiles.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
