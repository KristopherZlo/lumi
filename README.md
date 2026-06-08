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

The mod is singleplayer-first. Lumi capture and mutating actions activate only when the current player has the required admin/operator-level permission; in singleplayer this follows the world's command permission state, and dedicated servers use the same access-control layer.

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
- Manual saves, quick saves, amend latest save, restore checkpoints, auto checkpoints before configured large external edits, and semantic save kinds such as `MERGE` and `AUTO_CHECKPOINT`.
- Live undo/redo for recent tracked actions with default `Left Alt+Z` and `Left Alt+Y`; quick rollback with default `R`; quick save with default `Left Alt+S`; hotkey help with default `Left Alt+I`.
- Runtime wooden-sword region selection for partial restore and selected-area quick rollback.
- Version compare for saved versions, branches, and the current build, with world overlays for changed blocks.
- Full restore, quick rollback, return-before-restore, and partial restore in `Only selected area` or `Everything except selection` mode.
- Branch creation, branch switching, local branch merge, variant import/export, full project archives, imported review projects, and safety checks for imported executable world-state data.
- Same-Minecraft-version update checks at client startup, with a styled clickable in-world chat notice, GitHub issue/report and release-page links, plus Build History and More update modals backed by the Lumi website plus GitHub raw manifest fallback.
- Crash recovery through durable working drafts, write-ahead log compaction, operation-draft isolation, recovery journals, and restore return points.
- Capture of non-player entity spawn/remove/update with persistent NBT payloads for supported builder-facing entities.
- Pre-Lumi world checkpoint gate with visible progress and an opt-in vanilla Edit World restore action for worlds that capture compressed pre-Lumi chunk payloads.
- Optional WorldEdit/FAWE/Axiom capture without hard runtime dependencies; generic stack-trace fallback capture is opt-in for diagnostics.
- Client-side textured isometric previews that skip hidden internal faces, plus large-diff overlays prepared away from the render thread.
- Runtime diagnostics, load logs, block-apply logs, light-refresh logs, smoke tests, and a broad singleplayer regression suite.

## Diagnostic Telemetry

Lumi includes opt-out diagnostic telemetry for technical failure analysis.

- Enabled by default under the Lumi privacy notice and first-run notice
- Collects crashes, operation failures, rejected Lumi actions, sanitized edge-case errors, and severe performance outliers
- Does not collect usernames, UUIDs, world names, project names, seed, coordinates, raw logs, raw file paths, or raw NBT
- Can be disabled in Settings at any time
- Uses a rotating installation id and keeps raw server-side events for 90 days
- Receives transport IP for rate limiting only; IP is not stored in event rows

The client telemetry queue is local and bounded, and the ingest backend lives in `telemetry-backend/`.

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

1. A mixin hook, optional integration, opt-in tool-stack fallback, direct section fallback, or entity lifecycle hook observes a world mutation.
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
- Ambient changes without a causal builder action are rejected. Causal secondary fallout can be stored hidden so restore/undo/redo can replay it without adding builder-facing noise.

## Save Format

Project data lives under:

```text
<world>/lumi/projects/<project>.mbp/
```

The current storage model is patch-first:

- `versions/*.json` are lightweight manifests.
- `patches/*.meta.json` stores patch metadata, the chunk index, visible section bounds metadata, and the entity old/new chunk index.
- `patches/*.bin.lz4` stores chunk-addressable binary patch payloads.
- `snapshots/*.bin.lz4` stores checkpoint full-state anchors.
- `cache/baseline-chunks/` stores first-touch whole-dimension baseline chunks.
- `cache/content/` stores content-addressed immutable section payload blobs.
- `recovery/draft.bin.lz4` and `recovery/draft.wal.lz4` store crash-safe drafts.
- `recovery/operation-draft.bin.lz4` isolates a save/amend draft while the async operation is running.

New patch payloads use binary schema v9. Each file has a small Lumi header followed by independently compressed per-chunk LZ4 frames. The metadata records each chunk frame's physical offset and length, visible section fingerprints for previews, and entity old/new chunk membership, so selected-region restore can seek directly to relevant chunks instead of decoding the whole payload.

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
- whole-dimension root fallback uses tracked baseline chunks; direct root rollback reads exact baseline state only for replayed block positions plus bounded redstone/mechanism reconciliation scope
- snapshot fallback reconstructs from a checkpoint snapshot plus patch chain
- partial restore can read only selected chunk frames when a direct patch path exists
- non-direct partial restore reconstructs finite current and target states from snapshots, baseline chunks, and patches; direct partial restore switches to this target-state path when redstone/mechanism states are involved so writes stay inside the selected mode
- bounded-project partial restore clips selected-area target-state planning to the project bounds before requiring stored payloads
- partial restore applies the chosen save state into the world and pending draft without moving the branch head or creating a save

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
- Direct restores to `Initial` and `WORLD_ROOT` replay sparse exact-root positions and only expand touched redstone/mechanism sections up to a fixed reconciliation cap.
- Direct restore and quick rollback resolve extra signal-source redstone/mechanism halo targets off-thread by exact position, clipped to project and selected-area bounds, instead of decoding broad chunks on the tick-thread apply path.
- Patch metadata enables seek-based selected-chunk reads.
- Section fingerprints let diff skip equal patch sections before loading full state.
- Block-state palette decode caches avoid parsing identical NBT states repeatedly.
- Prepared apply batches keep JSON, LZ4, and block-state decoding off the tick-thread apply path.
- Dense section rewrites build a replacement `PalettedContainer` and swap its internal data when runtime access is available.
- Sparse direct writes coalesce heightmap, light, redstone, section-packet, and block-entity work.
- Redstone replay writes final saved states first, then drains a bounded neighbor-update phase only for relevant signal transitions.
- Light checks are deferred into a separate `light-refresh` follow-up operation.
- Large overlays switch from exact per-block rendering to bounded section-volume boxes.
- Overlay geometry is section-batched, GPU-uploaded and drawn under per-frame budgets, prioritized near the camera, and culled by render distance.
- Isometric previews project the 8 bounds corners through a fixed isometric rotation, then choose scale, offset, and image resolution from the projected span.

Hard runtime rule: JSON parsing, LZ4 decompression, and block-state decoding must not run on the tick-thread apply path.

## Reliability Guarantees

- Only one world operation runs per world at a time.
- Long operations publish progress through `OperationSnapshot` and Minecraft bossbars.
- Save manifests are written only after patch payloads and metadata exist.
- Atomic storage writes flush the temporary file before publishing it, so interrupted writes do not expose partial manifests or payloads.
- Save/amend operation drafts are isolated from new live edits.
- Recovery WAL corruption or truncation quarantines the damaged WAL and salvages the latest valid draft when possible.
- Malformed `world-origin.json` is quarantined and regenerated from the current world instead of blocking the UI.
- Existing pre-Lumi worlds without a completed Lumi checkpoint show an alpha gate before opening. Pressing `Got it!` writes a quick manifest-only safety checkpoint by default, so the world can open without scanning every region chunk. Setting `-Dlumi.preModBackup.maxMiB=<positive>` enables the older compressed chunk payload capture with visible progress; interrupted attempts are discarded or rolled back to the last consistent checkpoint before retrying. Fresh worlds created through Lumi are marked and skip that gate. The vanilla Edit World screen exposes `RESTORE FROM LUMI BACKUP` only when restorable backup chunks exist; that restore writes the pre-Lumi chunk payloads back into region files and leaves Lumi project commits on disk for later inspection.
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

The default test-client profile installs a small Fabric `1.21.11` builder-tool stack for local validation: Fabric API, WorldEdit, and a pinned `Axiom-5.4.1-for-MC1.21.11.jar` Modrinth file. Test-client launchers also rewrite `options.txt` to mute audio, enable v-sync, and cap `maxFps` at `120` so debug sessions do not idle at uncapped GPU load. The broader performance-mod stack is available with:

```powershell
.\scripts\run-test-client.ps1 -FullStack
```

Run the alpha release gate wrapper:

```powershell
.\scripts\run-alpha-release-check.ps1
```

The wrapper runs the unit coverage ratchet, server/client GameTests, focused structure/external-tool/crash-safety runtime modes, runtime load comparison, and crash harness. For local iteration, `-SkipRuntimeLoad` and `-SkipCrashHarness` can shorten the run.

## Runtime Testing

Runtime testing commands are hidden in normal builds and launches. Start the
game with `-Dlumi.testing.enabled=true` or `LUMI_TESTING_ENABLED=true` to expose
them in a singleplayer world with cheats enabled. The Gradle test-client and
client GameTest profiles set this flag automatically.

```mcfunction
/lumi testing smoke
/lumi testing singleplayer
/lumi testing player-flow
/lumi testing crash-safety
/lumi testing external-tools
```

`/lumi testing smoke` runs the shorter project smoke path. It validates bootstrap storage, pre-open checkpoint metadata, snapshot content refs, section-indexed patch reads, capture, save/amend, branch/export, partial restore, full restore, integrity, and cleanup.

`/lumi testing singleplayer` runs the broad runtime suite. It covers real save/restore/undo/redo paths, branch/share/archive flows, partial restore, entity history, fuse-time and redstone-powered TNT undo, water/TNT/redstone/piston fixtures, preview fulfillment, integrity, cleanup, and prepared-apply diagnostics.

`/lumi testing player-flow` runs the broad suite from ordinary terrain: it prepares a smooth-stone platform near the player, creates the test project over that prepared area, and verifies restore returns to that platform baseline. It fails on flat chunk generators.

`/lumi testing crash-safety` runs the restart-focused smoke path used by the crash harness, and `/lumi testing external-tools` adds focused WorldEdit/Axiom-source capture checks.

Coverage is ratcheted with JaCoCo through:

```powershell
.\gradlew.bat verifyCoverageRatchet
```

The checked-in baseline lives at `config/coverage-baseline.properties` and should only move upward or be intentionally refreshed after reviewing the report.

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
-Dlumi.clientLoadLog=true
-Dlumi.lightLog=true
-Dlumi.blockApplyLog=true
-Dlumi.partialRestoreLog=true
-Dlumi.externalStackDetection=true
```

`-Dlumi.loadLog=true` writes `logs/lumi-load.log` and also enables focused light and block-apply logs. Start with `type="summary"` rows, then inspect `type="span"` and `type="operation-metrics"` rows for expensive areas.

`-Dlumi.partialRestoreLog=true` writes `logs/lumi-partial-restore.log` only for `Only selected area` partial restores. It records the selected live blocks before restore, the planned target changes, and post-apply targets that still differ from the requested state.
When launching through `scripts/run-test-client.ps1`, pass this as a JVM flag after the wrapper parameters, for example `.\scripts\run-test-client.ps1 -Dlumi.partialRestoreLog=true`, or use `-JvmArgs` for multiple flags.

`-Dlumi.clientLoadLog=true` writes `logs/lumi-client-load.log` from the client process with CPU, heap/direct-buffer memory, GC, frame-pressure, OpenGL renderer, and optional `nvidia-smi` GPU utilization/memory samples. The test-client and client GameTest profiles enable it automatically.

High-volume capture skip diagnostics are sampled and then summarized per project, source, and reason so debug mode does not turn ambient world ticks into sustained disk and CPU load.

`-Dlumi.externalStackDetection=true` enables the conservative fallback that samples Java stack frames to recognize unsupported builder tools. Leave it disabled during normal play and broad test-client runs; explicit WorldEdit/FAWE/Axiom integrations do not require it.

Update checks run on client startup, refresh cached release metadata when the network is available, and are reused by the world-entry chat notice, Build History update modal, and the manual `Check updates` action at the bottom of More. They can be overridden for local testing with:

```text
-Dlumi.update.primaryUrl=<manifest-url>
-Dlumi.update.fallbackUrl=<manifest-url>
-Dlumi.update.disabled=true
```

The shipped fallback manifest is `updates/lumi-fabric.json`; keep it in sync with public release metadata.

## Scope

Current scope:

- singleplayer and integrated-server first
- dedicated-server mutation actions behind access control
- menu-first product flow, with commands limited to diagnostics/help plus opt-in runtime testing
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
