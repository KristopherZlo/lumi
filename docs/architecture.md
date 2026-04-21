# Architecture

## Purpose

Lumi is a singleplayer-first Fabric mod that gives Minecraft builders a project-oriented history workflow. The codebase is organized to keep user-facing domain rules separate from Minecraft engine integration, file persistence, and client UI.

The architecture is intentionally optimized around three requirements:

- history operations must remain reliable after crashes or interrupted sessions
- long save and restore work must avoid freezing the server tick
- the UI must expose progress as operations, not as fake instant actions

## Layered design

### Bootstrap layer

`io.github.luma.LumaMod` wires the mod into Fabric events. It registers commands, bootstraps shared world-origin metadata on integrated-server start, advances world operations once per server tick, flushes idle capture sessions, and persists active sessions on server shutdown.

### Domain model layer

`src/main/java/io/github/luma/domain/model` contains immutable records and focused mutable runtime structures used by the domain services.

Important model groups:

- project identity and settings: `BuildProject`, `ProjectVariant`, `ProjectVersion`, `ProjectSettings`
- world bootstrap metadata: `WorldOriginInfo`
- history payloads: `StoredBlockChange`, `StatePayload`, `PatchMetadata`, `SnapshotData`, `RecoveryDraft`
- user-visible summaries: `ChangeStats`, `PendingChangeSummary`, `VersionDiff`, `MaterialDeltaEntry`
- operation state: `OperationHandle`, `OperationProgress`, `OperationSnapshot`, `OperationStage`, `WorkspaceHudSnapshot`
- mutable capture runtime: `TrackedChangeBuffer`

### Domain service layer

`src/main/java/io/github/luma/domain/service` owns business workflows and orchestration.

Key services:

- `ProjectService`: create, load, and update projects
- `ProjectService`: also owns world-origin bootstrap and automatic `WORLD_ROOT` creation for dimension workspaces
- `ProjectArchiveService`: export stable project history to zip archives and import it back into project storage
- `VersionService`: save tracked edits as versions, amend the active head, and enforce snapshot policy
- `RestoreService`: build restore plans, decode world-root baseline restores, and prepare chunk batches
- `RecoveryService`: restore, persist, or discard interrupted tracked work
- `VariantService`: branch creation and branch switching
- `DiffService`: reconstruct version or live-world differences using structured state payload comparison before formatting UI-facing diff entries
- `PreviewService`: generate non-blocking preview images
- `ProjectIntegrityService`: validate storage consistency

These services should express product rules, not raw Minecraft side effects or raw file layouts.

### Minecraft adapter layer

`src/main/java/io/github/luma/minecraft` contains code that touches Minecraft engine APIs directly.

Important adapters:

- `HistoryCaptureManager`: captures tracked player actions, explosives, explosions, falling blocks, fluids, fire, growth, piston movement, and selected block-changing mob mutations into per-project buffers
- `WorldMutationContext`: prevents restore application from being re-captured as tracked history
- `WorldOperationManager`: runs async preparation plus completed-first chunk-queue dispatch on the server tick
- `GlobalDispatcher`, `LocalQueue`, `ChunkBatch`, `SectionBatch`, and `EntityBatch`: chunk-oriented operation runtime
- `BlockChangeApplier`: commits section blocks, block entities, and entity batches in bounded steps
- `LumaCommands`: fallback command interface

### Storage layer

`src/main/java/io/github/luma/storage` and `storage/repository` own the on-disk layout and persistence.

Important boundaries:

- `ProjectLayout` is the single source of truth for project-relative paths
- metadata repositories read and write lightweight manifests
- payload repositories read and write compressed binary history data
- `ProjectArchiveRepository` owns zip archive manifests and file-copy boundaries for history import/export
- `StorageIo` owns low-level atomic-write and NBT binary helpers

### Client UI layer

`src/client/java/io/github/luma/ui` follows a `Screen + Controller + ViewState` structure.

Responsibilities are split as follows:

- screens keep transient UI state and rendering
- controllers invoke services and translate failures into status keys
- view-state records provide immutable inputs to the rendering layer
- tab builders keep larger screen sections isolated
- `LumaScreen` ensures Lumi screens never pause the game
- `WorkspaceHudCoordinator` owns the always-on HUD overlay and action-bar progress surface
- `CompareOverlayRenderer` renders a client-side compare overlay through blocks, keeps diff data separate from visibility, and prioritizes the nearest changed blocks to the current camera position

## Core runtime flows

## Capture flow

1. A Minecraft mixin intercepts a block mutation.
2. `WorldMutationContext` accepts only explicit player, explosive, explosion, falling-block, selected mob, and other targeted mutation scopes.
3. `HistoryCaptureManager` finds matching projects for the block position.
4. Whole-dimension workspaces capture baseline chunks on first touch.
5. A per-project `TrackedChangeBuffer` merges the change by packed block position.
6. Idle or dirty sessions are flushed into recovery storage.

Important invariants:

- the first observed old state is preserved
- the latest new state wins
- no-op edits are removed from the buffer
- restore-originated mutations never re-enter tracked history

## Save flow

1. UI or commands call `VersionService.startSaveVersion(...)`.
2. The live in-memory buffer is consumed first; persisted recovery storage is only a fallback.
3. The draft is moved into isolated operation-draft storage while async save work runs.
4. `WorldOperationManager` executes background preparation off the tick thread.
5. `PatchDataRepository` writes the binary patch payload.
6. `PatchMetaRepository` writes the lightweight patch index.
7. `VersionService` evaluates snapshot policy and optionally asks `SnapshotWriter` for a checkpoint snapshot. Whole-dimension projects use root/cadence checkpoints, not per-save volume snapshots.
8. `VersionRepository` writes the final version manifest only after payload files exist.
9. Preview generation runs later on a separate low-priority executor.

For automatic dimension workspaces, the history chain starts with a metadata-backed `WORLD_ROOT` version. It records the world origin context instead of a normal patch/snapshot payload.

## Restore flow

1. UI calls `RestoreService.restore(...)`.
2. The client requires explicit user confirmation before restoring an `INITIAL` or `WORLD_ROOT` version.
3. The confirmation UI shows a lightweight `RestorePlanSummary` with mode, branch, base version, target version, and affected chunk count before any world mutation starts.
4. Active capture is frozen and an optional safety checkpoint is written first.
5. When the target lies on the current active variant lineage, `RestoreService` prefers a direct patch replay path, including restores to `WORLD_ROOT`:
   reverse patch application for ancestor restores, forward patch application for descendant restores, plus rollback of any pending draft.
6. If direct replay is not valid and the target is `WORLD_ROOT`, restore falls back to tracked baseline chunks for the current workspace. Generator regeneration remains blocked when the stored origin fingerprint does not match the current world.
7. If direct replay is not valid for a normal version, `RestoreService` falls back to the anchor snapshot plus patch-chain restore plan.
8. Baseline gaps are added only for the snapshot-based whole-dimension fallback path.
9. Prepared placements are collapsed by final block position before tick-thread application.
10. `WorldOperationManager` converts prepared chunk payloads into `ChunkBatch` structures, drains completed local queues first, and only falls back to incomplete queues when the FAWE-style `64 chunks / 25 ms` thresholds are hit.
11. Chunk commit order is fixed to section blocks -> block entities -> entity batch.
12. Completion resets the active variant head to the restored version, clears the pre-restore draft, writes a recovery journal entry, and leaves operation state available to the UI briefly.
13. Resetting the active variant head does not remove later version files. The UI keeps detached versions visible.

Hard rule: JSON parsing, LZ4 decompression, and block-state decoding must never happen on the tick-thread apply path.

## Recovery flow

Recovery is designed to survive crash-like exits without rewriting one large draft file on every change.

Current strategy:

- active sessions live in memory as `TrackedChangeBuffer`
- periodic flushes append to `recovery/draft.wal.lz4`
- compaction rewrites the latest state into `recovery/draft.bin.lz4`
- in-progress save/amend drafts move to `recovery/operation-draft.bin.lz4` so new edits can start a separate live draft
- restore/save recovery actions reuse the same operation model as save and restore

## Threading model

Lumi uses a strict two-stage operation pattern:

- prepare stage: file I/O, compression, decompression, snapshot capture, and decode work off-thread
- apply stage: bounded world mutation batches on the server thread

Current guarantees:

- only one world operation runs per world at a time
- the world-operation executor is single-threaded and low priority
- preview generation samples world state on the server thread and writes PNG output on a separate low-priority executor
- operation progress is observable through `OperationSnapshot`
- client HUD state is polled separately from screen rendering so non-pausing menus, the top-right diff overlay, and the action-bar progress bar keep updating while screens are open

## Storage format summary

The current durable history format is schema v3.

Main files:

- `world-origin.json`: shared world seed/version/datapack/generator manifest for all dimension workspaces
- `exports/*.zip`: command-driven project history archives
- `versions/*.json`: version manifests
- `patches/<patchId>.meta.json`: patch metadata and chunk index
- `patches/<patchId>.bin.lz4`: patch payload
- `snapshots/<snapshotId>.bin.lz4`: checkpoint snapshot payload
- `recovery/draft.bin.lz4`: compacted recovery base
- `recovery/draft.wal.lz4`: append-only recovery log
- `recovery/operation-draft.bin.lz4`: isolated save/amend draft fallback
- `recovery/journal.json`: user-facing workflow log

See [storage-format.md](storage-format.md) for the exact layout.

## Logging and observability

The mod is expected to log the following at minimum:

- operation start, rejection, progress, completion, and failure
- world-origin bootstrap and root-history initialization
- capture buffer lifecycle changes
- restore plan summaries
- recovery compaction and draft deletion
- UI-triggered service failures that map to generic status text

There is also a project-scoped debug layer:

- `ProjectSettings.debugLoggingEnabled` turns on verbose tracing for one workspace
- `-Dlumi.debug=true` turns it on globally
- debug logs cover capture, save, restore, recovery, compare, compare overlay cache rebuilds, HUD refresh, and world-operation queue/application steps

Logs are part of the support surface. New background or storage work should not be introduced without meaningful logs.

## Testing strategy

The current test suite is organized around:

- model behavior such as `TrackedChangeBuffer`
- repository round-trips for patch, snapshot, and recovery storage
- repository round-trips for archive export/import boundaries
- service-level diff and history policy behavior
- project layout and storage path invariants
- recovery draft isolation between live capture and save/amend operations
- client-side performance regression tests for compare overlay selection, commit graph layout, and material delta summarization
- Fabric GameTest scaffolding for server and client smoke tests, with a production client GameTest task for headless CI

When extending history or storage behavior, update both tests and documentation in the same change.

## Extension rules

- Keep domain services narrow and explicit. If a class starts owning more than one reason to change, split it.
- Add new repository types instead of growing one repository into a mixed metadata and payload god object.
- Prefer immutable records for persisted state and summaries.
- Restrict mutable state to clearly bounded runtime coordinators such as capture buffers or active operations.
- Preserve the menu-first product flow. Commands should remain a fallback, not the primary UX model.
