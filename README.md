# Lumi

<p align="center">
  <img alt="Lumi banner" src="lumi-banner.png" />
</p>

<p align="center">
  <strong>Singleplayer-first build history for Minecraft projects.</strong>
</p>

<p align="center">
  <img alt="Minecraft 1.21.11" src="https://img.shields.io/badge/Minecraft-1.21.11-5E7C16?style=for-the-badge" />
  <img alt="Loader Fabric" src="https://img.shields.io/badge/Loader-Fabric-DBD0B4?style=for-the-badge" />
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-1F6FEB?style=for-the-badge" />
  <img alt="Environment Singleplayer First" src="https://img.shields.io/badge/Environment-Singleplayer%20First-2EA043?style=for-the-badge" />
  <img alt="License GPL 3.0" src="https://img.shields.io/badge/License-GPL%203.0-2EA043?style=for-the-badge" />
</p>

Lumi is a Fabric mod for Minecraft `1.21.11`.

It gives builders a project-oriented history workflow for a dimension or selected build area. Instead of copying whole save folders, Lumi records block and entity state transitions as compact history payloads, lets the player compare versions, branch a build, restore older states, partially restore a selected region, recover interrupted work, and share project history packages.

The mod is singleplayer-first. In an integrated singleplayer world, builder edits are captured immediately. On dedicated servers, mutating Lumi actions require operator-level access through the mod's access-control layer.

## Product Model

| Term | Meaning | Main storage or owner |
| --- | --- | --- |
| `Project` | A tracked build area or whole dimension workspace | `project.json`, `ProjectService` |
| `Version` | A saved history node with message, stats, payload refs, and optional preview | `versions/*.json`, `VersionService` |
| `Variant` | A branch-like head pointer for alternate build directions | `variants.json`, `VariantService` |
| `Patch` | A compact per-version block/entity delta | `patches/*.meta.json`, `patches/*.bin.lz4` |
| `Snapshot` | A checkpoint full-state anchor used by restore planning | `snapshots/*.bin.lz4` |
| `Recovery draft` | Crash-safe unsaved working state | `recovery/draft.*`, `RecoveryService` |
| `Live undo/redo` | Runtime-only action stack for recent builder actions | `UndoRedoHistoryManager` |
| `Compare` | Diff between versions, branches, or current build | `DiffService`, overlay renderer |
| `Restore` | Tick-budgeted replay of a saved or planned state into the world | `RestoreService`, `WorldOperationManager` |

## Current Capabilities

- Automatic dimension workspaces and bounded project workspaces.
- Builder-facing Build History UI with save details, branches, compare, restore, import/export, recovery, cleanup, settings, and deleted-save views.
- Manual saves, quick saves, amend latest save, restore checkpoints, auto checkpoints before configured large external edits, and semantic save kinds such as `MERGE`, `AUTO_CHECKPOINT`, and `PARTIAL_RESTORE`.
- Live undo/redo for recent tracked actions with default `Left Alt+Z` and `Left Alt+Y`; quick rollback with default `R`; quick save with default `Left Alt+S`; hotkey help with default `Left Alt+I`.
- Runtime wooden-sword region selection for partial restore and selected-area quick rollback.
- Version compare for saved versions, branches, and the current build, with world overlays for changed blocks.
- Full restore, quick rollback, return-before-restore, and partial restore in `Only selected area` or `Everything except selection` mode.
- Branch creation, branch switching, local branch merge, variant import/export, full project archives, imported review projects, and safety checks for imported executable world-state data.
- Crash recovery through durable working drafts, write-ahead log compaction, operation-draft isolation, recovery journals, and restore return points.
- Capture of non-player entity spawn/remove/update with persistent NBT payloads for supported builder-facing entities.
- Optional WorldEdit/FAWE/Axiom/tool-stack capture without hard runtime dependencies.
- Client-side textured isometric previews and large-diff overlays prepared away from the render thread.
- Runtime diagnostics, load logs, block-apply logs, light-refresh logs, smoke tests, and a broad singleplayer regression suite.

## Architecture

Lumi keeps product rules away from Minecraft mutation and storage details.

| Layer | Responsibility | Main packages and classes |
| --- | --- | --- |
| Bootstrap | Fabric entrypoints, event wiring, operation ticking, shutdown flushes | `LumaMod`, `LumaClient` |
| Domain model | Value objects, persisted records, runtime buffers | `domain/model` |
| Domain services | Save, restore, diff, branch, merge, archive, cleanup, recovery rules | `domain/service` |
| Storage | Canonical paths, JSON manifests, binary payloads, archives, atomic writes | `storage`, `storage/repository` |
| Minecraft capture | Mixin-facing capture, causal context, draft ownership, stabilization | `minecraft/capture` |
| Minecraft apply | Prepared chunk batches, tick-budgeted block/entity application, light/redstone replay | `minecraft/world` |
| Optional integrations | Reflective adapters for builder tools | `integration` |
| Client UI | owo-ui screens, controllers, view state, previews, overlays, graph layout | `src/client/java/io/github/luma` |
| Mixins | Thin Minecraft hook entrypoints | `mixin` |
| Tests | Unit, storage, UI, integration, GameTest, and runtime singleplayer suites | `src/test`, `src/gametest`, scripts |

For file-level navigation, start with [modules.md](modules.md). It maps each workflow to the service, repository, adapter, UI, tests, and docs that own it.

## How Capture Works

1. A mixin hook, optional integration, known-tool fallback, direct section fallback, or entity lifecycle hook observes a world mutation.
2. `HistoryCaptureManager` resolves matching active projects through a cached dimension/chunk index.
3. `WorldMutationCapturePolicy` classifies block changes as direct capture, deferred stabilization, or rejected transient state.
4. Direct explicit builder edits are merged into the durable working draft immediately.
5. Redstone, fluid, falling-block, and piston fallout is usually captured through causal dirty-chunk stabilization instead of raw per-tick event history.
6. `TrackedChangeBuffer` keeps first-old/latest-new semantics per block position and per entity id; no-op transitions are removed.
7. `LiveUndoRedoActionRecorder` mirrors eligible action-scoped changes into the separate in-memory undo/redo stack.
8. Recovery drafts are flushed asynchronously, while first-touch whole-dimension baselines are written on a separate low-priority queue.

Important capture rules:

- Internal restore, recovery, merge, and undo/redo application is suppressed so Lumi does not capture its own replay as new user work.
- Moving piston animation state is transient. Lumi waits for dirty chunks to settle instead of persisting `moving_piston` as final build state.
- Delayed vanilla block events, scheduled ticks, and moving piston block entities can carry the original action id forward. Mechanism propagation depth is bounded so clocks cannot keep extending one action forever.
- Ambient changes without a causal builder action are rejected or hidden from builder-facing surfaces.

## Save Format

Project data lives under:

```text
<world>/lumi/projects/<project>.mbp/
```

The current storage model is patch-first:

- `versions/*.json` are lightweight manifests.
- `patches/*.meta.json` stores patch metadata and the chunk index.
- `patches/*.bin.lz4` stores chunk-addressable binary patch payloads.
- `snapshots/*.bin.lz4` stores checkpoint full-state anchors.
- `cache/baseline-chunks/` stores first-touch whole-dimension baseline chunks.
- `cache/content/` stores content-addressed immutable section payload blobs.
- `recovery/draft.bin.lz4` and `recovery/draft.wal.lz4` store crash-safe drafts.
- `recovery/operation-draft.bin.lz4` isolates a save/amend draft while the async operation is running.

New patch payloads use binary schema v9. Each file has a small Lumi header followed by independently compressed per-chunk LZ4 frames. The metadata records each chunk frame's physical offset and length, so selected-region restore can seek directly to relevant chunks instead of decoding the whole payload.

Inside a patch chunk, block changes are grouped by 16x16x16 chunk section:

- changed cells are stored as a 4096-bit mask, represented by 64 `long` words
- local section index is `(localY << 8) | (localZ << 4) | localX`
- old and new block states use section-local palettes
- old and new block-entity payloads use section-local palettes
- hidden changes are stored with a second section mask
- section fingerprints include `xxHash64` for fast comparison and `SHA-256` for durable identity
- entity diffs are stored per chunk with nullable old/new full NBT payloads

Snapshots use chunk-addressable LZ4 frames as well. The server thread copies compact Minecraft section palette data, and storage serialization happens later.

See [docs/storage-format.md](docs/storage-format.md) for the full layout and compatibility notes.

## Restore And Apply

Restore is a two-stage operation:

1. Prepare stage: file I/O, LZ4 decompression, patch/snapshot reads, block-state decoding, palette decode caching, restore planning, and batch construction happen off the server tick thread.
2. Apply stage: prepared chunk batches are applied on the server thread with explicit per-tick budgets and progress snapshots.

Restore planning prefers the cheapest valid path:

- same-lineage rollback uses reverse patch replay
- forward restore uses forward patch replay
- divergent branch restore replays back to a common ancestor and forward to the target
- whole-dimension root fallback uses tracked baseline chunks; direct root rollback replays only touched baseline chunks
- snapshot fallback reconstructs from a checkpoint snapshot plus patch chain
- partial restore can read only selected chunk frames when a direct patch path exists
- non-direct partial restore reconstructs finite current and target states from snapshots, baseline chunks, and patches

The apply layer chooses the safest fast path per prepared section:

- sparse work uses direct chunk/section writes
- dense sections use native section loops
- full or very dense safe sections can use container rewrite
- unsafe cases, block entities, POI states, missing access, or unloaded sections fall back to safer paths

For high-throughput history operations, Lumi uses adaptive apply budgets. The current tick budget scales with operation progress and recent cost pressure. A rolling p95 pressure window reduces throughput after expensive ticks and slowly raises it when ticks stay cheap.

## Performance Tricks

Lumi's important performance work is mostly structural:

- Chunk and section addressing keeps selection, storage, diff, restore, and overlay work bounded.
- Section change masks avoid expanding sparse edits into full 4096-cell loops.
- Patch metadata enables seek-based selected-chunk reads.
- Section fingerprints let diff skip equal patch sections before loading full state.
- Block-state palette decode caches avoid parsing identical NBT states repeatedly.
- Prepared apply batches keep JSON, LZ4, and block-state decoding off the tick-thread apply path.
- Dense section rewrites build a replacement `PalettedContainer` and swap its internal data when runtime access is available.
- Sparse direct writes coalesce heightmap, light, redstone, section-packet, and block-entity work.
- Redstone replay writes final saved states first, then drains a bounded neighbor-update phase only for relevant signal transitions.
- Light checks are deferred into a separate `light-refresh` follow-up operation.
- Large overlays switch from exact per-block rendering to bounded section-volume boxes.
- Overlay geometry is section-batched, GPU-uploaded under a per-frame budget, and culled by render distance.
- Isometric previews project the 8 bounds corners through a fixed isometric rotation, then choose scale, offset, and image resolution from the projected span.

Hard runtime rule: JSON parsing, LZ4 decompression, and block-state decoding must not run on the tick-thread apply path.

## Reliability Guarantees

- Only one world operation runs per world at a time.
- Long operations publish progress through `OperationSnapshot` and Minecraft bossbars.
- Save manifests are written only after patch payloads and metadata exist.
- Save/amend operation drafts are isolated from new live edits.
- Recovery WAL corruption or truncation quarantines the damaged WAL and salvages the latest valid draft when possible.
- Malformed `world-origin.json` is quarantined and regenerated from the current world instead of blocking the UI.
- Existing pre-Lumi worlds without a completed Lumi backup show a one-time alpha backup warning before opening. Fresh worlds created through Lumi are marked and skip that warning.
- Archive import validates paths, ids, sizes, and payload digests before promotion.
- Cleanup is conservative and does not delete referenced history or baseline chunks.
- Logs are part of the support surface for capture, save, restore, recovery, apply, light, and load diagnostics.

## User Flow

1. Open a local singleplayer save.
2. Press `U` to open the current Build History workspace when the dimension project is available.
3. Build normally. Lumi captures supported player and builder-tool edits.
4. Use `Left Alt+Z` and `Left Alt+Y` for live undo/redo.
5. Use `R` for quick rollback of unsaved work, or selected-area rollback when a Lumi region selection is active.
6. Use `Left Alt+S` for quick save.
7. Use `Save build` for the full save screen.
8. Open a save to restore, compare, rename, soft-delete, branch, or partially restore from it.
9. Hold a wooden sword in a Lumi workspace to create runtime selection bounds for partial restore.
10. Use `Branches` for alternate directions and `Import / Export` for portable history packages.

Keybindings are remappable in Minecraft controls. The Lumi action button defaults to `Left Alt`; changing it changes the default Lumi chords.

## Build

Target stack:

- Minecraft `1.21.11`
- Fabric Loader `0.19.2`
- Java `21`
- Fabric API `0.141.3+1.21.11`
- owo-lib `0.13.0+1.21.11`
- cloth-config `21.11.153`
- lz4-java `1.8.1`

Build the mod:

```powershell
.\gradlew.bat build
```

Run the development client:

```powershell
.\gradlew.bat runClient
```

Run unit tests:

```powershell
.\gradlew.bat test
```

Run the test-client profile:

```powershell
.\scripts\run-test-client.ps1
```

The default test-client profile installs a small Fabric `1.21.11` builder-tool stack for local validation: Fabric API, WorldEdit, and a pinned `Axiom-5.4.1-for-MC1.21.11.jar` Modrinth file. The broader performance-mod stack is available with:

```powershell
.\scripts\run-test-client.ps1 -FullStack
```

## Runtime Testing

From a singleplayer world with cheats enabled:

```mcfunction
/lumi testing smoke
/lumi testing singleplayer
```

`/lumi testing smoke` runs the shorter project smoke path. It validates bootstrap storage, pre-mod backup metadata, snapshot content refs, section-indexed patch reads, capture, save/amend, branch/export, partial restore, full restore, integrity, and cleanup.

`/lumi testing singleplayer` runs the broad runtime suite. It covers real save/restore/undo/redo paths, branch/share/archive flows, partial restore, entity history, water/TNT/redstone/piston fixtures, preview fulfillment, integrity, cleanup, and prepared-apply diagnostics.

Runtime reports are written under:

```text
<world>/lumi/test-logs/
```

## Diagnostics

Useful JVM flags:

```text
-Dlumi.debug=true
-Dlumi.startupProfile=true
-Dlumi.loadLog=true
-Dlumi.lightLog=true
-Dlumi.blockApplyLog=true
```

`-Dlumi.loadLog=true` writes `logs/lumi-load.log` and also enables focused light and block-apply logs. Start with `type="summary"` rows, then inspect `type="span"` and `type="operation-metrics"` rows for expensive areas.

## Scope

Current scope:

- singleplayer and integrated-server first
- dedicated-server mutation actions behind access control
- menu-first product flow, with commands limited to diagnostics/help/testing
- project, version, branch, compare, restore, partial restore, recovery, cleanup, import/export, and merge workflows
- optional capture support for supported external builder tools

Not current scope:

- full multiplayer collaboration semantics
- server-side visual preview rendering
- command-first replacement for the UI workflows

## Documentation

- [User guide](docs/user-guide.md)
- [Commands](docs/commands.md)
- [Architecture](docs/architecture.md)
- [Storage format](docs/storage-format.md)
- [Development](docs/development.md)
- [Module map](modules.md)
- [Maintenance guide](docs/maintenance-guide.md)
- [Commit policy](docs/commit-policy.md)
- [Test client profile](docs/test-client.md)

## License

Licensed under [GPL-3.0](LICENSE).
