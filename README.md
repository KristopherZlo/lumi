# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Project-oriented world history for Minecraft builders.</strong><br />
  A singleplayer-first Fabric mod for saving, comparing, restoring, and recovering structure work without exposing raw Git terminology in the UI.
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Loader Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="Environment Integrated Server" src="https://img.shields.io/badge/Environment-Singleplayer%20First-2EA043?style=for-the-badge" />
  <img alt="License GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is a Fabric mod for Minecraft `1.21.11` focused on builder workflows. Instead of asking the player to think in terms of repository internals, it exposes a builder-friendly model built around `project`, `version`, `variant`, `compare`, `restore`, and `recovery`.

The design goal is not "Git inside Minecraft". The design goal is a reliable history system for structure work that survives crashes, keeps long operations off the server tick, and makes restore and diff workflows practical inside an integrated-server world.

## What Lumi Actually Does

Lumi creates tracked workspaces inside a world and records meaningful changes to that workspace over time. Those changes can then be:

- saved as named versions
- amended into the current head
- compared against parent versions, other branch heads, or the current live world
- restored back into the world as a hard workspace reset
- recovered after interrupted sessions or crashes

For automatic dimension workspaces, Lumi also creates a metadata-backed `WORLD_ROOT` / `Initial` history node so the workspace has a real origin state instead of starting from an empty conceptual history.

## Core Product Model

| Lumi term | Meaning | Backed by |
| --- | --- | --- |
| `Project` | A tracked workspace in one dimension, with bounds or whole-dimension coverage | `project.json` |
| `Version` | One saved history node with message, stats, lineage, preview, and payload refs | `versions/*.json` |
| `Variant` | A named branch-like head pointer | `variants.json` |
| `Compare` | A reconstructed diff between two history states or between history and the live world | `DiffService` |
| `Restore` | Re-apply a chosen version into the world and move the active head to that restored state | `RestoreService` |
| `Recovery` | Crash-safe pending-change survival and replay | `recovery/draft.*` + journal |

## Current Capabilities

- Automatic dimension workspaces with shared world-origin bootstrap metadata.
- Patch-first history storage with checkpoint snapshots by policy rather than snapshot-only saves.
- Background save preparation with bounded tick-thread world application.
- Manual save and amend-on-head flows from the UI and command fallback layer.
- Hard restore semantics that move the active variant head to the restored version.
- Recovery drafts with WAL compaction instead of rewriting one large JSON blob for every change.
- Comparison against parent versions, other versions, branch heads, and the current live world.
- Client-side compare overlay for changed block positions near the camera.
- Material delta summaries, preview metadata, integrity checks, and workflow logs.
- Capture of player edits plus supported world-driven mutations such as entity and explosion-driven block changes inside tracked space.

## Runtime Architecture

| Layer | Responsibility | Representative types |
| --- | --- | --- |
| Bootstrap | Fabric wiring, command registration, operation ticking, session flushing | `LumaMod` |
| Domain model | Persisted records and focused runtime state | `BuildProject`, `ProjectVersion`, `TrackedChangeBuffer` |
| Domain service | Product rules and workflow orchestration | `VersionService`, `RestoreService`, `RecoveryService`, `DiffService` |
| Minecraft adapter | Capture hooks, world mutation application, tick-time dispatch | `HistoryCaptureManager`, `WorldOperationManager`, `BlockChangeApplier` |
| Storage | File layout, manifests, compressed payload I/O | `ProjectLayout`, repositories under `storage/repository` |
| Client UI | Non-pausing screens, controllers, immutable view state, HUD overlay | `ProjectScreen`, `CompareScreen`, `WorkspaceHudCoordinator` |

The main architectural rule is strict separation between product rules, Minecraft engine calls, and persistence details. Domain services should describe Lumi behavior. Minecraft adapters should handle Minecraft. Repositories should handle files.

## Operation Pipeline

### Capture

1. A mixin intercepts a world mutation.
2. `WorldMutationContext` tags the mutation source.
3. `HistoryCaptureManager` finds matching projects for that position.
4. The active `TrackedChangeBuffer` merges changes using first-old / last-new semantics.
5. Dirty drafts flush into recovery storage on an interval.

### Save

1. `VersionService` consumes the live in-memory draft first.
2. The current draft is persisted once as a durable recovery fallback.
3. Patch payload generation runs off-thread.
4. Metadata is written only after payload files exist.
5. Preview generation runs later on a separate low-priority executor.

### Restore

1. Active capture is frozen first.
2. A safety checkpoint can be created before mutation starts.
3. Lumi prefers direct same-lineage replay when possible.
4. `WORLD_ROOT` restore uses tracked baseline chunks for the workspace.
5. Snapshot fallback is used only when direct replay is not valid.
6. Tick-thread world application is chunk-batched and time-bounded.
7. The active variant head is moved to the restored version on success.

## Runtime Guarantees

- JSON parsing, LZ4 decompression, and block-state decoding are kept off the tick-thread apply path.
- Only one world operation is intended to run per world at a time.
- Progress is exposed through operation state instead of pretending long tasks are instant.
- Lumi screens do not pause the game.
- Recovery is part of the normal workflow model, not a bolted-on emergency mode.
- Detached older versions remain on disk for safety even when no active variant points to them.

## Storage Layout

World data lives under:

```text
<world>/lumi/
  world-origin.json
  projects/
    <project>.mbp/
      project.json
      variants.json
      versions/
      patches/
      snapshots/
      previews/
      recovery/
      cache/
      locks/
```

Important payloads:

- `patches/<patchId>.meta.json`: lightweight chunk index and patch metadata
- `patches/<patchId>.bin.lz4`: primary patch payload
- `snapshots/<snapshotId>.bin.lz4`: checkpoint snapshot anchor
- `recovery/draft.bin.lz4`: compacted recovery state
- `recovery/draft.wal.lz4`: append-only recovery log
- `recovery/journal.json`: user-facing workflow log

Schema version, snapshot cadence, and storage rationale are documented in [docs/storage-format.md](docs/storage-format.md).

## Dependencies And Packaging

Lumi currently targets:

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Java `21`
- Fabric API `0.141.3+1.21.11`

Key libraries used by the mod:

- `owo-lib` for UI composition
- `cloth-config` for settings integration
- `lz4-java` for compressed payload storage

The repository is configured to ship Lumi as a single distributable mod jar. Internal support libraries are included through Loom jar-in-jar packaging, while Fabric API remains an external dependency.

## Build And Local Development

Build the mod:

```powershell
.\gradlew.bat build
```

Run the normal client profile:

```powershell
.\gradlew.bat runClient
```

Run the dedicated test client profile:

```powershell
.\scripts\run-test-client.ps1
```

Run the automated test suite:

```powershell
.\gradlew.bat test
```

Artifacts are written to `build/libs/`.

## In-Game Quick Start

1. Open a local singleplayer world.
2. Press `U` to open Lumi.
3. Open or create the workspace for your current dimension.
4. Build normally in the tracked area.
5. Save a version from the History tab.
6. Use Compare, Restore, Variants, Recovery, and Settings as needed.

## Current Scope

Lumi is deliberately scoped around local builder workflows first.

Current limitations:

- singleplayer / integrated-server is the supported runtime target
- commands exist as fallback, but the menu flow is the primary UX
- WorldEdit and Axiom integrations are not deeply implemented yet
- import/export, merge/conflict resolution, archive deletion, and partial restore are not finished
- the compare overlay highlights changed positions in the live world, not a full 3D structural preview

## Documentation

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
