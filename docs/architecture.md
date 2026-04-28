# Architecture

## Purpose

Lumi is a singleplayer-first Fabric mod that gives Minecraft builders a project-oriented history workflow. The codebase is organized to keep user-facing domain rules separate from Minecraft engine integration, file persistence, and client UI.

The architecture is intentionally optimized around three requirements:

- history operations must remain reliable after crashes or interrupted sessions
- long save and restore work must avoid freezing the server tick
- the UI must expose progress as operations, not as fake instant actions

## Layered design

### Bootstrap layer

`io.github.luma.LumaMod` wires the mod into Fabric events. It registers diagnostic and local testing commands, schedules shared world-origin metadata bootstrap on a low-priority background thread after the first player has entered the world and a short idle delay has elapsed, advances world operations once per server tick, advances the singleplayer runtime test runner, flushes idle capture sessions, and persists active sessions on server shutdown.

### Domain model layer

`src/main/java/io/github/luma/domain/model` contains immutable records and focused mutable runtime structures used by the domain services.

Important model groups:

- project identity and settings: `BuildProject`, `ProjectVariant`, `ProjectVersion`, `ProjectSettings`
- world bootstrap metadata: `WorldOriginInfo`
- history payloads: `StoredBlockChange`, `StoredEntityChange`, `StatePayload`, `EntityPayload`, `PatchMetadata`, `SnapshotData`, `RecoveryDraft`
- user-visible summaries: `ChangeStats`, `PendingChangeSummary`, `VersionDiff`, `MaterialDeltaEntry`
- operation state: `OperationHandle`, `OperationProgress`, `OperationSnapshot`, `OperationStage`, `WorkspaceHudSnapshot`
- mutable capture runtime: `TrackedChangeBuffer`, `CaptureSessionState`
- mutable live action runtime: `UndoRedoAction`, `UndoRedoActionStack`

### Domain service layer

`src/main/java/io/github/luma/domain/service` owns business workflows and orchestration.

Key services:

- `ProjectService`: create, load, and update projects
- `ProjectService`: also owns world-origin bootstrap and automatic `WORLD_ROOT` creation for dimension workspaces
- `ProjectArchiveService`: export stable project history to zip archives and import it back into project storage
- `HistoryShareService`: export variant-scoped history packages, import them back as review projects for the same project lineage, and delete imported review packages after lineage validation
- `ProjectCleanupService`: compute safe cleanup candidates from reachable history metadata and active operation state
- `VersionService`: save tracked edits as versions, amend the active head, and enforce snapshot policy
- `RestoreService`: build restore plans and orchestrate prepared chunk batches through Minecraft-layer preparers
- `RecoveryService`: restore, persist, or discard interrupted tracked work
- `VariantService`: branch creation and branch switching
- `VariantMergeService`: compare imported variant lineage against a local target variant, group overlapping conflicts into chunk-connected zones, and write merged saves through the normal patch-first history path
- `DiffService`: reconstruct version or live-world block and entity differences using structured state payload comparison before formatting UI-facing diff entries
- `VersionLineageService`: centralizes reachable-version filtering, common ancestor lookup, ancestor checks, and ancestor-to-head path resolution used by restore, diff, and merge workflows
- `PreviewCaptureRequestService`: queue preview capture jobs without blocking save durability
- `PreviewCaptureRequestRepository`: persist preview capture requests so the server can queue work and the client can render later
- `ProjectIntegrityService`: validate storage consistency

These services should express product rules, not raw Minecraft side effects or raw file layouts.

### Minecraft adapter layer

`src/main/java/io/github/luma/minecraft` contains code that touches Minecraft engine APIs directly.

Important adapters:

- `HistoryCaptureManager`: facade for mixin capture entrypoints; it captures explicit tracked actions immediately, coordinates per-project causal envelopes, and drains dirty-chunk stabilization before drafts are persisted, consumed, or selected for live undo/redo
- `CaptureSessionRegistry`: owns active capture buffers, active session state, dirty-session flags, and live-draft flush fingerprints for the capture facade
- `CaptureDiagnosticsRegistry`: owns capture diagnostics state used for accepted-mutation traces and progress summaries
- `TrackedProjectCatalog`: loads active project metadata, caches tracked-project membership, and exposes the dimension/chunk index used by capture matching
- `ProjectTrackingIndex`: caches dimension/chunk membership for active projects so block capture does not scan every project for every mutation
- `UndoRedoHistoryManager`: keeps the in-memory per-project undo and redo action stacks that power live undo/redo and the temporary recent-action overlay, and it can absorb nearby short-lived secondary fallout or reconciled stabilization deltas into the latest builder action
- `CapturePersistenceCoordinator`: owns the low-priority maintenance executor for async baseline writes and coalesced recovery draft flushes
- `ChunkSnapshotCaptureService`: copies loaded chunk section palettes, real block-entity tags, and entity snapshots into immutable compact payloads on the server thread
- `SnapshotCaptureService`: marshals checkpoint snapshot capture onto the server thread and leaves serialization/persistence to storage writers
- `ChunkSectionOwnershipRegistry`: keeps a weak chunk-section owner index for direct section mutation fallback capture, with per-chunk section-array caching so repeated chunk reads during spawn generation do not re-register every section; direct section fallback resolves that server owner before stack inspection, so client chunk loading and unowned generation sections do not pay external-tool stack sampling costs
- `WorldMutationCapturePolicy`, `EntityMutationCapturePolicy`, and `PersistentBlockStatePolicy`: filter runtime-only block/entity transitions and normalize piston animation states before they become drafts, undo/redo actions, snapshots, or restore placements; unknown-stack entity fallback detection is scoped to builder-relevant persistent entity types so ordinary mob movement does not sample external-tool stacks
- `SessionStabilizationService`: compares session-start chunk baselines to the current world and composes a stabilized diff on top of the current pending chunk state for dirty envelope chunks
- `WorldMutationContext`: prevents restore application from being re-captured as tracked history
- `LumaAccessControl`: centralizes the operator/cheats gate for diagnostic commands, UI entry points, and dedicated-server tracked world actions
- `WorldOperationManager`: runs async preparation plus completed-first chunk-queue dispatch on the server tick with adaptive block budgets and bounded block-entity/entity passes
- `WorldChangeBatchPreparer` and `SnapshotBatchPreparer`: convert persisted block/entity changes and snapshot payloads into tick-ready prepared batches before apply begins
- `GlobalDispatcher`, `LocalQueue`, `ChunkBatch`, `SectionBatch`, and `EntityBatch`: chunk-oriented operation runtime, including entity spawn/remove/update batches
- `BlockChangeApplier`: commits section blocks, block entities, and entity batches in bounded steps
- `LumaCommands`: diagnostic command interface plus the singleplayer runtime test entry point
- `SingleplayerTestingService`: tick-driven integrated-world regression runner for real save, undo/redo, branch, export, and restore workflows, with chat progress and durable pass/fail logs
- `WorldBootstrapService`: runs startup-only world-origin and root-version metadata checks off the server-start path so storage scans do not delay initial world entry

### Optional integration layer

`src/main/java/io/github/luma/integration` contains optional integration contracts for external builder tools. These adapters must not create hard runtime dependencies on WorldEdit, FAWE, Axiom, or their client APIs.

`ExternalToolIntegrationRegistry` reports typed capabilities for detected tools:

- WorldEdit capabilities are enabled only when the corresponding WorldEdit API classes are present, such as edit-session events, local sessions, clipboard, and schematic formats.
- `OptionalIntegrationBootstrap` reflectively loads the WorldEdit edit-session tracker only when those capabilities are present. The tracker registers on WorldEdit's event bus and wraps `EditSession.Stage.BEFORE_CHANGE` extents. It records WorldEdit old/new block transitions directly under `WorldMutationSource.WORLDEDIT`, lazily serializing block-entity NBT only for block states that can own a block entity, while still keeping the mutation context active for Minecraft-level fallback capture.
- FAWE is reported as a detected fallback-capture tool when known FAWE classes or mod ids are present. Lumi does not claim FAWE selection, clipboard, or schematic APIs; block and entity history capture rely on the generic known-tool mutation fallbacks.
- `ExternalToolMutationOriginDetector` recognizes WorldEdit, FAWE, Axiom, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica, and Tweakeroo stack frames at Minecraft mutation boundaries without linking those tools. It first checks cached integration availability, and the direct chunk-section fallback also requires a known server section owner before asking for stack inspection, so vanilla startup, client chunk loading, and unowned world generation sections do not sample per-mutation stack traces. `WorldMutationCaptureGuard` prevents duplicate block records so the highest available hook wins: WorldEdit API extent, `Level#setBlock`, `LevelChunk#setBlockState`, then direct `LevelChunkSection#setBlockState`.
- Axiom capabilities are intentionally conservative. Lumi may report detection or a custom region API, but it does not claim selection, clipboard, or schematic support unless a stable API is available. Because Axiom does not expose a stable operation API, Lumi uses guarded server-side fallbacks: Axiom block-buffer packet applies are captured before Axiom mutates chunk sections directly with the same lazy block-entity capture rule, entity lifecycle/update hooks capture Axiom entity edits that reach Minecraft entity APIs, and otherwise untracked Axiom mutations can still be recorded from known-tool stack frames.
- Axion, AutoBuild, SimpleBuilding, and Effortless Building are reported as fallback-capture tools when their mod ids or known classes are present. Litematica and Tweakeroo are reported as player-driven placement tools because their normal printer/placement paths should be captured through player mutation context rather than as direct world editors.
- The fallback integration remains always available and represents Lumi's own world-tracking capture path.

External tool mutations use explicit `WorldMutationSource` values such as `WORLDEDIT`, `FAWE`, and `AXIOM` where stable tool identities exist. Other recognized builder tools are grouped under `EXTERNAL_TOOL` with an actor label such as `axion`, `autobuild`, or `litematica`.

### Storage layer

`src/main/java/io/github/luma/storage` and `storage/repository` own the on-disk layout and persistence.

Important boundaries:

- `ProjectLayout` is the single source of truth for project-relative paths
- metadata repositories read and write lightweight manifests
- payload repositories read and write compressed binary history data
- repositories do not depend on `ServerLevel`, block-state codecs, or apply-batch runtime types
- preview request repositories persist lightweight capture jobs for the client renderer
- `ProjectArchiveRepository` owns zip archive manifests and file-copy boundaries for history import/export
- `ProjectCleanupRepository` owns file scanning and deletion for conservative storage cleanup
- `StorageIo` owns low-level atomic-write and NBT binary helpers

### Client UI layer

`src/client/java/io/github/luma/ui` follows an `owo-ui Screen + Controller + ViewState` structure.

Responsibilities are split as follows:

- owo-ui screens keep transient UI state and rendering
- controllers invoke services and translate failures into status keys
- view-state records provide immutable inputs to the rendering layer
- lightweight summary controllers keep the project home, Branches, and Import / Export routes fast by avoiding diff, material, cleanup, diagnostics, archive scan, and merge-preview work on open
- `MergePreviewCache` runs Import / Export combine previews in the background and caches them by imported package and target branch while the screen is open
- `LumaScreen` extends owo-ui `BaseOwoScreen`, keeps Lumi menus non-pausing, and gives each route a code-driven `OwoUIAdapter`
- `CompareScreenSections`, `ProjectScreenSections`, focused Save details section builders, and `ShareMergeReviewSection` own repeated route section composition, while their screens keep route lifecycle, transient selection state, and action callbacks
- `ClientWorkspaceOpenService` opens the current workspace through a lightweight loading screen and schedules project metadata preparation away from the client tick that handled the key press
- `LumaUi` centralizes compact `FlowLayout`, `ScrollContainer`, `Sizing`, `Insets`, and `Surface` rules so screens avoid absolute positioning and keep layout predictable
- `ProjectWindowLayout` and `ProjectSidebarNavigation` keep the primary workspace tabs visible across Build History, Branches, Import / Export, Settings, and More. The sidebar highlights the active route and includes the external support link.
- `PreviewCaptureCoordinator` watches pending preview requests for the current dimension, runs the textured off-screen renderer on the client render thread through a local layered preview mesh builder, and trims empty transparent margins before storing the PNG
- obsolete tab-builder scaffolds have been removed; larger workflows now use dedicated screens and narrow view-state records instead of a shared project tab container
- the project home screen is now a Build History view with one primary action, `Save build`, plus one-click `See changes`, recent saves, `Branches`, and `More`
- dedicated screens isolate `Save`, `Save details`, `Branches`, `Import / Export`, `See Changes`, `Recovered work`, `Settings`, `Cleanup`, `Diagnostics`, and `Advanced` so the main project screen no longer carries rare or technical workflows
- `WorkspaceHudCoordinator` owns the optional top-right HUD overlay and action-bar progress surface
- project-facing screens poll lightweight operation snapshots every 10 client ticks so conflicting mutation buttons unlock as soon as the operation becomes terminal, while status text can stay visible briefly
- `CompareOverlayRenderer` renders a client-side compare overlay with a remappable hold-to-x-ray mode, keeps diff data separate from visibility, prioritizes the nearest exposed changed blocks to the current camera position, and draws exposed overlay faces through immediate end-main quads so translucent fill is flushed independently of the shared world buffer. Normal mode remains depth-tested so highlights do not show through blocks; x-ray mode deliberately disables depth testing.
- `CompareOverlayCoordinator` refreshes `current`-world compare overlays on the client tick so live edits appear in the active highlight without rebuilding the screen manually
- `RecentChangesOverlayRenderer` renders latest undo actions when the remappable Lumi overlay key is held, or redo actions while overlay key plus redo is held, when the compare overlay is not active. Dense action previews are selected from exposed changed blocks first so large fills do not disappear when the nearest raw blocks are internal, and fill alpha stays low enough for outlines to remain readable.
- the Import / Export route presents the normal flow: export history packages first, list importable zips from the game-root `lumi-projects` folder, import packages as review projects, optionally include preview PNGs in exports, delete imported review packages, resolve same-area zones, show zone overlays, and apply a combined save without cluttering Build History or Branches

## Core runtime flows

## Capture flow

1. A Minecraft mixin intercepts a block mutation or entity lifecycle/update mutation. External builder edits that bypass the normal `Level#setBlock` context can still be captured by the guarded Axiom block-buffer fallback, the `LevelChunk#setBlockState` known-tool fallback, or the direct `LevelChunkSection#setBlockState` fallback used by FAWE-style chunk placement.
2. `WorldMutationContext` accepts only explicit player, explosive, explosion, falling-block, selected mob, and other targeted mutation scopes.
3. `HistoryCaptureManager` finds matching projects for the block position or entity position through a cached dimension/chunk tracking index.
4. `WorldMutationCapturePolicy` drops piston animation sources, transient piston blocks, and runtime-only redstone state flips before they can enter drafts or live undo/redo.
5. Explicit root mutations define a session-local causal envelope. Chunk baselines inside that envelope are captured lazily when those chunks first need stabilization.
6. A per-project `TrackedChangeBuffer` merges explicit and targeted realtime changes by packed block position and entity UUID. For entities, the first old full-NBT payload and latest new full-NBT payload win.
7. Ambient fallout such as fluid spread and falling blocks only mark dirty chunks inside that causal envelope for deferred stabilization.
8. `SessionStabilizationService` reconciles those dirty chunks against the current world before snapshotting, flushing, saving, freezing, consuming the draft, or choosing a live undo/redo action, and exposes the reconciled delta so undo/redo can attach it to the latest nearby action.
9. Idle or dirty sessions are flushed into recovery storage only when the live buffer fingerprint changed since the last queued draft flush.
10. Authorized player-root actions append into the in-memory undo/redo stack, and nearby short-lived secondary fallout plus deferred fluid/falling-block deltas can join that same action, so Lumi can replay the practical builder step backward or forward without using the tick-thread decode path.
11. In integrated singleplayer worlds, explicit builder actions are allowed into capture and undo/redo immediately even if the permission frame is not operator-shaped yet; dedicated servers keep the operator gate.

Important invariants:

- the first observed old state is preserved
- the latest new state wins
- no-op edits are removed from the buffer
- entity spawn/remove/update diffs use nullable old/new payloads and are applied through `EntityBatch`
- restore-originated mutations never re-enter tracked history
- undo/redo reuses prepared world operations and then adjusts the pending draft separately, so internal replay does not create duplicate capture events

## Save flow

1. UI controllers call `VersionService.startSaveVersion(...)`.
2. The live in-memory buffer is consumed on the server thread first; persisted recovery storage is only a fallback.
3. The draft is moved into isolated operation-draft storage while async save work runs.
4. `WorldOperationManager` executes background preparation off the tick thread.
5. `PatchDataRepository` writes the binary patch payload.
6. `PatchMetaRepository` writes the lightweight patch index.
7. `VersionService` evaluates snapshot policy and optionally asks `SnapshotCaptureService` for a server-thread checkpoint capture, then persists the prepared payload through the snapshot writer. Whole-dimension projects use root/cadence checkpoints, not per-save volume snapshots.
8. `VersionRepository` writes the final version manifest only after payload files exist.
9. Amend-on-head merges both block and entity changes into the replacement draft before writing the amended version.
10. Preview generation stores a lightweight request after save durability completes.
11. The client preview coordinator later builds a local layered preview mesh, renders a textured isometric frame into an off-screen target, and writes the PNG plus preview metadata.

For automatic dimension workspaces, the history chain starts with a metadata-backed `WORLD_ROOT` version. It records the world origin context instead of a normal patch/snapshot payload.

## Restore flow

1. UI calls `RestoreService.restore(...)`.
2. The client requires explicit user confirmation before restoring an `INITIAL` or `WORLD_ROOT` version.
3. The confirmation UI shows a lightweight `RestorePlanSummary` with mode, branch, base version, target version, and affected chunk count before any world mutation starts.
4. Active capture is frozen and an optional safety checkpoint is written first.
5. When the target lies on the current active variant lineage, `RestoreService` prefers a direct patch replay path, including shared branch-base ancestors and restores to `WORLD_ROOT`:
   reverse patch application for ancestor restores, forward patch application for descendant restores, plus rollback of any pending draft.
6. If direct replay is not valid and the target is `WORLD_ROOT`, restore falls back to tracked baseline chunks for the current workspace. Generator regeneration remains blocked when the stored origin fingerprint does not match the current world.
7. If direct replay is not valid for a normal version, `RestoreService` falls back to the anchor snapshot plus patch-chain restore plan.
8. Baseline gaps are added only for the snapshot-based whole-dimension fallback path.
9. Persisted patch, baseline, and snapshot payloads are decoded off-thread and converted by Minecraft-layer preparers before any tick-thread apply work starts.
10. Prepared placements are collapsed by final block position before tick-thread application; entity-only chunk batches are preserved.
11. `WorldOperationManager` converts prepared chunk payloads into `ChunkBatch` structures, drains completed local queues first, and only falls back to incomplete queues when the FAWE-style `64 chunks / 25 ms` thresholds are hit.
12. Chunk commit order is fixed to section blocks -> bounded block-entity slices -> bounded entity removals -> bounded entity updates -> bounded entity spawns.
13. Progress uses total work units: block placements, block-entity tail writes, entity removals, entity updates, and entity spawns. Entity-only operations do not complete early.
14. Completion resets the active variant head to the restored version, clears the pre-restore draft, writes a recovery journal entry, and leaves operation state available to the UI briefly.
15. Resetting the active variant head does not remove later version files. The UI keeps detached versions visible.

## Partial Restore Flow

Partial restore is a region-scoped restore workflow. The UI builds a `PartialRestoreRequest` with explicit bounds and a region source, then `RestoreService.partialRestore(...)` filters the same-lineage patch replay plan off the server tick. Same-lineage targets may be shared ancestors from another variant, such as the main save a branch was created from.

Key differences from full restore:

- partial restore does not move the active variant head to the old target version
- Lumi applies only changes inside the selected bounds; with patch payload v6 it reads only chunk frames intersecting those bounds
- after apply, Lumi writes a new `PARTIAL_RESTORE` version on the active variant
- pending draft changes inside the selected region are folded into that version; pending draft changes outside the region are preserved as the recovery draft
- entity changes are filtered by their old/new entity position and stored alongside block changes in the partial-restore version
- non-direct cross-lineage partial restore is rejected until a snapshot/baseline target-state planner is implemented

Hard rule: JSON parsing, LZ4 decompression, and block-state decoding must never happen on the tick-thread apply path.

## Recovery flow

Recovery is designed to survive crash-like exits without rewriting one large draft file on every change.

Current strategy:

- active sessions live in memory as `CaptureSessionState`, which owns the mutable `TrackedChangeBuffer`, chunk envelope, compact session-start chunk baselines, and pending stabilization state
- periodic flushes enqueue immutable `RecoveryDraft` snapshots to a dedicated capture-maintenance executor, skipping repeated stabilization cycles that leave the live buffer unchanged
- that executor appends `recovery/draft.wal.lz4` entries and performs WAL compaction into `recovery/draft.bin.lz4`
- first-touch whole-dimension baseline writes are queued through the same executor after the server thread copies a compact chunk snapshot payload
- in-progress save/amend drafts move to `recovery/operation-draft.bin.lz4` so new edits can start a separate live draft
- restore/save recovery actions reuse the same operation model as save and restore

## Threading model

Lumi uses a strict two-stage operation pattern:

- prepare stage: file I/O, compression, decompression, async recovery maintenance, snapshot persistence, and decode work off-thread
- apply stage: bounded world mutation batches on the server thread

For live capture, the server thread is limited to compact chunk-copy work. It no longer samples whole chunks block-by-block through `Level.getBlockState()` or compacts recovery WAL data inline.

Capture session entry points that snapshot, freeze, consume, discard, or adjust live draft state marshal onto the Minecraft server thread when a client UI or background completion callback invokes them. This keeps loaded-chunk stabilization and mutable capture maps on the same thread as normal world capture while leaving save payload writing and operation preparation off-thread.

Current guarantees:

- only one world operation runs per world at a time
- the world-operation executor is single-threaded and low priority
- restore/apply budgets adapt downward when a tick slice exceeds its budget and recover gradually when slices stay cheap
- block entities and entity diffs have explicit per-tick caps instead of running as unbounded chunk tail work
- entity-only restore, undo/redo, and recovery batches remain visible to the operation model because progress counts entity work as first-class work units
- preview generation no longer samples or rasterizes on the server; the server only queues request metadata and the client later performs the textured off-screen render with the built-in preview mesh path
- startup world-origin metadata bootstrap is low-priority background work and must not block initial server start or the first client render path
- malformed `world-origin.json` files are quarantined and regenerated from the current world so a damaged manifest cannot prevent the current workspace UI from opening
- operation progress is observable through `OperationSnapshot`
- client HUD state is polled separately from screen rendering so non-pausing menus, the top-right diff overlay, and the action-bar progress bar keep updating while screens are open

## Storage format summary

The current durable history format is project schema v4, patch payload schema v6, snapshot payload schema v5, and recovery draft schema v4.

Main files:

- `world-origin.json`: shared world seed/version/datapack/generator manifest for all dimension workspaces
- `exports/*.zip`: UI-driven project history archives and share packages
- `versions/*.json`: version manifests
- `patches/<patchId>.meta.json`: patch metadata and chunk index
- `patches/<patchId>.bin.lz4`: patch payload
- `snapshots/<snapshotId>.bin.lz4`: checkpoint snapshot payload
- `preview-requests/<versionId>.json`: queued client-side preview capture jobs
- `recovery/draft.bin.lz4`: compacted recovery base
- `recovery/draft.wal.lz4`: append-only recovery log
- `recovery/operation-draft.bin.lz4`: isolated save/amend draft fallback
- `recovery/journal.json`: user-facing workflow log
- `test-logs/singleplayer-<timestamp>.log`: local runtime test report for `/lumi testing singleplayer`

See [storage-format.md](storage-format.md) for the exact layout.

## Logging and observability

The mod is expected to log the following at minimum:

- operation start, rejection, progress, completion, and failure
- world-origin bootstrap and root-history initialization
- capture buffer lifecycle changes
- dirty-chunk stabilization summaries before draft persistence
- restore plan summaries
- recovery compaction and draft deletion
- UI-triggered service failures that map to generic status text

There is also a project-scoped debug layer:

- `ProjectSettings.debugLoggingEnabled` turns on verbose tracing for one workspace
- `-Dlumi.debug=true` turns it on globally
- debug logs cover capture, save, restore, recovery, compare, compare/recent overlay input and render diagnostics, compare overlay cache rebuilds, HUD refresh, and world-operation queue/application steps
- `-Dlumi.startupProfile=true` is a separate startup diagnostic flag for idle launch profiling. It logs bootstrap/client initializer timings and aggregate chunk-section ownership counters without turning on full capture debug logs.

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
- Fabric GameTest scaffolding for server smoke tests, a Lumi client GameTest that opens a consistent singleplayer world, runs the integrated Lumi runtime suite, and then captures a smoke screenshot, plus a no-Lumi baseline client GameTest that runs the same broad vanilla gameplay surface through `lumi-baseline-gametest`
- idle startup client GameTests that open a consistent singleplayer world with and without Lumi, wait for chunk rendering and a short idle window, and report a minimal result line for startup-only load comparisons
- `/lumi testing singleplayer` for a local integrated-world runtime suite that exercises the real project, version, recovery, undo/redo, diff, material, branch, archive/share export, partial restore, full restore, gameplay interaction, integrity, and cleanup services while reporting progress and logging pass/fail checks
- `scripts/compare-runtime-load.ps1` for repeated no-Lumi versus Lumi launch comparisons based on wall-clock time, server tick-delay warnings, long tick reports, WARN/ERROR counts, Lumi warnings, render pipeline failures, and required gameplay-suite result lines

When extending history or storage behavior, update both tests and documentation in the same change.

## Extension rules

- Keep domain services narrow and explicit. If a class starts owning more than one reason to change, split it.
- Add new repository types instead of growing one repository into a mixed metadata and payload god object.
- Prefer immutable records for persisted state and summaries.
- Restrict mutable state to clearly bounded runtime coordinators such as capture buffers or active operations.
- Preserve the menu-first product flow. Commands must stay read-only diagnostics/help and must not duplicate mutation workflows.
