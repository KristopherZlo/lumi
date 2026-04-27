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

It gives builders safety history for a build area.

You can save builds, see changes, restore earlier states, try alternate ideas, import/export portable history packages, combine imported ideas back into your build, and recover pending edits after a crash.

Lumi only activates for players with operator-level permissions or when cheats are enabled in the world.

The normal UI uses builder terms:

- `Build History`
- `Save`
- `Idea`
- `See changes`
- `restore`
- `Recovered work`

## What It Solves

Use Lumi if you want to:

- try a redesign without losing the stable version
- check what changed since the last save
- go back to an older state without copying full save folders
- keep separate ideas for alternate build directions
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
- builder-first Build History UI built around `Save build`, `See changes`, recent saves, `Ideas`, and `More`
- lightweight save, idea, import/export, cleanup, diagnostics, and advanced navigation with live background-operation refresh while screens stay open
- patch-first history with checkpoint snapshots
- dedicated save screen with optional `Replace latest save`
- save details screen with isometric preview, restore, see-changes, and idea actions
- See Changes screen for saved states, ideas, and the current build, with manual references hidden under Advanced
- live undo and redo for the last tracked builder actions with default `Alt+Z` / `Alt+Y` bindings
- short-lived secondary fallout near the latest tracked action is folded into that same undo/redo step when it settles right after the edit
- hard restore that moves the active idea head
- recovery drafts with WAL compaction
- client-rendered textured isometric preview images auto-framed from changed blocks with safe context padding
- material delta summaries and integrity checks under focused details/diagnostic screens
- zip import/export under `More`, including idea-scoped packages with optional previews
- imported review projects with deletion, cached combine review, and same-area overlays for shared ideas
- optional WorldEdit edit-session capture when WorldEdit is present, without a hard runtime dependency
- conservative cleanup for orphaned snapshots, previews, cache files, and stale operation drafts
- capture of player edits plus supported entity and explosion edits
- temporary `Alt` overlay for the latest 10 tracked actions when compare highlight is not active

## How It Works

### Capture

1. A mixin catches a block change.
2. `HistoryCaptureManager` finds matching projects.
3. Explicit builder-driven sources can bootstrap a dimension project on demand, but ambient world-settling sources do not.
4. Whole-dimension sessions now keep a causal chunk envelope rooted in explicit builder edits. The root chunk defines a one-chunk halo envelope, and Lumi captures per-chunk baselines lazily when a chunk inside that envelope first needs stabilization.
5. Ambient fallout such as fluid spread and falling blocks no longer append directly into the live draft for whole-dimension workspaces. They only re-mark chunks inside that causal envelope as dirty.
6. `TrackedChangeBuffer` still merges explicit and targeted realtime changes immediately.
7. First-touch whole-dimension baseline capture now copies compact chunk section payloads on the server thread and writes the compressed baseline file later on a dedicated low-priority capture-maintenance executor.
8. Before draft snapshots, idle flushes, save, amend, or freeze persist anything, Lumi reconciles dirty envelope chunks against the current world and stores the final stabilized diff on top of the live pending chunk buffer.
9. Recovery draft data still flushes on an interval, but the WAL append and compaction now run asynchronously on that same capture-maintenance executor.

### Save

1. `VersionService` consumes the active draft.
2. Patch payloads are prepared off-thread.
3. Metadata is written after payload files exist.
4. Preview generation queues a lightweight request in project storage.
5. The client later fulfills that request with a textured isometric off-screen render and updates the version metadata.

### Restore

1. Active capture is frozen first.
2. Lumi tries direct same-lineage replay first.
3. `WORLD_ROOT` restore uses tracked baseline chunks.
4. Snapshot fallback is used if direct replay is not valid.
5. Tick-thread apply uses bounded chunk batches.
6. The active variant head moves to the restored version.

## Runtime Rules

- JSON parsing, LZ4 decompression, and block-state decoding stay off the tick-thread apply path.
- Recovery WAL writes, WAL compaction, and baseline chunk compression stay off the server-tick capture path.
- One map operation is expected at a time per save.
- Progress is exposed through operation state.
- Lumi screens do not pause the game.
- Detached old versions stay on disk for safety.

## Architecture

| Layer | Responsibility | Main types |
| --- | --- | --- |
| Bootstrap | Fabric wiring, diagnostic commands, ticking, flushes | `LumaMod` |
| Domain model | persisted records and runtime state | `BuildProject`, `ProjectVersion`, `TrackedChangeBuffer` |
| Domain service | product logic | `VersionService`, `RestoreService`, `RecoveryService`, `DiffService` |
| Minecraft adapter | game hooks and map mutation | `HistoryCaptureManager`, `WorldOperationManager`, `BlockChangeApplier` |
| Storage | file layout and payload I/O | `ProjectLayout`, repositories in `storage/repository` |
| Client UI | LDLib2 GDP screen factories, controllers, HUD, view state | `LdLib2Screens`, `LdLib2ProjectHomeScreenFactory`, `WorkspaceHudCoordinator` |

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
- `preview-requests/*.json`
- `recovery/draft.bin.lz4`
- `recovery/draft.wal.lz4`
- `recovery/journal.json`

History archives and share packages are written under:

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

- `cloth-config`
- `lz4-java`

LDLib2 GDP is the only menu UI backend in this branch. The Fabric `1.21.11` build keeps LDLib2 isolated behind reflection until a compatible Fabric artifact exists, but that isolation is not a fallback: menu screens require compatible LDLib2 runtime classes and are created as LDLib2 `ModularUIScreen` instances with the built-in GDP stylesheet (`ldlib2:lss/gdp.lss`).

Build output is one mod jar.

## Build

```powershell
.\gradlew.bat build
```

Run client:

```powershell
.\gradlew.bat runClient
```

Dev launch tasks automatically remove packaged `lumi-*.jar` and legacy `luma-*.jar` copies from the local `run/*/mods` folders so Loom only loads the compiled source-set output.

Run test client:

```powershell
.\scripts\run-test-client.ps1
```

Run tests:

```powershell
.\gradlew.bat test
```

Artifacts go to `build/libs/`. Packaging tasks also prune stale legacy `luma-*` artifacts so the folder only keeps the current `lumi-*` outputs.

## Quick Start

1. Open a local singleplayer save.
2. Make sure cheats are enabled or the player has operator-level permissions.
3. Press `U`.
4. Lumi opens the current Build History directly when the dimension project is available.
5. Build in the tracked area.
6. Use `Alt+Z` / `Alt+Y` to undo or redo the latest tracked Lumi action.
7. Hold `Alt` to preview the latest 10 tracked actions when the compare overlay is not active.
   Seeing changes against `Current build` enables the world highlight immediately and refreshes it automatically while you keep editing.
8. Use `Save build` when you want a safe restore point.
9. Open a save when you want details, restore, see changes, or create an idea from it.
10. Use `Ideas` for alternate build directions and `More` for import/export, settings, cleanup, diagnostics, and advanced tools.

## Scope

Current scope:

- singleplayer / integrated-server first
- menu flow first, commands limited to diagnostics/help
- combine currently works through imported review projects for the same project lineage, with background review, block-level same-area detection, and validation messages before Lumi writes a combined save
- partial restore is available from save details with manual bounds
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
