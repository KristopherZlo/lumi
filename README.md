# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Version control for your creations to save builds, compare changes, restore earlier states.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Loader Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="Environment Singleplayer First" src="https://img.shields.io/badge/Environment-Singleplayer%20First-2EA043?style=for-the-badge" />
  <img alt="License GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is a Fabric mod for Minecraft `1.21.11`.

It gives builders version control for a build area.

You can save versions, branch ideas, compare states, restore old states, and recover pending edits after a crash.

The mod uses builder terms:

- `project`
- `version`
- `variant`
- `compare`
- `restore`
- `recovery`

## What It Solves

Use Lumi if you want to:

- try a redesign without losing the stable version
- check what changed since the last save
- go back to an older state without copying full save folders
- keep separate variants for alternate ideas
- recover work after a crash or bad edit

## Core Model

| Term | Meaning | Stored in |
| --- | --- | --- |
| `Project` | Tracked area in one dimension | `project.json` |
| `Version` | Saved history node with message, stats, preview, and payload refs | `versions/*.json` |
| `Variant` | Named branch-like head pointer | `variants.json` |
| `Compare` | Diff between two saved states, or between a saved state and the live game state | `DiffService` |
| `Restore` | Apply a chosen version back into the map and move the active head to it | `RestoreService` |
| `Recovery` | Crash-safe draft storage | `recovery/draft.*` |

## Current Features

- automatic dimension projects
- patch-first history with checkpoint snapshots
- manual save and amend-on-head
- compare against parent, other versions, branch heads, or live game state
- hard restore that moves the active variant head
- recovery drafts with WAL compaction
- preview images and version metadata
- material delta summaries and integrity checks
- zip import/export for project history
- conservative cleanup for orphaned snapshots, previews, cache files, and stale operation drafts
- capture of player edits plus supported entity and explosion edits

## How It Works

### Capture

1. A mixin catches a block change.
2. `HistoryCaptureManager` finds matching projects.
3. Explicit builder-driven sources can bootstrap a dimension project on demand, but ambient world-settling sources do not.
4. Secondary sources such as falling blocks, fluids, fire, growth, mob griefing, and block updates only append while a capture session is already active.
5. Ambient spread/update sources only record inside chunks that the project has already tracked.
6. `TrackedChangeBuffer` merges the change.
7. Recovery draft data flushes on an interval.

### Save

1. `VersionService` consumes the active draft.
2. Patch payloads are prepared off-thread.
3. Metadata is written after payload files exist.
4. Preview generation runs later on a low-priority executor.

### Restore

1. Active capture is frozen first.
2. Lumi tries direct same-lineage replay first.
3. `WORLD_ROOT` restore uses tracked baseline chunks.
4. Snapshot fallback is used if direct replay is not valid.
5. Tick-thread apply uses bounded chunk batches.
6. The active variant head moves to the restored version.

## Runtime Rules

- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- One map operation is expected at a time per save.
- Progress is exposed through operation state.
- Lumi screens do not pause the game.
- Detached old versions stay on disk for safety.

## Architecture

| Layer | Responsibility | Main types |
| --- | --- | --- |
| Bootstrap | Fabric wiring, commands, ticking, flushes | `LumaMod` |
| Domain model | persisted records and runtime state | `BuildProject`, `ProjectVersion`, `TrackedChangeBuffer` |
| Domain service | product logic | `VersionService`, `RestoreService`, `RecoveryService`, `DiffService` |
| Minecraft adapter | game hooks and map mutation | `HistoryCaptureManager`, `WorldOperationManager`, `BlockChangeApplier` |
| Storage | file layout and payload I/O | `ProjectLayout`, repositories in `storage/repository` |
| Client UI | screens, controllers, HUD, view state | `ProjectScreen`, `CompareScreen`, `WorkspaceHudCoordinator` |

Rules:

- domain services own product logic
- Minecraft adapters touch game APIs
- repositories handle files and payloads
- UI controllers stay thin

## Storage

Project data lives under:

```text
<save>/lumi/projects/<project>.mbp/
```

Main files:

- `project.json`
- `variants.json`
- `versions/*.json`
- `patches/*.meta.json`
- `patches/*.bin.lz4`
- `snapshots/*.bin.lz4`
- `previews/*.png`
- `recovery/draft.bin.lz4`
- `recovery/draft.wal.lz4`
- `recovery/journal.json`

History archives exported from commands are written under:

```text
<save>/lumi/exports/
```

See [docs/storage-format.md](docs/storage-format.md) for the full format.

## Target

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Java `21`
- Fabric API `0.141.3+1.21.11`

Main libraries:

- `owo-lib`
- `cloth-config`
- `lz4-java`

Build output is one mod jar.

## Build

```powershell
.\gradlew.bat build
```

Run client:

```powershell
.\gradlew.bat runClient
```

Run test client:

```powershell
.\scripts\run-test-client.ps1
```

Run tests:

```powershell
.\gradlew.bat test
```

Artifacts go to `build/libs/`.

## Quick Start

1. Open a local singleplayer save.
2. Press `U`.
3. Open or create the project for the current dimension.
4. Build in the tracked area.
5. Save a version from the History tab.
6. Use Compare, Restore, Variants, Recovery, and Settings as needed.

## Scope

Current scope:

- singleplayer / integrated-server first
- menu flow first, commands as fallback
- no merge/conflict flow yet
- no partial restore yet
- compare overlay marks changed positions, not a full 3D preview

## Docs

- [User guide](docs/user-guide.md)
- [Commands](docs/commands.md)
- [Development](docs/development.md)
- [Architecture](docs/architecture.md)
- [Maintenance guide](docs/maintenance-guide.md)
- [Storage format](docs/storage-format.md)
- [Commit policy](docs/commit-policy.md)
- [Test client profile](docs/test-client.md)

## License

Licensed under [GPL-3.0](LICENSE).
