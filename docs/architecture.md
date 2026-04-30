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
- history visibility: `HistoryTombstones`

### Domain service layer

`src/main/java/io/github/luma/domain/service` owns business workflows and orchestration.

Key services:

- `ProjectService`: create, load, and update projects
- `ProjectService`: also owns world-origin bootstrap and automatic `WORLD_ROOT` creation for dimension workspaces
- `ProjectArchiveService`: export stable project history to zip archives and import it back into project storage
- `HistoryShareService`: export variant-scoped history packages, import them back as review projects for the same project lineage, and delete imported review packages after lineage validation
- `ProjectCleanupService`: compute safe cleanup candidates from reachable history metadata and active operation state
- `VersionService`: save tracked edits as versions, amend the active head, and enforce snapshot policy
- `HistoryEditService`: rename saves, soft-delete saves, soft-delete branches, move safe branch heads back to parents, and keep tombstoned history hidden without deleting payload files
- `RestoreService`: build restore plans and orchestrate prepared chunk batches through Minecraft-layer preparers
- `RecoveryService`: restore, persist, or discard interrupted tracked work
- `VariantService`: branch creation and branch switching. Branch creation is metadata-only and does not freeze active recovery drafts; branch switching freezes and validates pending edits before asking restore to apply the selected branch head.
- `VariantMergeService`: compare imported or local variant lineage against the active local target variant, group overlapping conflicts into chunk-connected zones, and write merged saves through the normal patch-first history path
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
- `UndoRedoHistoryManager`: keeps the in-memory per-project undo and redo action stacks that power live undo/redo and the temporary recent-action overlay, and it can absorb nearby short-lived secondary fallout or reconciled stabilization deltas into the latest builder action. Client undo routing sends WorldEdit/FAWE actors through the tools' native commands, while captured Axiom actors and `axiom-*` action ids replay through Lumi so Axiom capability edits do not depend on Axiom's own history stack.
- `CapturePersistenceCoordinator`: owns the low-priority maintenance executor for async baseline writes and coalesced recovery draft flushes
- `ChunkSnapshotCaptureService`: copies loaded chunk section palettes, real block-entity tags, and entity snapshots into immutable compact payloads on the server thread
- `SnapshotCaptureService`: marshals checkpoint snapshot capture onto the server thread and leaves serialization/persistence to storage writers
- `ChunkSectionOwnershipRegistry`: keeps a weak chunk-section owner index for direct section mutation fallback capture, with per-chunk section-array caching so repeated chunk reads during spawn generation do not re-register every section; direct section fallback resolves that server owner before stack inspection, so client chunk loading and unowned generation sections do not pay external-tool stack sampling costs
- `WorldMutationCapturePolicy`, `EntityMutationCapturePolicy`, and `PersistentBlockStatePolicy`: filter runtime-only block/entity transitions and normalize piston animation states before they become drafts, undo/redo actions, snapshots, or restore placements; unknown-stack entity fallback detection is scoped to builder-relevant persistent entity types so ordinary mob movement does not sample external-tool stacks
- `AutoCheckpointCommandClassifier` and `AutoCheckpointService`: identify large vanilla `/fill` and `/clone` commands plus external WorldEdit/Axiom action ids, then save an existing pending draft as an `AUTO_CHECKPOINT` before the external edit starts
- `ExplosiveEntityContextRegistry`: carries the originating builder action from a primed TNT spawn to its delayed explosion so the block damage is captured with the same action context
- `SessionStabilizationService`: compares session-start chunk baselines to the current world and composes a stabilized diff on top of the current pending chunk state for dirty envelope chunks
- `WorldMutationContext`: prevents internal restore, recovery, merge, and undo/redo application from being re-captured as tracked history and can temporarily suppress capture while Lumi dispatches native external-tool undo/redo commands
- `LumaAccessControl`: centralizes the operator/cheats gate for diagnostic commands, UI entry points, and dedicated-server tracked world actions
- `WorldOperationManager`: runs async preparation plus completed-first chunk-queue dispatch on the server tick with adaptive block budgets and bounded section-rewrite, block-entity, and entity passes
- `WorldChangeBatchPreparer` and `SnapshotBatchPreparer`: convert persisted block/entity changes, large undo/redo actions, v7 section frames, and snapshot payloads into tick-ready sparse or section-native prepared batches before apply begins
- `GlobalDispatcher`, `LocalQueue`, `ChunkBatch`, `SectionBatch`, and `EntityBatch`: chunk-oriented operation runtime, including entity spawn/remove/update batches
- `SectionContainerRewriteCommitStrategy`, `PalettedContainerDataSwapper`, `SectionNativeBlockCommitStrategy`, `DirectSectionBlockCommitStrategy`, and `VanillaBlockCommitStrategy`: choose the fastest safe loaded-section commit path for prepared section batches, rewrite dense block-entity-free and POI-free sections by swapping `PalettedContainer` data when runtime access is available, and fall back to normal `ServerLevel#setBlock` application when eligibility checks fail
- `ChunkSectionUpdateBroadcaster`, `WorldApplyBlockUpdatePolicy`, and `BlockChangeApplier`: commit section blocks, block entities, and entity batches in bounded steps with batched section packets, block-entity packets, and side-effect-suppressed fallback flags so replayed restore/undo/redo states do not emit neighbor updates or placement physics
- `ConnectedBlockPlacementExpander`: completes paired block placements for beds, doors, and tall plants before replay so apply batches do not leave one half clipped when only one persisted cell changed
- `LumaCommands`: diagnostic command interface plus the singleplayer runtime test entry point
- `SingleplayerTestingService`: tick-driven integrated-world regression runner for real save, undo/redo, branch, export, gameplay capture, and initial restore workflows, with chat progress and durable pass/fail logs
- `WorldBootstrapService`: runs startup-only world-origin and root-version metadata checks off the server-start path so storage scans do not delay initial world entry

### Optional integration layer

`src/main/java/io/github/luma/integration` contains optional integration contracts for external builder tools. These adapters must not create hard runtime dependencies on WorldEdit, FAWE, Axiom, or their client APIs.

`ExternalToolIntegrationRegistry` reports typed capabilities for detected tools:

- WorldEdit capabilities are enabled only when the corresponding WorldEdit API classes are present, such as edit-session events, local sessions, clipboard, and schematic formats.
- `OptionalIntegrationBootstrap` reflectively loads the WorldEdit edit-session tracker only when those capabilities are present. The tracker registers on WorldEdit's event bus and wraps `EditSession.Stage.BEFORE_CHANGE` extents. It records WorldEdit old/new block transitions directly under `WorldMutationSource.WORLDEDIT`, lazily serializing block-entity NBT only for block states that can own a block entity, while still keeping the mutation context active for Minecraft-level fallback capture.
- FAWE is reported as a detected fallback-capture tool when known FAWE classes or mod ids are present. Lumi does not claim FAWE selection, clipboard, or schematic APIs; block and entity history capture rely on the generic known-tool mutation fallbacks.
- `ExternalToolMutationOriginDetector` recognizes WorldEdit, FAWE, Axiom, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica, and Tweakeroo stack frames at Minecraft mutation boundaries without linking those tools. `ExternalToolMutationSourceResolver` keeps this detection policy out of mixins and lets Axiom override an active player mutation source for Axiom-assisted break paths. It first checks cached integration availability, and the direct chunk-section fallback also requires a known server section owner before asking for stack inspection, so vanilla startup, client chunk loading, and unowned world generation sections do not sample per-mutation stack traces. `WorldMutationCaptureGuard` prevents duplicate block records so the highest available hook wins: WorldEdit API extent, `Level#setBlock`, `LevelChunk#setBlockState`, then direct `LevelChunkSection#setBlockState`.
- Axiom capabilities are intentionally conservative. Lumi may report detection or a custom region API, but it does not claim selection, clipboard, or schematic support unless a stable API is available. Because Axiom does not expose a stable operation API, Lumi uses guarded server-side fallbacks: Axiom block-buffer packet applies are captured before Axiom mutates chunk sections directly with the same lazy block-entity capture rule, entity lifecycle/update hooks capture Axiom entity edits that reach Minecraft entity APIs, and otherwise untracked Axiom mutations can still be recorded from known-tool stack frames. Captured Axiom actions, including capability-driven `axiom-*` action ids from tools such as bulldozer, fast place, replace, infinite range, tinker, and angel place, replay through Lumi undo/redo rather than Axiom's own undo stack.
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
- history tombstone repositories persist soft-delete visibility metadata without touching history payloads
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
- `LumaScreen` extends owo-ui `BaseOwoScreen`, keeps Lumi menus non-pausing, closes the Lumi UI back to the game on Escape unless a route deliberately overrides that behavior, and gives each route a code-driven `OwoUIAdapter`
- `CompareScreenSections`, `ProjectScreenSections`, focused Save details section builders, and `ShareMergeReviewSection` own repeated route section composition, while their screens keep route lifecycle, transient selection state, and action callbacks
- `ClientWorkspaceOpenService` opens the current workspace through a lightweight loading screen and schedules project metadata preparation away from the client tick that handled the key press
- `ClientWorkspaceOpenService` and `ScreenRouter` route directly to `RecoveryScreen` when the opened project has a non-empty interrupted draft from a previous session; current-run pending work stays on the normal project screen
- `QuickSaveScreen` is a standalone shortcut route opened from the Lumi action button plus `Quick save key` chord; `QuickSaveScreenController` resolves the current dimension workspace and calls the same save service as the normal Save route
- `LumaUi` centralizes compact `FlowLayout`, `ScrollContainer`, `Sizing`, `Insets`, and `Surface` rules so screens avoid absolute positioning and keep layout predictable
- `ProjectWindowLayout` and `ProjectSidebarNavigation` keep the primary workspace tabs visible across Build History, Branches, Import / Export, Settings, and More. The sidebar highlights the active route and includes external support links.
- `PreviewCaptureCoordinator` watches pending preview requests for the current dimension, throttles empty scans so idle gameplay does not repeatedly walk preview request storage, runs the textured off-screen renderer on the client render thread through a local layered preview mesh builder, and trims empty transparent margins before storing the PNG. Save details polls for the fulfilled metadata and reloads cached textures when the PNG changes, so a finished preview appears without reopening the screen.
- obsolete tab-builder scaffolds have been removed; larger workflows now use dedicated screens and narrow view-state records instead of a shared project tab container
- the project home screen is now a Build History view with one primary action, `Save build`, plus one-click `See changes`, recent saves, `Branches`, and `More`
- `MoreScreen` exposes project maintenance, manual compare, the history graph, and raw references under Project tools, plus a separate Deleted saves tab for soft-deleted save metadata; Import / Export and Settings stay in the persistent sidebar, and diagnostics remain out of the normal builder path.
- `CommitGraphLayout` computes deterministic branch lanes, skips shared-only empty lanes, and exposes parent-lane connectors, while `CommitGraphGeometry` owns graph hit-testing and routed connector geometry. `CommitGraphComponent` renders the More history graph as colored lanes, branch-head badges, commit metadata, and clickable rows instead of ASCII prefixes.
- dedicated screens isolate `Onboarding`, `Save`, `Save details`, `Branches`, `Import / Export`, `See Changes`, `Recovered work`, `Settings`, `Cleanup`, and `Diagnostics` so the main project screen no longer carries rare or technical workflows
- `WorkspaceHudCoordinator` owns the optional top-right HUD overlay and action-bar feedback surface. It uses a slower idle refresh cadence, switches back to the short cadence while a world operation is active, and delegates concise colored operation text to `ActionBarMessagePresenter`. The action-bar progress bar is shown only for larger active operations so quick actions do not create noisy fake progress.
- project-facing screens poll lightweight operation snapshots every 10 client ticks so conflicting mutation buttons unlock as soon as the operation becomes terminal, while status text can stay visible briefly
- `CompareOverlayRenderer` renders a client-side compare overlay with a remappable hold-to-x-ray mode, keeps diff data separate from visibility, binds active overlay data to the resolved project/version pair, prioritizes the nearest exposed changed blocks to the current camera position, and draws exposed overlay faces through immediate end-main quads so translucent fill is flushed independently of the shared world buffer. Normal mode remains depth-tested so highlights do not show through blocks; x-ray mode deliberately disables depth testing.
- `CompareOverlayCoordinator` refreshes `current`-world compare overlays on the client tick so live edits appear in the active highlight without rebuilding the screen manually
- `RecentChangesOverlayCoordinator` prepares recent-action overlay data off the client tick and reuses it while the undo/redo stack revision is unchanged. `RecentChangesOverlayRenderer` renders latest undo actions when the remappable Lumi action button is held, or redo actions while the action button plus redo is held, when the compare overlay is not active. Small action previews are selected from exposed changed blocks first and capped to the nearest 512 per-block entries. Dense action previews skip per-block surface resolving and collapse changed chunk sections into low-alpha merged volume blobs so large edits remain readable without drawing thousands of overlapping overlays.
- `LumiRegionSelectionController` keeps the runtime-only wooden-sword selection for the current project and dimension. `LumiRegionSelectionRenderer` draws the selected cuboid with translucent faces and an outline in the world render callback.
- the Import / Export route presents the normal flow: export history packages first, list importable zips from the game-root `lumi-projects` folder, import packages as review projects, optionally include preview PNGs in exports, delete imported review packages, resolve same-area zones, show zone overlays, and apply a combined save without cluttering Build History or Branches

## Core runtime flows

## Capture flow

1. A Minecraft mixin intercepts a block mutation or entity lifecycle/update mutation. External builder edits that bypass the normal `Level#setBlock` context can still be captured by the guarded Axiom block-buffer fallback, the `LevelChunk#setBlockState` known-tool fallback, or the direct `LevelChunkSection#setBlockState` fallback used by FAWE-style chunk placement.
2. `WorldMutationContext` accepts explicit player/tool scopes and targeted secondary scopes. Ambient random ticks are marked as `GROWTH` and do not inherit player action IDs, so natural kelp, vine, grass, or amethyst growth cannot masquerade as a builder action.
3. `HistoryCaptureManager` finds matching projects for the block position or entity position through a cached dimension/chunk tracking index.
4. `WorldMutationCapturePolicy` drops piston animation sources, transient piston blocks, and runtime-only redstone state flips before they can enter drafts or live undo/redo.
5. Explicit root mutations define a session-local causal envelope. Chunk baselines inside that envelope are captured lazily when those chunks first need stabilization.
6. A per-project `TrackedChangeBuffer` merges explicit and targeted realtime changes by packed block position and entity UUID. For entities, the first old full-NBT payload and latest new full-NBT payload win.
7. Ambient fallout such as fluid spread and falling blocks only mark dirty chunks inside that causal envelope for deferred stabilization. Natural growth cannot expand tracked chunks.
8. `SessionStabilizationService` reconciles those dirty chunks against the current world before snapshotting, flushing, saving, freezing, consuming the draft, or choosing a live undo/redo action, and exposes the reconciled delta so undo/redo can attach it to the latest nearby action.
9. Idle or dirty sessions are flushed into recovery storage only when the live buffer fingerprint changed since the last queued draft flush.
10. Item drops created by explosions, fluid, falling blocks, and nearby block-update fallout are captured into the in-memory undo/redo action only. They are removed on undo and respawned on redo, but they do not enter recovery drafts or saved version payloads.
11. Authorized player-root actions append into the in-memory undo/redo stack, and nearby short-lived secondary fallout plus deferred fluid/falling-block deltas can join that same action without clearing an available redo, so Lumi can replay the practical builder step backward or forward without using the tick-thread decode path.
12. In integrated singleplayer worlds, explicit builder actions are allowed into capture and undo/redo immediately even if the permission frame is not operator-shaped yet; dedicated servers keep the operator gate.

Important invariants:

- the first observed old state is preserved
- the latest new state wins
- no-op edits are removed from the buffer
- entity spawn/remove/update diffs use nullable old/new payloads and are applied through `EntityBatch`
- restore-originated mutations never re-enter tracked history
- undo/redo reuses high-throughput prepared world operations and then adjusts the pending draft separately, so internal replay does not create duplicate capture events
- native WorldEdit/FAWE undo/redo adjusts Lumi's pending draft after the tool command runs and suppresses fallback capture during the command, so the same changes are not recorded twice
- undo-only item drops are excluded from durable recovery and version payloads by entity capture policy

## Save flow

1. UI controllers call `VersionService.startSaveVersion(...)`; Quick save reaches the same path after resolving or creating the current dimension workspace.
2. The live in-memory buffer is consumed on the server thread first; persisted recovery storage is only a fallback.
3. The draft is moved into isolated operation-draft storage while async save work runs.
4. `WorldOperationManager` executes background preparation off the tick thread.
5. `PatchDataRepository` writes the binary patch payload.
6. `PatchMetaRepository` writes the lightweight patch index.
7. `VersionService` evaluates snapshot policy and optionally asks `SnapshotCaptureService` for a server-thread checkpoint capture, then persists the prepared payload through the snapshot writer. Whole-dimension projects use root/cadence checkpoints, not per-save volume snapshots.
8. `VersionRepository` writes the final version manifest only after payload files exist.
9. Amend-on-head merges both block and entity changes into the replacement draft before writing the amended version.
10. Preview generation stores a lightweight request after save durability completes.
11. The client preview coordinator later builds a local layered preview mesh, renders a textured isometric frame into an off-screen target, and writes the PNG plus preview metadata. Open project and save-details screens poll this metadata and invalidate preview textures when the underlying file changes.

For automatic dimension workspaces, the history chain starts with a metadata-backed `WORLD_ROOT` version. It records the world origin context instead of a normal patch/snapshot payload.

## Restore flow

1. UI calls `RestoreService.restore(...)`.
2. The client requires explicit user confirmation before restoring an `INITIAL` or `WORLD_ROOT` version.
3. The confirmation UI shows a lightweight `RestorePlanSummary` with mode, branch, base version, target version, and affected chunk count before any world mutation starts. Pending recovery-draft chunks keep the summary actionable even when the selected target is already the active branch head.
4. Active capture is frozen and an optional safety checkpoint is written first.
5. When the target shares saved lineage with the current active variant head, `RestoreService` prefers a direct patch replay path, including shared branch-base ancestors, divergent branch heads, and restores to `WORLD_ROOT`: reverse patch application back to the common ancestor, forward patch application to the target, plus rollback of any pending draft. Pending restores to an `INITIAL` snapshot append that snapshot after the direct rollback so the result matches the saved initial state, not just the draft's old values.
6. If direct replay is not valid and the target is `WORLD_ROOT`, restore falls back to tracked baseline chunks for the current workspace. Generator regeneration remains blocked when the stored origin fingerprint does not match the current world.
7. If direct replay is not valid for a normal version, `RestoreService` falls back to the anchor snapshot plus patch-chain restore plan.
8. Baseline gaps are added only for the snapshot-based whole-dimension fallback path.
9. Persisted patch, baseline, and snapshot payloads are decoded off-thread and converted by Minecraft-layer preparers before any tick-thread apply work starts.
10. Prepared placements are collapsed by final block position and paired block halves are completed before tick-thread application; entity-only chunk batches are preserved.
11. `WorldOperationManager` converts prepared chunk payloads into `ChunkBatch` structures, drains completed local queues first, and only falls back to incomplete queues when the FAWE-style `64 chunks / 25 ms` thresholds are hit.
12. Chunk commit order is fixed to dense native sections -> sparse section blocks -> bounded block-entity slices -> bounded entity removals -> bounded entity updates -> bounded entity spawns. Dense sections use Lumi-owned `LumiSectionBuffer` data and write loaded `LevelChunkSection` cells directly, then update heightmaps, POI state, light checks, block entities, chunk dirty state, and one section packet. Sparse sections keep the existing direct section path, and invalid or unloaded sections fall back to the vanilla apply path.
13. Progress uses total work units: block placements, block-entity tail writes, entity removals, entity updates, and entity spawns. Entity-only operations do not complete early.
14. Completion resets the target variant head to the restored version, clears the pre-restore draft, writes a recovery journal entry, and leaves operation state available to the UI briefly. Branch switching and cross-branch save restore pass an explicit target variant or target save branch so active-branch metadata changes only after the world apply has finished.
15. Resetting the active variant head does not remove later version files. The UI keeps detached versions visible.

## Partial Restore Flow

Partial restore is a region-scoped restore workflow. The UI builds a `PartialRestoreRequest` with explicit bounds, a mode, and a region source, then `RestoreService.partialRestore(...)` filters the direct patch replay plan off the server tick. Direct targets may be same-lineage saves, shared ancestors from another variant, or divergent branch saves with a common saved ancestor.

Key differences from full restore:

- partial restore does not move the active variant head to the old target version
- `SELECTED_AREA` applies only changes inside the selected bounds; with chunk-addressable patch payloads it reads only chunk frames intersecting those bounds
- `OUTSIDE_SELECTED_AREA` applies the restore path outside the selected bounds, leaving selected blocks untouched
- after apply, Lumi writes a new `PARTIAL_RESTORE` version on the active variant
- after the version write succeeds, Lumi records the applied block/entity changes as one live undo/redo action so the player can undo or redo the partial restore without changing the saved branch head
- pending draft changes in the restored part are folded into that version; pending draft changes outside the restored part are preserved as the recovery draft
- entity changes are filtered by their old/new entity position and stored alongside block changes in the partial-restore version
- non-direct cross-lineage partial restore is rejected until a snapshot/baseline target-state planner is implemented

The client can fill the same request from a runtime Lumi region selection. With a `minecraft:wooden_sword`, Lumi uses a client-side raycast through already-loaded chunks so the selected block can be farther than vanilla interaction reach without loading new chunks. Lumi action button + scroll toggles between `corners` and `extend`. In `corners` mode, left click sets corner A and right click sets corner B. In `extend` mode, left click expands the current bounds and right click resets the selection to the clicked block. Lumi action button + right click clears the selection. Selection state is scoped to project plus dimension in memory and is not persisted.

Hard rule: JSON parsing, LZ4 decompression, and block-state decoding must never happen on the tick-thread apply path.

## History editing and local merge flow

`HistoryEditService` owns editing rules for saved history metadata. Rename rewrites only the selected `ProjectVersion.message`. Save and branch deletion are soft deletes persisted through `HistoryTombstoneRepository`; payload files, previews, snapshots, and baseline chunks remain on disk.

Save deletion is intentionally narrow: root saves are blocked, non-leaf saves are blocked, and ambiguous multi-head deletes are blocked. If a deleted leaf is a branch head, that branch head is moved to the parent before the version id is tombstoned. Branch deletion is blocked for `main` and for the active branch.

Local branch merge reuses `VariantMergeService` conflict planning but targets only the current active branch for v1. The source branch is unchanged. Resolved changes are prepared and applied through `WorldOperationManager`, then `VersionService` writes a new `MERGE` version on the active branch.

## Auto checkpoint flow

`AutoCheckpointService` protects pending work before large external edits when `ProjectSettings.autoCheckpointEnabled` is enabled. The setting is off by default. When enabled, it runs before vanilla `/fill` and `/clone` commands when `AutoCheckpointCommandClassifier` estimates at least 512 affected blocks, and before WorldEdit/Axiom actions when those integrations surface an external action id.

The service saves an existing pending draft as `VersionKind.AUTO_CHECKPOINT`. If no draft exists, the current branch head already represents the checkpoint and no version is written. Checkpoints are deduplicated per external action id, and skipped attempts are logged when another Lumi world operation is already active.

## Recovery flow

Recovery is designed to survive crash-like exits without rewriting one large draft file on every change.

Current strategy:

- active sessions live in memory as `CaptureSessionState`, which owns the mutable `TrackedChangeBuffer`, chunk envelope, compact session-start chunk baselines, and pending stabilization state
- periodic flushes enqueue immutable `RecoveryDraft` snapshots to a dedicated capture-maintenance executor, skipping repeated stabilization cycles that leave the live buffer unchanged
- that executor appends `recovery/draft.wal.lz4` entries and performs WAL compaction into `recovery/draft.bin.lz4`
- first-touch whole-dimension baseline writes are queued through the same executor after the server thread copies a compact chunk snapshot payload
- in-progress save/amend drafts move to `recovery/operation-draft.bin.lz4` so new edits can start a separate live draft
- restore/save recovery actions reuse the same operation model as save and restore
- project open routes directly to the recovery screen only when a non-empty interrupted draft is persisted from a previous run; same-run unsaved drafts remain visible as pending changes instead of repeatedly forcing recovery

## Threading model

Lumi uses a strict two-stage operation pattern:

- prepare stage: file I/O, compression, decompression, async recovery maintenance, snapshot persistence, and decode work off-thread
- apply stage: bounded world mutation batches on the server thread

For live capture, the server thread is limited to compact chunk-copy work. It no longer samples whole chunks block-by-block through `Level.getBlockState()` or compacts recovery WAL data inline.

Capture session entry points that snapshot, freeze, consume, discard, or adjust live draft state marshal onto the Minecraft server thread when a client UI or background completion callback invokes them. This keeps loaded-chunk stabilization and mutable capture maps on the same thread as normal world capture while leaving save payload writing and operation preparation off-thread.

Current guarantees:

- only one world operation runs per world at a time
- the world-operation executor is single-threaded and low priority
- restore, recovery, merge, and undo/redo apply operations use the high-throughput section-native budget; ordinary prepared work keeps the conservative tick budget
- restore/apply budgets adapt downward when a tick slice exceeds its budget and recover gradually when slices stay cheap
- prepared apply records debug-only fast-apply metrics for native sections/cells, direct sections, fallback sections, changed/skipped blocks, section packets, block-entity packets, light checks, and fallback reasons
- block entities and entity diffs have explicit per-tick caps instead of running as unbounded chunk tail work
- entity-only restore, undo/redo, and recovery batches remain visible to the operation model because progress counts entity work as first-class work units
- preview generation no longer samples or rasterizes on the server; the server only queues request metadata and the client later performs the textured off-screen render with the built-in preview mesh path
- startup world-origin metadata bootstrap is low-priority background work and must not block initial server start or the first client render path
- malformed `world-origin.json` files are quarantined and regenerated from the current world so a damaged manifest cannot prevent the current workspace UI from opening
- operation progress is observable through `OperationSnapshot`
- client HUD state is polled separately from screen rendering so non-pausing menus, the top-right diff overlay, and the action-bar progress bar keep updating while screens are open

## Storage format summary

The current durable history format is project schema v4, patch payload schema v7, snapshot payload schema v5, and recovery draft schema v4.

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
- debug logs cover capture, save, restore, recovery, compare, compare/recent overlay input and render diagnostics, compare overlay cache rebuilds, HUD refresh, world-operation queue/application steps, and fast world-apply metrics
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
- `/lumi testing singleplayer` for a local integrated-world runtime suite that exercises the real project, version, recovery, undo/redo, diff, material, branch, archive/share export, partial restore, full restore, player `gameMode` water-bridge placement, gameplay save preview fulfillment, controlled TNT undo/redo, pending-draft initial restore, integrity, and cleanup services while reporting progress and logging pass/fail checks
- `scripts/compare-runtime-load.ps1` for repeated no-Lumi versus Lumi launch comparisons based on wall-clock time, server tick-delay warnings, long tick reports, WARN/ERROR counts, Lumi warnings, render pipeline failures, and required gameplay-suite result lines

When extending history or storage behavior, update both tests and documentation in the same change.

## Extension rules

- Keep domain services narrow and explicit. If a class starts owning more than one reason to change, split it.
- Add new repository types instead of growing one repository into a mixed metadata and payload god object.
- Prefer immutable records for persisted state and summaries.
- Restrict mutable state to clearly bounded runtime coordinators such as capture buffers or active operations.
- Preserve the menu-first product flow. Commands must stay read-only diagnostics/help and must not duplicate mutation workflows.
