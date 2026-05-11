# Architecture

## Purpose

Lumi is a singleplayer-first Fabric mod that gives Minecraft builders a project-oriented history workflow. The codebase is organized to keep user-facing domain rules separate from Minecraft engine integration, file persistence, and client UI.

The architecture is intentionally optimized around three requirements:

- history operations must remain reliable after crashes or interrupted sessions
- long save and restore work must avoid freezing the server tick
- the UI must expose progress as operations, not as fake instant actions

## Layered design

### Bootstrap layer

`io.github.luma.LumaMod` wires the mod into Fabric events. It registers diagnostic and local testing commands, schedules shared world-origin metadata bootstrap on a low-priority background thread after the first player has entered the world and a short idle delay has elapsed, advances world operations once per server tick, updates native bossbar progress for active operations, drains delayed entity spawn capture after Minecraft accepts spawned entities into a level, advances the singleplayer runtime test runner, flushes idle capture sessions, and persists active sessions on server shutdown. Shutdown persistence saves recovery drafts but does not wait for a backlog of low-priority baseline chunk writes, so leaving a world is not blocked by maintenance storage. Startup bootstrap also performs one-time storage migration and verifies the pre-mod checkpoint off the tick thread. On the client, world opening is gated by an alpha checkpoint screen for existing pre-Lumi worlds that do not yet have a completed Lumi checkpoint; accepting the screen writes a manifest-only checkpoint by default before entering the world. Full compressed chunk payload backup remains opt-in through `lumi.preModBackup.maxMiB`; when enabled, chunk payloads are written into staging storage with atomic file moves, then promoted before the completed manifest is published, so interrupted attempts retry instead of exposing a partial backup. Newly created worlds are marked before server bootstrap so they skip that gate, and the vanilla Edit World menu can restore completed pre-mod backup chunks while preserving Lumi project history.

### Domain model layer

`src/main/java/io/github/luma/domain/model` contains immutable records and focused mutable runtime structures used by the domain services.

Important model groups:

- project identity and settings: `BuildProject`, `ProjectVariant`, `ProjectVersion`, `ProjectSettings`
- world bootstrap metadata: `WorldOriginInfo`, `WorldInitialBackupManifest`
- history payloads: `StoredBlockChange`, `StoredEntityChange`, `StatePayload`, `EntityPayload`, `PatchMetadata`, `SnapshotData`, `RecoveryDraft`
- user-visible summaries: `ChangeStats`, `PendingChangeSummary`, `VersionDiff`, `MaterialDeltaEntry`
- operation state: `OperationHandle`, `OperationProgress`, `OperationSnapshot`, `OperationStage`, `WorkspaceHudSnapshot`
- mutable working-draft runtime: `TrackedChangeBuffer`, `CaptureSessionState`, `ChunkSectionPoint`
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
- `VersionService`: save tracked edits as versions, amend the active head, and enforce snapshot policy. Save and amend requests register a world operation before isolating the working draft, so large operation-draft writes and patch preparation are visible progress work instead of blocking the client action that started the save.
- `HistoryEditService`: rename saves, soft-delete saves, soft-delete branches, move safe branch heads back to parents, and keep tombstoned history hidden without deleting payload files
- `RestoreService`: build restore plans and orchestrate prepared chunk batches through Minecraft-layer preparers. Full and partial restore requests enter the operation model before lineage metadata loading, pending-draft freezing, restore journals, or batch decoding.
- `RecoveryService`: restore, persist, or discard interrupted tracked work
- `VariantService`: branch creation and branch switching. Branch creation is metadata-only and does not freeze active recovery drafts; branch switching freezes and validates pending edits before asking restore to apply the selected branch head.
- `VariantMergeService`: compare imported or local variant lineage against the active local target variant, group overlapping conflicts into chunk-connected zones, and write merged saves through the normal patch-first history path
- `DiffService`: reconstruct version or live-world block and entity differences using section fingerprint indexes to skip equal patch sections before falling back to structured state payload comparison
- `VersionLineageService`: centralizes reachable-version filtering, common ancestor lookup, ancestor checks, and ancestor-to-head path resolution used by restore, diff, and merge workflows
- `PreviewCaptureRequestService`: queue preview capture jobs without blocking save durability
- `PreviewCaptureRequestRepository`: persist preview capture requests so the server can queue work and the client can render later
- `ProjectIntegrityService`: validate storage consistency

These services should express product rules, not raw Minecraft side effects or raw file layouts.

### Minecraft adapter layer

`src/main/java/io/github/luma/minecraft` contains code that touches Minecraft engine APIs directly.

Important adapters:

- `HistoryCaptureManager`: facade for mixin capture entrypoints; it captures explicit tracked actions immediately, coordinates per-project causal envelopes, and fans accepted mutations out to the durable working draft and the volatile live action stack. It drains dirty-chunk stabilization before drafts are persisted, consumed, or selected for live undo/redo. Draft-flush throttling limits recovery-file writes only; it does not postpone dirty-chunk reconciliation.
- `WorkingDraftSessionManager`: owns active `TrackedChangeBuffer` sessions, recovery-draft persistence, freeze/consume/snapshot/discard operations, idle draft flushes, and rebasing a new draft from the old branch head to the version written by an async save.
- `LiveUndoRedoActionRecorder`: mirrors authorized root, causal, related, and reconciled stabilization deltas into `UndoRedoHistoryManager`; these actions are runtime-only and are not part of durable project history.
- `CaptureSessionRegistry`: owns active working-draft buffers, active session state, dirty-session flags, and live-draft flush fingerprints for `WorkingDraftSessionManager`
- `CaptureDiagnosticsRegistry`: owns capture diagnostics state used for accepted-mutation traces and progress summaries
- `TrackedProjectCatalog`: loads active project metadata, caches tracked-project membership, and exposes the dimension/chunk index used by capture matching
- `ProjectTrackingIndex`: caches dimension/chunk membership for active projects so block capture does not scan every project for every mutation
- `UndoRedoHistoryManager`: keeps the in-memory per-project undo and redo action stacks that power live undo/redo and the temporary recent-action overlay, and it can absorb nearby short-lived secondary fallout or reconciled stabilization deltas into the latest builder action. The stack is intentionally not restored after a restart and is not consumed by save/amend. Client undo routing sends WorldEdit/FAWE actors through the tools' native commands, while captured Axiom actors and `axiom-*` action ids replay through Lumi by default so the selected Lumi action remains the state transition that is applied. Axiom block-buffer captures keep the buffer operation identity, so large one-shot place/break edits undo as one Lumi action instead of being selected one block at a time.
- `CapturePersistenceCoordinator`: owns the low-priority maintenance executor for async baseline writes and coalesced recovery draft flushes
- `ChunkSnapshotCaptureService`: copies loaded chunk section palettes, real block-entity tags, and entity snapshots into immutable compact payloads on the server thread
- `SnapshotCaptureService`: marshals checkpoint snapshot capture onto the server thread and leaves serialization/persistence to storage writers
- `ChunkSectionOwnershipRegistry`: keeps a weak chunk-section owner index for direct section mutation fallback capture, with per-chunk section-array caching so repeated chunk reads during spawn generation do not re-register every section; direct section fallback resolves that server owner before either honoring an active vanilla mutation source such as piston replay or sampling external-tool stack frames, so client chunk loading and unowned generation sections do not pay fallback costs
- `WorldMutationCapturePolicy`, `EntityMutationCapturePolicy`, `EntityCausalContextRegistry`, and `PersistentBlockStatePolicy`: classify direct, deferred, rejected, and transient block/entity transitions before they become drafts, undo/redo actions, snapshots, or restore placements. Redstone and settled piston properties are persistent block state; only `moving_piston` animation state is normalized away. Explicit player and known-tool actions capture non-player entity spawn/remove/update as full persistent NBT payloads without stripping tick fields. Player damage stores a short pre-death entity context so delayed death, loot, and removal callbacks keep the original action id and entity payload. Spawn snapshots are queued until the entity is visible from its `ServerLevel`, so constructor-time or pre-registration NBT serialization cannot drop the entity history entry; delayed spawn records keep the original action time so they do not become the newest live undo action after later edits. Minecart/projectile mechanism fallout can join active `BLOCK_UPDATE` actions or the latest nearby live undo action, but secondary block stabilization without a causal action id is skipped. Unknown-stack entity fallback detection stays scoped to builder-facing persistent entity types so ordinary mob movement does not sample external-tool stacks
- `AutoCheckpointCommandClassifier` and `AutoCheckpointService`: identify large vanilla `/fill` and `/clone` commands plus external WorldEdit/Axiom action ids, then save an existing pending draft as an `AUTO_CHECKPOINT` before the external edit starts; dedicated servers require `LumaAccessControl` for player commands and an explicit access grant for external tool actors
- `ExplosiveEntityContextRegistry`: carries the originating builder action from a primed TNT spawn to its delayed `Level`/`ServerLevel` explosion so the block damage is captured with the same action context
- `DeferredWorldMutationContext` and `DeferredWorldMutationContexts`: copy the current source, actor, action id, access decision, and mechanism propagation depth onto delayed vanilla mutation carriers such as block events, scheduled ticks, and moving piston block entities; when those carriers run later, Lumi restores the same action context before normal block/entity capture hooks observe the resulting changes. Block events and scheduled ticks consume the bounded mechanism depth so self-sustaining redstone clocks cannot regenerate the same action id indefinitely, while moving piston block entities preserve the existing piston action id without increasing that depth so chained piston carriers in flush doors still reconcile their final moved blocks. `DeferredActionFalloutGuard` suppresses callbacks that still belong to an action after Lumi has started undoing or redoing that action, while replay callback suppression also protects the short-lived piston/observer envelope created by internal replay even when stale callbacks no longer carry an action id.
- `SessionStabilizationService`: compares session-start chunk baselines to the current world and composes a stabilized diff on top of the current pending chunk state for dirty envelope chunks after a short tick-settle window. Dirty section tracking narrows the cell walk when hooks know the changed section, while full dirty-chunk tracking remains the fallback. Related live undo/redo changes include both new settled deltas and tracked cells that a mechanism returned to the session baseline, such as a sticky piston pulling a block back from its extended position.
- `WorldMutationContext`: prevents internal restore, recovery, merge, and undo/redo application from being re-captured as tracked history, carries explicit source/action/access frames for player, external tool, and causal mechanism mutations, lets targeted physics sources inherit the originating action id, and can temporarily suppress capture while Lumi dispatches native external-tool undo/redo commands
- `LumaAccessControl`: centralizes the operator/cheats gate for diagnostic commands, UI entry points, and dedicated-server tracked world actions
- `WorldOperationManager`: runs async preparation, optional bounded chunk preload for fast apply profiles, completed-first chunk-queue dispatch on the server tick, and automatic `light-refresh` follow-up operations with explicit normal/history-fast/diagnostic apply profiles, adaptive block budgets, bounded section-rewrite and sparse direct-section passes, loaded-chunk no-op pruning, bulk skylight source/section-status refresh for low-level block apply paths, and bounded block-entity/entity tail work
- `WorldOperationBossBarManager`: adapts `OperationSnapshot` into Minecraft's native `ServerBossEvent`, so save/restore/undo/redo/merge/recovery progress is visible during prepare, preload, apply, and finalize without putting Minecraft APIs into domain services
- `WorldChangeBatchPreparer` and `SnapshotBatchPreparer`: convert persisted block/entity changes, large undo/redo actions, v7/v8 section frames, and snapshot payloads into tick-ready sparse or section-native prepared batches before apply begins. Exact root restores can read selected snapshot or baseline positions directly instead of expanding entire touched chunk sections. Entity preparation is explicit about delta replay versus authoritative placed-entity replacement. Change replay completes paired blocks and settled piston companions before chunk grouping; generated piston head-removal companions carry a final-replay hint so retracted states are reasserted after vanilla callbacks.
- `GlobalDispatcher`, `LocalQueue`, `ChunkBatch`, `SectionBatch`, and `EntityBatch`: chunk-oriented operation runtime, including entity spawn/remove/update batches
- `SectionContainerRewriteCommitStrategy`, `SectionRewriteApplyPlanner`, `PalettedContainerDataSwapper`, `SectionNativeBlockCommitStrategy`, `DirectChunkBlockCommitStrategy`, `SparseDeleteFastPathClassifier`, `WorldApplyChunkLoadContext`, `WorldApplyChunkResolver`, `ChunkHeightmapUpdatePlan`, `HeightmapColumnUpdater`, `DirectSectionBlockCommitStrategy`, and `VanillaBlockCommitStrategy`: choose the fastest safe loaded-section commit path for prepared batches, rewrite dense block-entity-free and POI-free sections by swapping `PalettedContainer` data when runtime access is available, apply sparse chunk sections through a chunk-level direct path when eligible, let fast profiled apply ticks synchronously reacquire a chunk after bounded preload when `getChunkNow` misses in direct, native, or rewrite paths, avoid unnecessary block-entity and POI work for proven-safe sparse deletes to air, coalesce sparse direct heightmap updates once per changed column, and fall back to normal `ServerLevel#setBlock` application when eligibility checks fail
- `ChunkSectionUpdateBroadcaster`, `WorldLightUpdateQueue`, `ChunkSkylightRefreshQueue`, `WorldApplyBlockUpdatePolicy`, `RedstoneReplayUpdatePlanner`, `RedstoneReplayUpdateQueue`, and `BlockChangeApplier`: commit section blocks, block entities, and entity batches in bounded steps with batched section packets, block-entity packets, side-effect-suppressed fallback flags, deferred light checks, and pre-barrier refresh of section emptiness plus `ChunkSkyLightSources` for low-level section rewrites. Redstone power/source transitions enqueue scoped vanilla neighbor updates and drain them after the operation has written its final stored block state, while ordinary replay still avoids placement physics and suppresses piston extension checks plus replay-created mechanism callbacks so captured moved-block deltas are not replayed twice.
- `ConnectedBlockPlacementExpander`: completes paired block placements for beds, doors, and tall plants before replay so apply batches do not leave one half clipped when only one persisted cell changed
- `LumaCommands`: diagnostic command interface plus smoke/full/structure singleplayer runtime test entry points
- `SingleplayerTestingService`: tick-driven integrated-world regression runner for real save, undo/redo, branch, export, gameplay capture, and initial restore workflows, with chat progress and durable pass/fail logs
- `WorldBootstrapService`: runs startup-only world-origin and root-version metadata checks off the server-start path so storage scans do not delay initial world entry

### Optional integration layer

`src/main/java/io/github/luma/integration` contains optional integration contracts for external builder tools. These adapters must not create hard runtime dependencies on WorldEdit, FAWE, Axiom, or their client APIs.

`ExternalToolIntegrationRegistry` reports typed capabilities for detected tools:

- WorldEdit capabilities are enabled only when the corresponding stable WorldEdit API classes are present, such as edit-session events, `LocalSession`, `ClipboardHolder`, and `ClipboardFormats`. `WorldEditSessionBridge` reads selection bounds, clipboard availability, and supported clipboard/schematic formats through those public session APIs.
- `OptionalIntegrationBootstrap` reflectively loads the WorldEdit edit-session tracker only when those capabilities are present. The tracker registers on WorldEdit's event bus and wraps `EditSession.Stage.BEFORE_CHANGE` extents. It records WorldEdit old/new block transitions directly under `WorldMutationSource.WORLDEDIT`, lazily serializing block-entity NBT only for block states that can own a block entity, while still keeping the mutation context active for Minecraft-level fallback capture. On dedicated servers, the tracker resolves player actors through the server player list and denies capture/checkpoint access for unknown actors.
- FAWE is reported as a detected fallback-capture tool when known FAWE classes or mod ids are present. It inherits selection, clipboard, and schematic capabilities only when the WorldEdit-compatible stable session APIs above are also present; otherwise block and entity history capture rely on the generic known-tool mutation fallbacks.
- `ExternalToolMutationOriginDetector` recognizes WorldEdit, FAWE, Axiom, Axion, AutoBuild, SimpleBuilding, Effortless Building, Litematica, and Tweakeroo stack frames at Minecraft mutation boundaries without linking those tools. `ExternalToolMutationSourceResolver` keeps this detection policy out of mixins and lets Axiom override an active player mutation source for Axiom-assisted break paths. It first checks cached integration availability, and the direct chunk-section fallback also requires a known server section owner before asking for stack inspection, so vanilla startup, client chunk loading, and unowned world generation sections do not sample per-mutation stack traces. Lower `LevelChunk#setBlockState` and `LevelChunkSection#setBlockState` fallbacks still record the current Lumi mutation source when one is already active, so vanilla piston and redstone mechanism writes that bypass `Level#setBlock` can dirty their stabilization chunk instead of leaving only the lever or power source in undo/redo history. Delayed block events, scheduled ticks, and moving piston block entities keep the action id that created them, so later mechanism writes can still reconcile into the same undo/redo action. Stack-detected external sources are untrusted on dedicated servers unless they inherit an authorized player context. `WorldMutationCaptureGuard` prevents duplicate block records so the highest available hook wins: WorldEdit API extent, `Level#setBlock`, `LevelChunk#setBlockState`, then direct `LevelChunkSection#setBlockState`; its capture boundaries are AutoCloseable for Java service code and mixin frame bookkeeping. Mixin hooks that hold source, suppression, capture-boundary, or pending mutation state wrap their target methods so cleanup runs from Java `finally` blocks or method-local state is discarded when the target throws.
- Axiom capabilities are intentionally conservative. Lumi may report detection or a custom region API, but it does not claim selection, clipboard, or schematic support unless a stable API is available. Because Axiom does not expose a stable operation API, Lumi uses guarded server-side fallbacks: Axiom block-buffer packet applies are captured before Axiom mutates chunk sections directly with the same lazy block-entity capture rule, entity lifecycle/update hooks capture Axiom entity edits that reach Minecraft entity APIs, and otherwise untracked Axiom mutations can still be recorded from known-tool stack frames. Captured Axiom actions, including capability-driven `axiom-*` action ids from tools such as bulldozer, fast place, replace, infinite range, tinker, and angel place, replay through Lumi by default. Place/break, replacement, and region-style block buffers keep their tool operation grouping for live undo/redo. The experimental `-Dlumi.experimentalAxiomNativeUndoRedo=true` route can delegate to Axiom's own history dispatcher, but it is not the default because Axiom's active selection/tool history can diverge from Lumi's selected action.
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
- `ProjectArchiveRepository` owns zip archive manifests and file-copy boundaries for history import/export, including manifest size limits, safe storage-id validation, stable source-file hashing, bounded entry copying, entry digest verification, and symlink rejection
- `ProjectCleanupRepository` owns file scanning and deletion for conservative storage cleanup, resolving deletion candidates through the project root and skipping symlink directories during empty-folder pruning
- `StorageIo` owns low-level atomic-write, durable append, and NBT binary helpers. Atomic writes go through a temp file, force the file contents before publish, move into place, and best-effort force the parent directory when the platform permits it.

### Client UI layer

`src/client/java/io/github/luma/ui` follows an `owo-ui Screen + Controller + ViewState` structure.

Responsibilities are split as follows:

- owo-ui screens keep transient UI state and rendering
- controllers invoke services and translate failures into status keys
- view-state records provide immutable inputs to the rendering layer
- lightweight summary controllers keep the project home, Save details, Branches, and Import / Export routes fast by avoiding diff, material, cleanup, diagnostics, archive scan, and merge-preview work on open; Save details renders manifest `ChangeStats` and leaves per-block/material reconstruction to See Changes
- `MergePreviewCache` runs Import / Export combine previews in the background and caches them by imported package and target branch while the screen is open
- `LumaScreen` extends owo-ui `BaseOwoScreen`, keeps Lumi menus non-pausing, closes the Lumi UI back to the game on Escape unless a route deliberately overrides that behavior, and gives each route a code-driven `OwoUIAdapter`
- `CompareScreenSections`, `ProjectScreenSections`, focused Save details section builders, and `ShareMergeReviewSection` own repeated route section composition, while their screens keep route lifecycle, transient selection state, and action callbacks
- `ClientWorkspaceOpenService` opens the current workspace through a lightweight loading screen and schedules project metadata preparation away from the client tick that handled the key press
- `ClientWorkspaceOpenService` and `ScreenRouter` route directly to `RecoveryScreen` when the opened project has a non-empty interrupted draft from a previous session; current-run pending work stays on the normal project screen
- Onboarding is a guided 9-step flow. `OnboardingScreen` owns modal cards, `OnboardingSpotlightOverlay` dims the workspace while leaving a cut-out around the taught `Save build` or `See changes` control, and `ClientOnboardingFlowCoordinator` owns the no-screen break-block step before reopening the tour for controlled Undo/Redo teaching. `OnboardingWorldPreviewShortcutController` turns those held Undo/Redo teaching cards into one synthetic request through the normal queued undo/redo controller and waits for the matching operation snapshot to become terminal; `OnboardingWorldPreviewDelay` then keeps the finished world state visible for one second before the next card opens. Onboarding cards expose an explicit close button that marks the tour complete, and the redo teaching step keeps the card visible briefly after executing so the restored state is readable. `OnboardingScreen` and any workspace route with an active onboarding overlay implement `LumiShortcutSuppressingScreen`, and `LumaClient` drains queued Lumi keybinding clicks without executing unrelated open workspace, quick save, undo/redo, quick rollback, compare toggle, hotkey info, or x-ray behavior while that suppression is active.
- `QuickSaveScreen` is a standalone shortcut route opened from the Lumi action button plus `Quick save key` chord; `QuickSaveScreenController` resolves the current dimension workspace and calls the same save service as the normal Save route. `HotkeyInfoScreen` is the standalone `Lumi action button` + `I` route and renders `LumiShortcutCatalog`, so the displayed combinations follow the current Minecraft Controls bindings.
- `LumaUi` centralizes compact `FlowLayout`, `ScrollContainer`, `Sizing`, `Insets`, and `Surface` rules so screens avoid absolute positioning and keep layout predictable
- `ClientContextualHelpService` owns client-side dismissal state for in-use hints, while `ContextualHelpPresenter` renders the shared dismissible hint panel for Build History, clean/dirty save states, quick rollback, shortcuts, Branches, Import / Export, Settings, More, Cleanup, Diagnostics, Save, Restore, See Changes, Partial restore, and Recovery workflows. `LumiRegionSelectionTeachingController` reuses the same dismissal state for the one-time wooden-sword actionbar hint.
- `ProjectWindowLayout` and `ProjectSidebarNavigation` keep the primary workspace tabs visible across Build History, Branches, Import / Export, Settings, and More. The sidebar highlights the active route and includes external support links.
- `PreviewCaptureCoordinator` watches pending preview requests for the current dimension, throttles empty scans so idle gameplay does not repeatedly walk preview request storage, runs the textured off-screen renderer on the client render thread through a local layered preview mesh builder, and trims empty transparent margins before storing the PNG. Save details polls for the fulfilled metadata and reloads cached textures when the PNG changes, so a finished preview appears without reopening the screen.
- obsolete tab-builder scaffolds have been removed; larger workflows now use dedicated screens and narrow view-state records instead of a shared project tab container
- the project home screen is now a Build History view with one primary action, `Save build`, plus one-click `See changes`, recent saves, `Branches`, and `More`
- `MoreScreen` exposes project maintenance, onboarding replay, contextual hint reset, manual compare, the history graph, and raw references under Project tools, plus a separate Deleted saves tab for soft-deleted save metadata; Import / Export and Settings stay in the persistent sidebar, and diagnostics remain out of the normal builder path.
- `CommitGraphLayout` computes deterministic branch lanes, skips shared-only empty lanes, and exposes parent-lane connectors, while `CommitGraphGeometry` owns graph hit-testing and routed connector geometry. `CommitGraphComponent` renders the More history graph as colored lanes, branch-head badges, commit metadata, and clickable rows instead of ASCII prefixes.
- dedicated screens isolate `Onboarding`, `Save`, `Save details`, `Branches`, `Import / Export`, `See Changes`, `Recovered work`, `Settings`, `Cleanup`, and `Diagnostics` so the main project screen no longer carries rare or technical workflows
- `WorkspaceHudCoordinator` owns the optional top-right HUD overlay and action-bar feedback surface. It refreshes workspace snapshots on a background worker so recovery-draft and operation polling never blocks the client tick, uses a slower idle refresh cadence, switches back to the short cadence while a world operation is active, and delegates concise colored operation text to `ActionBarMessagePresenter`. Numeric progress is left to the native bossbar so the action bar does not render a second text progress bar.
- project-facing screens poll lightweight operation snapshots every 10 client ticks so conflicting mutation buttons unlock as soon as the operation becomes terminal, while status text can stay visible briefly
- `CompareOverlayRenderer` renders a client-side compare overlay with a remappable hold-to-x-ray mode, keeps diff data separate from visibility, binds active overlay data to the resolved project/version pair, resolves exposed changed block faces once per overlay state, and draws cached section-scoped GPU meshes in the world render callback. Normal and x-ray meshes are uploaded lazily and reused while the overlay data is unchanged; render-distance culling skips off-camera sections, and only newly visible dirty sections are uploaded on a frame. Diffs above 50,000 changed blocks skip per-block surface resolving and collapse changed chunk sections into low-alpha volume blobs by change type; `OverlayVolumeMerger` tiles those blobs into a bounded set of section-aligned boxes so a very large diff never becomes one giant GPU primitive. Normal mode remains depth-tested so highlights do not show through blocks; x-ray mode deliberately disables depth testing.
- `CompareScreenController` resolves references on the client, then hands expensive diff and material aggregation to `AsyncCompareCache` so large saved comparisons open into a loading state instead of blocking the UI thread. Enabling a large world highlight also uses `CompareOverlayPreparationService` so coarse overlay geometry is prepared away from the client thread before activation. `CompareOverlayCoordinator` refreshes `current`-world compare overlays through the same async path. To protect the client, active overlays above the same 50,000-block detailed-render cap keep their initial snapshot instead of auto-refreshing every few ticks.
- `PendingChangesOverlayCoordinator` prepares the cumulative unsaved recovery draft overlay off the client tick while the remappable Lumi action button is held and no compare or recent-action overlay is visible. `PendingChangesOverlayRenderer` renders visible pending draft block changes since the active head, using cached exposed-surface meshes below the detailed cap and bounded tiled orange volume blobs above it. Hidden action-scoped growth changes remain in the draft but are filtered out before overlay geometry is built.
- `RecentChangesOverlayCoordinator` prepares recent-action overlay data off the client tick while the action button is held and pins the selected undo/redo stack revision for that hold so live edits do not flicker or replace an existing preview. `RecentChangesOverlayRenderer` renders undo and redo actions together when the compare overlay is not active. The first selected undo action renders red, the first selected redo action renders green, and older recent actions remain orange. Action previews resolve exposed changed block faces once, ignore hidden action-scoped growth changes, then render cached section-scoped GPU meshes with lazy uploads and render-distance culling at small and giant sizes instead of falling back to coarse merged volume chunks.
- `LumiShortcutInteractionGate` is the client input guard for recent-action chords. It latches the Lumi action button plus undo/redo state only while a world is open, and the client interaction mixins suppress vanilla use/attack mouse and key paths for that chord so undo/redo cannot also toggle the block the player is targeting.
- `LumiRegionSelectionController` keeps the runtime-only wooden-sword selection for the current project and dimension. `LumiRegionSelectionRenderer` caches the selected cuboid mesh until the bounds change, then draws translucent faces and an outline in the world render callback.
- the Import / Export route presents the normal flow: export history packages first, list importable zips from the game-root `lumi-projects` folder, import packages as review projects, optionally include preview PNGs in exports, delete imported review packages, resolve same-area zones, show zone overlays, review imported payload safety warnings, and apply a combined save without cluttering Build History or Branches

## Core runtime flows

## Capture flow

1. A Minecraft mixin intercepts a block mutation or entity lifecycle/update mutation. External builder edits that bypass the normal `Level#setBlock` context can still be captured by the guarded Axiom block-buffer fallback, the `LevelChunk#setBlockState` known-tool fallback, or the direct `LevelChunkSection#setBlockState` fallback used by FAWE-style chunk placement. Those lower block fallbacks also honor an already-active Lumi source frame, and delayed vanilla carriers restore their saved action context before executing, which keeps redstone and piston chunk/section writes attached to the same stabilization flow as their triggering action.
2. `WorldMutationContext` accepts explicit player/tool scopes and targeted secondary scopes. Ambient random ticks are marked as `GROWTH` and do not inherit player action IDs, so natural kelp, vine, grass, or amethyst growth cannot masquerade as a builder action. Bonemeal growth uses an explicit causal secondary frame, so player-caused growth can still join the originating action. Captured action-scoped `GROWTH` block deltas are stored with a hidden visibility bit: restore and recovery can replay them, but builder-facing stats, diffs, overlays, and preview bounds ignore them.
3. Player block and entity interaction packets enter the same explicit player mutation scope, so attacking or editing a non-player entity records its own undo/redo action with the full saved entity NBT payload. Entity spawns defer their NBT snapshot until the next server tick after world acceptance, but the queued record retains the original source frame and action ordering.
4. `HistoryCaptureManager` finds matching projects for the block position or entity position through a cached dimension/chunk tracking index.
5. `WorldMutationCapturePolicy` classifies block mutations as direct captures, deferred stabilization work, or rejected transient state. Direct player/tool state toggles are captured immediately. Redstone-driven block updates and piston fallout dirty the active session for final-state reconciliation instead of becoming tick-by-tick event history.
6. Explicit root mutations define a session-local causal envelope. Secondary mutations may also join from chunks currently loaded for a player in the same dimension, but they cannot bootstrap new sessions by themselves. Chunk baselines inside that active session region are captured lazily when those chunks first need stabilization or first-touch tracking, and deferred piston, redstone, fluid, and falling-block mutations can capture that session baseline before their first block write when an active session already owns the region and the mutation still has that causal action id.
7. `WorkingDraftSessionManager` keeps a per-project `TrackedChangeBuffer` that merges explicit and targeted realtime changes by packed block position and entity UUID. For entities, the first old full-NBT payload and latest new full-NBT payload win. `LiveUndoRedoActionRecorder` separately records eligible action-scoped deltas into the in-memory undo/redo stack, so durable version history and live action history do not share ownership.
8. Causal fallout such as player-caused fluid spread, falling blocks, redstone-driven block updates, and piston movement only marks dirty chunks inside that active session region for deferred stabilization. Ambient fluid and falling-block stabilization without an action id is skipped even when the chunk is player-loaded, and ambient random-tick growth without an action id cannot enter an existing draft. Causal growth remains hidden from builder-facing surfaces. Fluid-driven neighbor callbacks remain fluid fallout, so blocks broken by water are reconciled with the water action instead of being rejected as unactioned block updates.
9. `SessionStabilizationService` reconciles those dirty chunks against the current world before snapshotting, flushing, saving, freezing, consuming the draft, or choosing a live undo/redo action, but ordinary dirty-chunk drains wait a short tick-settle window after the last causal mutation. Live undo/redo force-loads pending stabilization chunks before that drain, so walking away from the edited area does not leave the project permanently blocked on unloaded fallout chunks. It still refuses to select an action while a project has pending dirty chunks after the drain, which avoids replaying a lever/button delta without its delayed piston or repeater fallout. During composition it stores settled redstone and piston properties such as `lit`, `powered`, `power`, `open`, `extended`, and `piston_head` as final block-state history while still excluding short-lived `moving_piston` animation state. Captured and deferred mutations contribute pre-change baseline corrections for their exact block positions, and deferred secondary sources can seed a clean session chunk baseline before they mutate the first block in an already-active region while carrying an action id. This prevents early neighbor fallout or transient piston writes from becoming the apparent original state when a chunk baseline is captured lazily. Replay applies derived redstone states exactly, keeps derived volatile replay positions under a short bounded exact-state guard while stale callbacks drain, excludes player input controls and active piston/observer mechanism participants from that guard, and releases the guard as soon as a new explicit builder mutation starts. Replay also propagates restored player input signal changes so undoing a lever/button/plate/tripwire toggle re-enters the vanilla signal chain instead of only flipping the control state. Undo/redo marks the replayed action id as stale for delayed vanilla callbacks, so scheduled redstone work, stale piston events, and moving-piston tickers queued before the replay cannot propagate beyond the restored state. Neighbor notifications stay reserved for signal-source block changes and player input signal changes. If the dirty chunk was caused by a delayed carrier with an action id, the reconciled delta is written into that exact live undo/redo action before the older nearby-fallout join policy is considered; when multiple causal mechanism actions dirty the same chunk before the settle window drains, the latest causal context owns that pending chunk so the selected undo action includes the current settled piston state instead of only the input block. Positions that already have explicit current builder edits are recorded as current-state-to-settled-state transitions so undoing a lever press does not delete the blocks that were placed before it.
10. Idle or dirty sessions are flushed into recovery storage only when the live buffer fingerprint changed since the last queued draft flush, but pending dirty chunks are still reconciled on every eligible server tick before the recovery flush throttle is considered.
11. Item drops created by explosions, fluid, falling blocks, and nearby block-update fallout are captured into the in-memory undo/redo action only. They are removed on undo and respawned on redo, but they do not enter recovery drafts or saved version payloads.
12. Authorized player-root actions append into the in-memory undo/redo stack, and nearby short-lived secondary fallout plus deferred fluid, falling-block, redstone, and piston deltas can join that same action without clearing an available redo. Delayed block events, scheduled ticks, and moving piston block entities use their copied action id instead of relying only on time/radius heuristics, and late redstone/piston callbacks in an already-pending dirty mechanism chunk can reuse that chunk's latest causal action context. Lumi records the reconciled action payload as the draft transition from the previous chunk state to the newly composed settled chunk state, so a mechanism close that returns blocks to baseline still contributes the moved-block undo/redo payload. Lumi can replay the practical builder step backward or forward without using version storage. Causal fallout is allowed to amend only the current top action with the same id; it cannot create or promote an older action after the builder has made a newer edit, which keeps oscillating redstone clocks from becoming the next undo target.
13. Explicit builder actions enter capture and undo/redo only when the current player has Lumi's required admin/operator permission. Integrated singleplayer worlds and dedicated servers use the same permission gate, so non-admin worlds do not activate Lumi mutation capture.

Important invariants:

- the first observed old state is preserved
- the latest new state wins
- no-op edits are removed from the buffer
- entity spawn/remove/update diffs use nullable old/new payloads, are collapsed by UUID to the final target operation, and are applied through `EntityBatch`
- restore-originated mutations never re-enter tracked history, and full restore completion clears stale live undo/redo stacks because their entries describe the pre-restore world state. Quick rollback is not a full restore: it applies only the inverse dirty draft through the history-fast prepared apply path and records its own live undo/redo action, so undo/redo can toggle that rollback without moving the saved branch head.
- save/amend consumes only the working draft and does not clear the live undo/redo stack; if a new draft appears while the async save writes, Lumi rebases that draft from the old branch head to the newly saved head
- undo/redo reuses high-throughput prepared world operations and then adjusts the working draft separately, so internal replay does not create duplicate capture events
- redstone restore is state-based, not pulse-based: prepared apply writes the selected persistent block states with side-effect-suppressed flags, then drains a deduplicated neighbor-update queue for redstone power/source transitions after the stored block state is complete. The short exact-state guard is scoped to derived redstone/mechanism states, excludes active piston/observer mechanism participants, and yields to the next explicit builder edit so normal interaction capture is not blocked. Settled piston replay is base-driven: an extended base can add its expected `piston_head`, a retracting base can clear the old head position, a transient moving-piston base target can be replayed as the stable retracted base, normalized transient air at the expected head can be replaced by the settled head, and a head-only placement is never used to create a new adjacent piston base
- native WorldEdit/FAWE undo/redo adjusts Lumi's pending draft after the tool replay runs and suppresses fallback capture during that replay, so the same changes are not recorded twice; Axiom actions use Lumi replay by default to keep the chosen Lumi history entry and the world mutation in lockstep
- repeated `Alt+Z` / `Alt+Y` shortcut presses enter a bounded client-side intent queue keyed by dimension and project. Each queued intent selects the next undo or redo action only after the active world operation finishes; save, restore, quick rollback, and return-before-restore actions do not use this queue.
- undo-only item drops are excluded from durable recovery and version payloads by entity capture policy

## Save flow

1. UI controllers call `VersionService.startSaveVersion(...)`; Quick save reaches the same path after resolving or creating the current dimension workspace.
2. `WorldOperationManager` registers a background operation immediately, so the screen and HUD can show progress before any large draft file is written.
3. The operation prepare stage consumes the live working draft buffer on the server thread first; persisted recovery storage is only a fallback. The in-memory undo/redo action stack is not consumed or cleared by save/amend.
4. The draft is moved into isolated operation-draft storage while async save work runs.
5. `WorldOperationManager` executes background preparation off the tick thread.
6. `PatchDataRepository` writes the binary patch payload.
7. `PatchMetaRepository` writes the lightweight patch index.
8. `VersionService` evaluates snapshot policy and optionally asks `SnapshotCaptureService` for a server-thread checkpoint capture, then persists the prepared payload through the snapshot writer. Whole-dimension projects use root/cadence checkpoints, not per-save volume snapshots.
9. `VersionRepository` writes the final version manifest only after payload files exist.
10. Amend-on-head merges both block and entity changes into the replacement draft before writing the amended version.
11. If new edits created another working draft while the async save was running, `WorkingDraftSessionManager` rebases that draft from the consumed head id to the newly saved version id without changing its block/entity delta.
12. Preview generation stores a lightweight request after save durability completes.
13. The client preview coordinator later builds a local layered preview mesh, renders a textured isometric frame into an off-screen target, and writes the PNG plus preview metadata. Open project and save-details screens poll this metadata and invalidate preview textures when the underlying file changes.

For automatic dimension workspaces, the history chain starts with a metadata-backed `WORLD_ROOT` version. It records the world origin context instead of a normal patch/snapshot payload.

## Restore flow

1. UI calls `RestoreService.restore(...)`.
2. The client requires explicit user confirmation before restoring an `INITIAL` or `WORLD_ROOT` version.
3. The confirmation UI shows a lightweight `RestorePlanSummary` with mode, branch, base version, target version, and affected chunk count before any world mutation starts. Pending recovery-draft chunks keep the summary actionable even when the selected target is already the active branch head.
4. `WorldOperationManager` registers the restore operation before heavy storage work starts. The prepare stage freezes active capture, loads lineage metadata, and writes the restore journal. If pending draft changes exist, a restore checkpoint is written first, and `recovery/last-restore-return.json` records the exact version/variant to return to before the restore.
5. When the target shares saved lineage with the current active variant head, `RestoreService` prefers a direct patch replay path, including shared branch-base ancestors, divergent branch heads, and restores to `WORLD_ROOT`: reverse patch application back to the common ancestor, forward patch application to the target, plus rollback of any pending draft. Pending entity rollback resolves target entity state from the target snapshot chain, or from the metadata-backed `WORLD_ROOT` plus tracked baseline chunks and patch replay for whole-dimension workspaces. Direct restores to `INITIAL` append saved initial snapshot chunks only for chunks touched by pending draft rollback or patch replay, and direct restores to `WORLD_ROOT` append only the touched chunks that have tracked root baselines.
6. If direct replay is not valid and the target is `WORLD_ROOT`, restore falls back to tracked baseline chunks for the current workspace. Generator regeneration remains blocked when the stored origin fingerprint does not match the current world.
7. If direct replay is not valid for a normal version, `RestoreService` falls back to the anchor snapshot plus patch-chain restore plan.
8. Baseline gaps are added only for the snapshot-based whole-dimension fallback path.
9. Persisted patch, baseline, and snapshot payloads are decoded off-thread and converted by Minecraft-layer preparers before any tick-thread apply work starts. New prepared snapshots and baseline chunks also write section payloads into the content-addressed cache and store section `ContentRef` metadata in the snapshot frame index for deduplication and future compaction.
10. Prepared chunk batches are collapsed by final `chunk + sectionY + localIndex` without expanding section-native/full-section buffers into sparse placements; paired block halves are completed for sparse changes, and entity operations collapse by UUID so a restore chain applies only the final spawn/remove/update target.
11. `WorldOperationManager` converts prepared chunk payloads into `ChunkBatch` structures, drains completed local queues first, and only falls back to incomplete queues when the FAWE-style `64 chunks / 25 ms` thresholds are hit.
12. `HISTORY_FAST`, `DIAGNOSTIC_TURBO`, and `MAXIMUM` prepared apply operations first run a bounded `PRELOADING` stage that acquires temporary chunk tickets and loads the operation's unique chunks before mutation starts; `NORMAL` stays conservative. After that preload completes, only the fast apply context may synchronously reacquire the current operation chunk when `getChunkNow` misses, so chunk-loading cost remains visible in apply timing and ordinary prepared work keeps the older unloaded-chunk fallback behavior. Loaded fast-profile chunks are pruned before mutation: already-matching cells are removed from tick apply, and no-op exact replay is kept only for forced mechanical companions, adjacent real updates, or chunk-boundary protection. Chunk commit order is then fixed to dense native sections -> sparse section blocks -> bounded block-entity slices -> bounded entity removals -> bounded entity updates -> bounded entity spawns. Dense `SECTION_NATIVE` sections use Lumi-owned `LumiSectionBuffer` data and a cursor that can resume inside the section across ticks, then send one section packet when the section completes. `SECTION_REWRITE` remains atomic per section; ordinary prepared work keeps the old phase separation, while fast restore, recovery, merge, and undo/redo operations may mix rewrite, native, and sparse steps in the same tick until the actual profile budgets or tick deadline are consumed. `MAXIMUM` is the foreground history profile for real restore/rollback work and uses larger apply, sync chunk-load, block-entity, entity, redstone, light, and preload caps than `DIAGNOSTIC_TURBO`. Full sections and dense 256-cell surface sections are rewrite candidates when preflight passes. Full-section rewrite builds a fresh replacement `PalettedContainer` from the target section cells instead of copying live container data; partial dense rewrite keeps the copy-and-patch path so untouched live cells are preserved. Sparse sections use a chunk-level direct path that reuses the loaded chunk, changed-cell masks, one heightmap plan, and one light batch across multiple sparse sections in the same tick step; safe sparse deletes to air skip block-entity removal and POI updates only when the current and target states are not block entities or POI states. Invalid, unsafe, or still-unloaded sections fall back to the older direct-section/vanilla path. Lumi-owned direct/native/rewrite paths collect lighting and redstone neighbor-update work while mutating blocks. During prepared world operations redstone updates and exact replay drain in the main action after block, block-entity, and entity writes. Queued light checks are transferred to a separate `light-refresh` follow-up action, which inherits the parent preload ticket lease, preloads its prepared dirty chunks before drain, calls `checkBlock`, marks only loaded touched chunks unsaved while logging missing chunks, waits on dirty-chunk `ThreadedLevelLightEngine` task barriers, marks the same loaded dirty chunks unsaved again after the barrier so final light data stays durable across rejoin, and leaves two server ticks for client light-update publication. Server shutdown gives an active `light-refresh` a bounded foreground drain window, then fails and clears any still-active operation while releasing inherited chunk tickets so a rejoin cannot keep ticking an old `ServerLevel`; outside the operation context the same planners apply immediately.
13. Progress uses total work units: block placements, block-entity tail writes, entity removals, entity updates, and entity spawns. Entity-only operations do not complete early.
14. Completion resets the target variant head to the restored version, clears the pre-restore draft, clears stale live undo/redo stacks, writes a recovery journal entry, and leaves operation state available to the UI briefly. Terminal snapshots remain addressable by operation id while an automatic `light-refresh` follow-up is active, so callers can still observe the completed block/entity action without treating visual light work as a failed parent operation. Quick rollback completion leaves the variant head unchanged, discards only the dirty draft it rolled back, and records a fresh live undo/redo action, so the next undo returns to the pre-rollback block/entity state without moving the saved branch head and redo reapplies the rollback. The explicit return-before-restore action reads the stored restore return point and runs the same hard restore pipeline back to that version from the UI. Branch switching and cross-branch save restore pass an explicit target variant or target save branch so active-branch metadata changes only after the world apply has finished.
15. Resetting the active variant head does not remove later version files. The UI keeps detached versions visible.

## Partial Restore Flow

Partial restore is a region-scoped restore workflow. The UI builds a `PartialRestoreRequest` with explicit bounds, a mode, and a region source, then `RestoreService.partialRestore(...)` plans the requested target state off the server tick. Direct targets may be same-lineage saves, shared ancestors from another variant, or divergent branch saves with a common saved ancestor. When direct patch replay is not available, `PartialRestoreTargetStatePlanner` reconstructs the current head and target save from checkpoint snapshots, whole-dimension baseline chunks, and patch chains, then emits a normal partial-restore draft from current state to target state.

Key differences from full restore:

- partial restore does not move the active variant head to the old target version
- `SELECTED_AREA` applies only changes inside the selected bounds; with chunk-addressable patch payloads it reads only chunk frames intersecting those bounds
- `OUTSIDE_SELECTED_AREA` applies the restore path outside the selected bounds, leaving selected blocks untouched
- after apply, Lumi writes a new `PARTIAL_RESTORE` version on the active variant
- after the version write succeeds, Lumi records the applied block/entity changes as one live undo/redo action so the player can undo or redo the partial restore without changing the saved branch head
- pending draft changes in the restored part are folded into that version; pending draft changes outside the restored part are preserved as the recovery draft
- entity changes are filtered by their old/new entity position and stored alongside block changes in the partial-restore version
- non-direct target-state planning is finite: selected-area restore uses selected chunks, while `OUTSIDE_SELECTED_AREA` uses project bounds for bounded projects or tracked whole-dimension chunks for world workspaces
- missing snapshot, patch, or baseline payloads reject the partial restore before tick-time apply starts

The client can fill the same request from a runtime Lumi region selection. With a `minecraft:wooden_sword`, Lumi uses a client-side raycast through already-loaded chunks so the selected block can be farther than vanilla interaction reach without loading new chunks. The first time the player holds the sword in an active workspace, a low-noise actionbar hint teaches the controls and is dismissed through the contextual hint store. Lumi action button + scroll toggles between `corners` and `extend`. In `corners` mode, left click sets corner A and right click sets corner B. In `extend` mode, left click expands the current bounds and right click resets the selection to the clicked block. Lumi action button + right click clears the selection. Selection state is scoped to project plus dimension in memory and is not persisted.

Hard rule: JSON parsing, LZ4 decompression, and block-state decoding must never happen on the tick-thread apply path.

## History editing and local merge flow

`HistoryEditService` owns editing rules for saved history metadata. Rename rewrites only the selected `ProjectVersion.message`. Save and branch deletion are soft deletes persisted through `HistoryTombstoneRepository`; payload files, previews, snapshots, and baseline chunks remain on disk.

Save deletion is intentionally narrow: root saves are blocked, non-leaf saves are blocked, and ambiguous multi-head deletes are blocked. If a deleted leaf is a branch head, that branch head is moved to the parent before the version id is tombstoned. Branch deletion is blocked for `main` and for the active branch.

Local branch merge reuses `VariantMergeService` conflict planning but targets only the current active branch for v1. The source branch is unchanged. Resolved changes are prepared and applied through `WorldOperationManager`, then `VersionService` writes a new `MERGE` version on the active branch.

Imported branch merge plans also carry a `HistoryPackageSafetyReport` from `HistoryPackageSafetyScanner`. The scanner inspects imported block-entity and entity payloads for command-capable blocks, structure/jigsaw/spawner data, command block minecarts, and unknown ids. Unsafe imported payloads require an explicit trusted-package confirmation before `VariantMergeService.startMerge(...)` will apply them; local branch merges remain trusted local history.

## Auto checkpoint flow

`AutoCheckpointService` protects pending work before large external edits when `ProjectSettings.autoCheckpointEnabled` is enabled. The setting is off by default. When enabled, it runs before vanilla `/fill` and `/clone` commands when `AutoCheckpointCommandClassifier` estimates at least 512 affected blocks, and before WorldEdit/Axiom actions when those integrations surface an external action id.

The service saves an existing pending draft as `VersionKind.AUTO_CHECKPOINT`. If no draft exists, the current branch head already represents the checkpoint and no version is written. Checkpoints are deduplicated per external action id, and skipped attempts are logged when another Lumi world operation is already active.

## Recovery flow

Recovery is designed to survive crash-like exits without rewriting one large draft file on every change.

Current strategy:

- active working-draft sessions live in memory as `CaptureSessionState` plus `TrackedChangeBuffer` under `WorkingDraftSessionManager`; the session owns the chunk envelope, compact session-start chunk baselines, and pending stabilization state, while Minecraft-side capture expands active membership with currently player-loaded chunks through `ActiveSessionRegionPolicy` only for secondary mutations that still carry a causal action id
- periodic flushes enqueue immutable `RecoveryDraft` snapshots to a dedicated capture-maintenance executor, skipping repeated stabilization cycles that leave the live buffer unchanged
- that executor appends `recovery/draft.wal.lz4` entries and performs WAL compaction into `recovery/draft.bin.lz4`
- draft flushes and first-touch whole-dimension baseline writes use separate low-priority capture executors, so a baseline backlog cannot delay recovery WAL durability
- in-progress save/amend drafts move to `recovery/operation-draft.bin.lz4` inside the operation prepare stage so new edits can start a separate working draft without making the initiating UI call wait for a large LZ4/NBT write; when the save commits, that new draft is rebased onto the newly saved head
- restore/save recovery actions reuse the same operation model as save and restore
- project open routes directly to the recovery screen only when a non-empty interrupted draft is persisted from a previous run; same-run unsaved drafts remain visible as pending changes instead of repeatedly forcing recovery

## Threading model

Lumi uses a strict two-stage operation pattern:

- prepare stage: file I/O, compression, decompression, async recovery maintenance, snapshot persistence, and decode work off-thread
- apply stage: bounded world mutation batches on the server thread

For live capture, the server thread is limited to compact chunk-copy work. It no longer samples whole chunks block-by-block through `Level.getBlockState()` or compacts recovery WAL data inline.

Capture session entry points that snapshot, freeze, consume, discard, or adjust working draft state marshal onto the Minecraft server thread when a client UI, background operation prepare step, or background completion callback invokes them. This keeps loaded-chunk stabilization and mutable capture maps on the same thread as normal world capture while leaving save payload writing and operation preparation off-thread. Save/amend and restore entrypoints enqueue their world operation before heavy draft isolation or restore planning, so large recovery files, patch metadata reads, and decode work report operation progress instead of freezing the screen that launched them.

Current guarantees:

- only one world operation runs per world at a time
- the world-operation executor is single-threaded and low priority
- restore, recovery, merge, undo/redo, and `light-refresh` apply operations use the foreground `MAXIMUM` profile; ordinary prepared work keeps the `NORMAL` profile, and bulk diagnostics use `DIAGNOSTIC_TURBO`
- restore/apply budgets adapt downward when a tick slice exceeds its budget and recover gradually when slices stay cheap; native cells, rewrite-section bursts, direct sparse sections, sparse step size, sync chunk loads, block-entity/entity tail work, and deferred light checks have explicit caps in addition to block count and time budgets. `HISTORY_FAST`, `DIAGNOSTIC_TURBO`, and `MAXIMUM` keep profile-specific minimum direct-section and tick-time floors after adaptive downscale so one expensive chunk-load tick does not collapse sparse throughput for the rest of the operation. `MAXIMUM` keeps the adaptive scale at or above the base foreground budget for real history operations.
- prepared apply records debug-only fast-apply metrics for native sections/cells, direct sections, fallback sections, changed/skipped blocks, section packets, block-entity packets, deferred redstone updates, deferred light checks, apply/work ticks, redstone/light-drain ticks/duration, and fallback reasons
- block entities and entity diffs have explicit per-tick caps instead of running as unbounded chunk tail work
- entity-only restore, undo/redo, and recovery batches remain visible to the operation model because progress counts entity work as first-class work units
- preview generation no longer samples or rasterizes on the server; the server only queues request metadata and the client later performs the textured off-screen render with the built-in preview mesh path
- startup world-origin metadata bootstrap is low-priority background work and must not block initial server start or the first client render path
- malformed `world-origin.json` files are quarantined and regenerated from the current world so a damaged manifest cannot prevent the current workspace UI from opening
- operation progress is observable through `OperationSnapshot`
- client HUD state is polled separately from screen rendering so non-pausing menus, the top-right diff overlay, action-bar status text, and native operation bossbar keep updating while screens are open

## Storage format summary

The current durable history format is project schema v4, patch payload schema v9, snapshot payload schema v6, and recovery draft schema v5.

Main files:

- `world-origin.json`: shared world seed/version/datapack/generator manifest for all dimension workspaces
- `exports/*.zip`: UI-driven project history archives and share packages
- `versions/*.json`: version manifests
- `versions/index.json`: optional disposable version-list cache
- `pre-mod-backup/manifest.json` and `pre-mod-backup/chunks/*`: one-time pre-Lumi raw chunk backup used by the world-entry gate and the vanilla Edit World restore action; `pre-mod-backup/staging/*` is incomplete attempt state and is not restore-visible
- `patches/<patchId>.meta.json`: patch metadata, chunk index, visible section index, and entity old/new chunk index
- `patches/<patchId>.bin.lz4`: patch payload
- `snapshots/<snapshotId>.bin.lz4`: checkpoint snapshot payload
- `preview-requests/<versionId>.json`: queued client-side preview capture jobs
- `recovery/draft.bin.lz4`: compacted recovery base
- `recovery/draft.wal.lz4`: append-only recovery log
- `recovery/operation-draft.bin.lz4`: isolated save/amend draft fallback
- `recovery/journal.json`: user-facing workflow log
- `recovery/last-restore-return.json`: local return-before-restore pointer
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
- recovery WAL salvage and corruption quarantine
- UI-triggered service failures that map to generic status text

There is also a project-scoped debug layer:

- `ProjectSettings.debugLoggingEnabled` turns on verbose tracing for one workspace
- `-Dlumi.debug=true` turns it on globally
- debug logs cover capture, save, restore, recovery, compare, compare/recent overlay input and render diagnostics, overlay section mesh uploads, HUD refresh, world-operation queue/application steps, and fast world-apply metrics
- `-Dlumi.startupProfile=true` is a separate startup diagnostic flag for idle launch profiling. It logs bootstrap/client initializer timings and aggregate chunk-section ownership counters without turning on full capture debug logs.
- `-Dlumi.loadLog=true` is a separate runtime load diagnostic flag. It writes `logs/lumi-load.log` with slow spans, cumulative top-cost summaries, server-tick substep timings, save payload/snapshot phases, restore decode phases, prepared-apply tick/preload/finalize slices, and completed world-apply metrics without enabling verbose debug tracing.
- `-Dlumi.lightLog=true` and `-Dlumi.blockApplyLog=true` write focused operational logs for the automatic `light-refresh` follow-up and high-throughput restore/rollback block apply path. The load log flag enables both focused logs so the test-client profile captures shadow and bottleneck evidence by default without per-block log volume.

Logs are part of the support surface. New background or storage work should not be introduced without meaningful logs.

## Testing strategy

The current test suite is organized around:

- model behavior such as `TrackedChangeBuffer`
- repository round-trips for patch, snapshot, and recovery storage
- repository round-trips for archive export/import boundaries, including malformed archive rejection and storage path containment
- service-level diff and history policy behavior
- project layout and storage path invariants
- recovery draft isolation between live capture and save/amend operations
- client-side performance regression tests for compare overlay selection, commit graph layout, and material delta summarization
- Fabric GameTest scaffolding for server smoke tests, a dedicated Lumi overlay client GameTest for small live-render and large cached-mesh overlay smoke paths, a Lumi runtime client GameTest that opens a consistent singleplayer world, runs the integrated Lumi runtime suite, and then captures a smoke screenshot, plus a no-Lumi baseline client GameTest that runs the same broad vanilla gameplay surface through `lumi-baseline-gametest`
- idle startup client GameTests that open a consistent singleplayer world with and without Lumi, wait for chunk rendering and a short idle window, and report a minimal result line for startup-only load comparisons
- `/lumi testing smoke` for a shorter local integrated-world project smoke suite that exercises bootstrap storage, the pre-open checkpoint manifest and opt-in backup budget, snapshot content refs, section-indexed patch reads, capture, save/amend, branch/export, partial restore, full restore, integrity, and cleanup through real services
- `/lumi testing singleplayer` for a local integrated-world runtime suite that exercises the real project, version, recovery, undo/redo, diff, material, branch, archive/share export, partial restore, full restore, entity quick rollback, saved entity update restore, player `gameMode` water-bridge placement, gameplay save preview fulfillment, controlled TNT undo/redo, pending-draft initial restore, generated observer/sticky-piston rollback fixture checks, saved-structure fixtures, integrity, cleanup, and bulk prepared-apply diagnostics while reporting progress and logging pass/fail checks plus final apply metrics
- `scripts/compare-runtime-load.ps1` for repeated no-Lumi versus Lumi launch comparisons based on wall-clock time, server tick-delay warnings, long tick reports, WARN/ERROR counts, Lumi warnings, render pipeline failures, and required gameplay-suite result lines

When extending history or storage behavior, update both tests and documentation in the same change.

## Extension rules

- Keep domain services narrow and explicit. If a class starts owning more than one reason to change, split it.
- Add new repository types instead of growing one repository into a mixed metadata and payload god object.
- Prefer immutable records for persisted state and summaries.
- Restrict mutable state to clearly bounded runtime coordinators such as capture buffers or active operations.
- Preserve the menu-first product flow. Commands must stay read-only diagnostics/help and must not duplicate mutation workflows.
