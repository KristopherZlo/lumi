# MODULES

## Purpose

This file is the first navigation map for agents and contributors who need to change Lumi without rereading the whole codebase. Use it with `AGENTS.md`: `AGENTS.md` defines mandatory rules, and this file says where to look for specific logic.

Lumi is organized around project history for builders: project, version, branch, compare, restore, recovery, share, and cleanup. Most tasks should start from one workflow row below, then follow only the named classes and their tests.

## Context Budget Rules

- Do not begin a task by reading every source file or every class in a layer.
- Start with this file, `AGENTS.md`, and the smallest relevant workflow section below.
- Use targeted search such as `rg "VersionService"` or `rg --files src/main/java/io/github/luma/domain/service` before opening files.
- Read `package-info.java`, the named service/controller/repository, and the matching test first.
- Broaden only when a collaborator, test failure, compile error, or documented flow points to another module.
- For cross-layer work, follow the normal path: UI screen -> UI controller -> domain service -> repository or Minecraft adapter -> model/test.
- Do not put product rules in UI, Minecraft adapters, repositories, mixins, or static helper sprawl.
- Update this file when adding a new module, workflow owner, or cross-layer responsibility.

## Documentation Routing

- `AGENTS.md`: non-negotiable architecture, OOP/SOLID, testing, documentation, and commit rules.
- `modules.md`: file-level routing and context-saving rules.
- `README.md`: product overview, user-facing capability summary, quick build/test commands.
- `docs/architecture.md`: runtime architecture, invariants, threading, capture/save/restore/recovery flows.
- `docs/development.md`: local setup, test commands, UI/history development notes.
- `docs/storage-format.md`: on-disk layout, schema versions, payload files, archive format, cleanup policy.
- `docs/user-guide.md`: user-facing behavior and wording.
- `docs/commands.md`: command surface.
- `docs/test-client.md`: test client profile and runtime validation stack.
- `docs/maintenance-guide.md`: maintenance and operational checks.
- `docs/commit-policy.md`: checkpoint commit policy.

## Workflow Routing

| Work area | Start here | Then inspect | Tests/docs |
| --- | --- | --- | --- |
| Project creation, settings, workspace open, `WORLD_ROOT` | `ProjectService`, `ClientWorkspaceOpenService`, `WorldBootstrapService` | `ProjectRepository`, `VariantRepository`, `WorldOriginRepository`, `RecoveryRepository`, `ProjectLayout` | `ProjectServiceTest`, `WorldOriginRepositoryTest`, `docs/storage-format.md` |
| Save, amend, quick save | `VersionService`, `QuickSaveScreenController` | `CaptureSessionRegistry`, `CapturePersistenceCoordinator`, `PatchDataRepository`, `PatchMetaRepository`, `SnapshotCaptureService`, `PreviewCaptureRequestService` | `VersionServiceTest`, `PatchDataRepositoryTest`, `SnapshotStorageTest` |
| Restore, full rollback, operation progress | `RestoreService` | `VersionLineageService`, `WorldOperationManager`, `WorldChangeBatchPreparer`, `SnapshotBatchPreparer`, `BlockChangeApplier` | `RestoreServiceTest`, `WorldChangeBatchPreparerTest`, `docs/architecture.md` |
| Partial restore | `RestoreService`, `PartialRestorePlanner` | `SaveDetailsScreen`, `SaveDetailsScreenController`, `SaveDetailsPartialRestoreSection`, `LumiRegionSelectionController`, `LumiRegionSelectionRenderer`, `PatchDataRepository` | `PartialRestorePlannerTest`, `PartialRestoreFormStateTest`, `LumiRegionSelectionStateTest`, `docs/storage-format.md` |
| Live capture and recovery draft creation | `HistoryCaptureManager` | `CaptureSessionRegistry`, `TrackedProjectCatalog`, `ProjectTrackingIndex`, `WorldMutationCapturePolicy`, `EntityMutationCapturePolicy`, `SessionStabilizationService`, relevant mixin | capture tests under `src/test/java/io/github/luma/minecraft/capture` |
| Undo/redo and recent actions | `UndoRedoService`, `UndoRedoHistoryManager`, `UndoRedoKeyController` | `ExternalUndoRedoPolicy`, `WorldOperationManager`, `RecentChangesOverlayCoordinator`, `RecentChangesOverlayRenderer`, `EntityMutationCapturePolicy` | `UndoRedoActionStackTest`, `ExternalUndoRedoPolicyTest`, `RecentChangesOverlayRendererStateTest` |
| Branches, branch switching, and history editing | `VariantService`, `HistoryEditService`, `VersionLineageService` | `VariantRepository`, `HistoryTombstoneRepository`, `VariantsScreenController`, `VariantsScreen`, `SaveDetailsScreen` | `VariantServiceTest`, `HistoryEditServiceTest`, `VersionLineageServiceTest`, `VariantsScreenControllerTest` |
| Import/export/share/merge | `HistoryShareService`, `ProjectArchiveService`, `VariantMergeService` | `ProjectArchiveRepository`, `ShareScreenController`, `VariantsScreenController`, `MergePreviewCache`, `ShareMergeReviewSection` | `HistoryShareServiceTest`, `ProjectArchiveServiceTest`, `VariantMergeServiceTest` |
| Compare and material summaries | `DiffService`, `MaterialDeltaService` | `CompareScreenController`, `CompareOverlayCoordinator`, `CompareOverlayRenderer`, `CompareOverlaySurfaceResolver` | `DiffServiceTest`, compare overlay tests |
| Preview generation | `PreviewCaptureRequestService`, `PreviewService`, `PreviewCaptureCoordinator` | `PreviewBoundsResolver`, `TexturedPreviewCaptureService`, `PreviewRenderMeshBuilder`, `PreviewImageCropper` | `PreviewServiceTest`, `PreviewCaptureRequestRepositoryTest`, preview tests |
| Recovery UI and recovery actions | `RecoveryService`, `RecoveryScreenController` | `RecoveryRepository`, `CapturePersistenceCoordinator`, `ScreenOperationStateSupport` | `RecoveryRepositoryTest`, recovery model tests |
| Cleanup and integrity | `ProjectCleanupService`, `ProjectIntegrityService` | `ProjectCleanupRepository`, `CleanupScreenController`, `ProjectRepository` | `ProjectCleanupRepositoryTest`, `ProjectArchiveRepositoryTest` |
| Storage format or path changes | `ProjectLayout`, exact repository class | `StorageIo`, `GsonProvider`, matching domain model record | `ProjectLayoutTest`, repository tests, `docs/storage-format.md` |
| Optional builder tool integration and auto checkpoints | `ExternalToolIntegrationRegistry`, `OptionalIntegrationBootstrap`, `AutoCheckpointService`, `AutoCheckpointCommandClassifier` | `WorldEditEditSessionTracker`, Axiom classes, integration mixins, `ServerGamePacketListenerMixin` | integration tests, `AutoCheckpointCommandClassifierTest`, `docs/architecture.md` |
| Commands and runtime tests | `LumaCommands`, `SingleplayerTestingService` | `SingleplayerGameplayRegressionSuite`, scripts under `scripts/` | `docs/commands.md`, `docs/test-client.md` |
| Client navigation and screen behavior | `ScreenRouter`, the route screen, route controller, route view state | `LumaScreen`, `ProjectWindowLayout`, `ProjectSidebarNavigation`, section builders | UI controller tests, `docs/development.md` |

## Bootstrap And Global Entry Points

- `src/main/java/io/github/luma/LumaMod.java`: Fabric server entry point, event wiring, server tick operation pump, shutdown capture flush.
- `src/client/java/io/github/luma/LumaClient.java`: Fabric client entry point, keybindings, overlays, preview coordinator, client UI registration.
- `src/main/resources/fabric.mod.json`: mod metadata, entrypoints, dependencies.
- `src/main/resources/lumi.mixins.json`: mixin registration; inspect only when capture hooks or Minecraft mutation entrypoints change.
- `src/main/java/io/github/luma/debug/LumaDebugLog.java`: debug logging categories and global/workspace debug checks.
- `src/main/java/io/github/luma/debug/StartupProfiler.java`: startup-only diagnostics behind `-Dlumi.startupProfile=true`.

## Domain Model

Use `src/main/java/io/github/luma/domain/model` for value objects, persisted records, summaries, and focused mutable runtime state. Do not add Minecraft APIs, file I/O, UI state, or broad orchestration here.

- Project identity/settings: `BuildProject`, `ProjectSettings`, `ProjectVariant`, `ProjectVersion`, `VersionKind`, `WorldOriginInfo`.
- Coordinates/bounds/chunks: `BlockPoint`, `Bounds3i`, `ChunkPoint`, `ChunkDelta`.
- Stored changes and payloads: `StoredBlockChange`, `StoredEntityChange`, `StoredChangeAccumulator`, `StatePayload`, `EntityPayload`, `BlockPatch`, `PatchWorldChanges`, `PatchMetadata`, `PatchStats`, `PatchChunkSlice`.
- Snapshots: `SnapshotRef`, `SnapshotData`, `SnapshotChunkData`, `SnapshotSectionData`, `ChunkSnapshotPayload`, `ChunkSectionSnapshotPayload`.
- Recovery: `RecoveryDraft`, `RecoveryDraftSummary`, `RecoveryJournalEntry`.
- Operations/progress/HUD: `OperationHandle`, `OperationProgress`, `OperationSnapshot`, `OperationStage`, `WorkspaceHudSnapshot`.
- Diff/compare/material summaries: `VersionDiff`, `DiffBlockEntry`, `ChangeStats`, `PendingChangeSummary`, `MaterialDeltaEntry`.
- Restore and partial restore: `RestorePlanSummary`, `RestorePlanMode`, `PartialRestoreRequest`, `PartialRestorePlanSummary`, `PartialRestoreRegionSource`.
- Branch merge/share/archive: `VariantMergePlan`, `VariantMergeApplyRequest`, `MergeConflictZone`, `MergeConflictResolution`, `MergeConflictZoneResolution`, `ProjectArchiveManifest`, `ProjectArchiveEntry`, `ProjectArchiveScope`, `ProjectArchiveScopeType`, `ProjectArchiveExportResult`, `ProjectArchiveImportResult`, `HistoryPackageFileSummary`, `HistoryPackageImportResult`, `ImportedHistoryProjectSummary`, `ExternalSourceInfo`.
- Live capture and undo/redo runtime: `TrackedChangeBuffer`, `CaptureSessionState`, `UndoRedoAction`, `UndoRedoActionStack`, `WorldMutationSource`.
- Preview: `PreviewInfo`, `PreviewCaptureRequest`.
- History visibility: `HistoryTombstones`.
- Cleanup/integrity: `ProjectCleanupPolicy`, `ProjectCleanupCandidate`, `ProjectCleanupReport`, `ProjectIntegrityReport`.

## Domain Services

Use `src/main/java/io/github/luma/domain/service` for business workflows and product rules. Services may coordinate repositories and Minecraft adapters, but they should not render UI, parse raw paths ad hoc, or mutate Minecraft blocks directly.

- `ProjectService`: create/load/update projects, bootstrap dimension workspaces, world-origin and `WORLD_ROOT` project rules.
- `VersionService`: save/amend versions, isolate operation drafts, write patch-first history, request snapshots/previews.
- `RestoreService`: full restore, partial restore, pre-restore safety checkpoints, operation orchestration.
- `RecoveryService`: recover, discard, persist, and expose interrupted work.
- `HistoryEditService`: rename saves, soft-delete safe saves, soft-delete inactive branches, and persist history tombstones.
- `VariantService`: branch creation, branch switching, active head movement.
- `VariantMergeService`: imported and local branch merge planning, conflict zones, merge apply through normal version persistence.
- `VersionLineageService`: reachable version filtering, ancestor/common-ancestor/path lookup shared by restore, diff, merge.
- `DiffService`: version-to-version and live-world diff reconstruction.
- `MaterialDeltaService`: material summary aggregation for UI.
- `HistoryShareService`: variant package export/import/delete/list flow for `lumi-projects`.
- `ProjectArchiveService`: full project archive import/export.
- `ProjectCleanupService`: conservative cleanup candidate calculation.
- `ProjectIntegrityService`: storage consistency validation.
- `PreviewCaptureRequestService`: durable request queue for client-side preview capture.
- `PreviewService`: legacy/simple preview sampling and PNG writing.
- `PreviewBoundsResolver`: changed-region bounds for previews.
- `PreviewColumnSampler`, `IsometricPreviewRenderer`: preview scene sampling/render helpers.
- `PartialRestorePlanner`: selected-region restore planning and filtering.
- `ChangeStatsFactory`: change stats, patch metadata summaries, pending summaries.
- `ChunkSelectionFactory`: chunk list derivation from bounds or changes.

## Storage

Use `src/main/java/io/github/luma/storage` and `src/main/java/io/github/luma/storage/repository` for file paths, JSON manifests, binary payloads, archives, and atomic writes. Repositories should not depend on UI, screen state, or tick-time apply batches.

- `ProjectLayout`: single source of truth for world/project-relative paths.
- `GsonProvider`: shared JSON configuration.
- `StorageIo`: atomic writes, NBT helpers, low-level storage utilities.
- `ProjectRepository`: `project.json` metadata.
- `VariantRepository`: `variants.json` branch heads and branch list.
- `HistoryTombstoneRepository`: `history-tombstones.json` soft-delete visibility metadata.
- `VersionRepository`: `versions/*.json` manifests.
- `WorldOriginRepository`: shared `world-origin.json` manifest and corruption quarantine behavior.
- `PatchRepository`: patch metadata/data facade.
- `PatchMetaRepository`: `patches/*.meta.json` chunk index and lightweight patch metadata.
- `PatchDataRepository`: `patches/*.bin.lz4` schema reads/writes and selective chunk-frame reads.
- `SnapshotRepository`, `SnapshotReader`, `SnapshotWriter`: checkpoint snapshot payload boundary.
- `RecoveryRepository`: recovery draft, WAL, operation draft, journal persistence.
- `BaselineChunkRepository`: whole-dimension baseline chunks under `cache/baseline-chunks`.
- `PreviewCaptureRequestRepository`: `preview-requests/*.json` queue.
- `ProjectArchiveRepository`: zip archive import/export file copying and archive manifest boundary.
- `ProjectCleanupRepository`: conservative file scanning/deletion.
- `BackgroundThrottle`: background I/O throttling helper.

## Minecraft Adapter Layer

Use `src/main/java/io/github/luma/minecraft` for Minecraft APIs, capture hooks, world mutation, commands, access control, bootstrap, and runtime tests. Keep product rules in domain services and raw storage in repositories.

### Capture

- `HistoryCaptureManager`: mixin-facing capture facade.
- `CaptureSessionRegistry`: active buffers, dirty flags, session states, flush fingerprints.
- `CaptureDiagnosticsRegistry`, `CaptureSessionDiagnostics`: accepted mutation traces and capture summaries.
- `TrackedProjectCatalog`: active project metadata cache for capture matching.
- `TrackedProject`, `ProjectTrackingIndex`: dimension/chunk membership for tracked workspaces.
- `WorldMutationContext`: prevents Lumi operations from reentering capture and can suppress fallback capture during native external-tool undo/redo.
- `WorldMutationCaptureGuard`: duplicate hook protection.
- `WorldMutationCapturePolicy`: block mutation filtering and runtime-only state rejection.
- `EntityMutationCapturePolicy`, `EntityMutationTracker`, `EntitySnapshotService`, `EntitySnapshotOverride`: entity capture filtering and payload handling.
- `AutoCheckpointService`, `AutoCheckpointCommandClassifier`: pending-draft auto checkpoints before large vanilla commands and external WorldEdit/Axiom edits.
- `MutationSourcePolicy`: mutation source classification.
- `ExplosiveEntityContextRegistry`: TNT/explosion causal context.
- `SessionStabilizationService`: dirty chunk reconciliation before save/freeze/undo/redo.
- `CapturePersistenceCoordinator`: async maintenance executor for recovery and baseline writes.
- `ChunkSnapshotCaptureService`, `SnapshotCaptureService`: server-thread chunk/snapshot capture into immutable payloads.
- `ChunkSectionOwnershipRegistry`, `ChunkSectionOwnerLookup`, `DirectSectionMutationCaptureService`: lower-level section owner fallback capture.
- `UndoRedoHistoryManager`: live undo/redo stacks and recent action source data.
- `ServerThreadExecutor`: marshals capture state work to the Minecraft server thread.

### World Apply

- `WorldOperationManager`: single-operation-per-world async prepare plus tick-time apply orchestration.
- `WorldChangeBatchPreparer`: patch/recovery block/entity changes to tick-ready batches.
- `SnapshotBatchPreparer`: snapshot payloads to tick-ready batches.
- `BlockChangeApplier`: actual section/block-entity/entity commit operations.
- `BlockCommitStrategy`, `DirectSectionBlockCommitStrategy`, `VanillaBlockCommitStrategy`: direct loaded-section apply path and safe vanilla fallback selection.
- `ChunkSectionUpdateBroadcaster`: batched section and block-entity client update packets after fast commits.
- `WorldApplyMetrics`: debug counters for direct/fallback sections, changed blocks, packets, light checks, and fallback reasons.
- `WorldApplyBlockUpdatePolicy`: side-effect-suppressed update flags and apply behavior.
- `PersistentBlockStatePolicy`: restore/snapshot normalization for runtime-only states.
- `ConnectedBlockPlacementExpander`: paired blocks such as beds, doors, tall plants.
- `PreparedBlockPlacement`, `PreparedChunkBatch`: prepared immutable apply data.
- `ChunkBatch`, `SectionBatch`, `EntityBatch`: per-chunk apply units.
- `GlobalDispatcher`, `LocalQueue`, `BatchState`, `BatchProcessor`, `HistoryStore`: queue/runtime state for bounded apply.
- `BlockStateNbtCodec`: Minecraft block-state and NBT conversion.
- `EditOperation`: operation identity/type for apply workflows.

### Other Minecraft Modules

- `minecraft/access/LumaAccessControl.java`: permission gate for commands, UI entry points, and dedicated-server mutation workflows.
- `minecraft/bootstrap/WorldBootstrapService.java`: low-priority startup bootstrap for world-origin and root-version metadata.
- `minecraft/command/LumaCommands.java`: diagnostics/help and singleplayer runtime test command entry.
- `minecraft/testing/*`: integrated singleplayer regression service, performance monitor, test volume/run/log helpers.

## Mixins

Use `src/main/java/io/github/luma/mixin` only for Minecraft hook entrypoints. Mixins should delegate quickly to capture/integration services.

- Block mutation hooks: `LevelSetBlockMixin`, `LevelChunkSetBlockStateMixin`, `LevelChunkSectionSetBlockStateMixin`.
- Entity hooks: `EntityMutationMixin`, `ServerLevelEntityLifecycleMixin`, `ServerLevelEntityTickMixin`.
- Player/input/server hooks: `ServerPlayerGameModeMixin`, `ServerGamePacketListenerMixin`.
- Explosion/TNT/falling hooks: `TntBlockMixin`, `ServerLevelExplosionMixin`, `LevelExplosionMixin`, `FallingBlockMixin`, `FallingBlockEntityMixin`.
- Growth/fluid/fire/piston hooks: `SaplingBlockMixin`, `StemBlockMixin`, `CropBlockMixin`, `FlowingFluidMixin`, `FireBlockMixin`, `PistonBaseBlockMixin`.
- Section ownership and Axiom fallback: `ChunkAccessSectionOwnershipMixin`, `AxiomSetBufferPacketMixin`.

## Optional Integrations

Use `src/main/java/io/github/luma/integration` for external builder tool detection and adapters. Do not create hard runtime dependencies on optional tools.

- `OptionalIntegrationBootstrap`: reflectively enables optional integrations.
- `integration/common/*`: capability reporting, external mutation detection, clipboard/schematic/selection contracts.
- `integration/worldedit/WorldEditEditSessionTracker.java`: guarded WorldEdit edit-session extent capture.
- `integration/axiom/*`: Axiom block-buffer extraction/capture helpers.

## Client Layer

Use `src/client/java/io/github/luma` for client-only UI, key input, previews, overlays, and screens. Controllers call services; screens render and own transient route state; view-state records are immutable.

### Navigation And Shared UI

- `ui/navigation/ScreenRouter.java`: route construction and screen transitions.
- `ui/navigation/ProjectWorkspaceTab.java`, `ProjectSidebarNavigation.java`: workspace tab/sidebar model.
- `ui/screen/LumaScreen.java`: common non-pausing owo-ui base screen and Escape behavior.
- `ui/ProjectWindowLayout.java`, `ProjectUiSupport.java`, `LumaUi.java`, `LumaScrollContainer.java`, `SimpleActionCard.java`, `OperationProgressPresenter.java`, `MaterialEntryView.java`: shared layout, surfaces, progress, and compact UI helpers.

### Screens, Controllers, And View State

- Dashboard/projects: `DashboardScreen`, `DashboardScreenController`, `DashboardViewState`, `DashboardProjectItem`.
- Create/open workspace: `CreateProjectScreen`, `CreateProjectScreenController`, `ProjectOpeningScreen`, `ClientWorkspaceOpenService`, `ClientProjectAccess`.
- Project home/history: `ProjectScreen`, `ProjectScreenController`, `ProjectHomeScreenController`, `ProjectHomeViewState`, `ProjectScreenSections`.
- Save and quick save: `SaveScreen`, `SaveDetailsScreen`, `QuickSaveScreen`, `QuickSaveScreenController`, `SaveViewState`, `SaveDetailsViewState`, `SaveDetailsPartialRestoreSection`, `PartialRestoreFormState`.
- Compare: `CompareScreen`, `CompareScreenController`, `CompareViewState`, `CompareScreenSections`.
- Branches: `VariantsScreen`, `VariantsScreenController`, `VariantsViewState`.
- Import/export/share: `ShareScreen`, `ShareScreenController`, `ShareViewState`, `MergePreviewCache`, `MergePreviewKey`, `MergePreviewStatus`, `ShareMergeReviewSection`.
- Recovery: `RecoveryScreen`, `RecoveryScreenController`.
- Settings/more/tools: `SettingsScreen`, `SettingsScreenController`, `MoreScreen`, `CleanupScreen`, `CleanupScreenController`, `DiagnosticsScreen`, `ProjectAdvancedViewState`, `PartialRestoreFormState`. More includes Project tools and Deleted saves tabs.
- Operation polling helpers: `OperationSnapshotViewService`, `ScreenOperationStateSupport`, `WorkspaceHudController`.

### Overlays, Input, Preview, Graphs

- Input chords: `client/input/UndoRedoKeyController`, `UndoRedoKeyChordTracker`, `ExternalUndoRedoPolicy`, `KeyBindingState`.
- HUD, selection, and compare/recent overlays: `WorkspaceHudCoordinator`, `LumiRegionSelectionController`, `LoadedChunkBlockRaycaster`, `LumiRegionSelectionRenderer`, `CompareOverlayCoordinator`, `CompareOverlayRenderer`, `CompareOverlaySurfaceResolver`, `CompareOverlayRenderTypes`, `RecentChangesOverlayCoordinator`, `RecentChangesOverlayRenderer`, `OverlayImmediateRenderer`, `OverlayFaceRenderer`, `OverlayDiagnostics`.
- Client preview renderer: `client/preview/TexturedPreviewCaptureService`, `PreviewCaptureCoordinator`, `PreviewRenderMeshBuilder`, `PreviewRenderMesh`, `PreviewImageCropper`, `PreviewFramingCalculator`, plus `ui/preview/ProjectPreviewTextureCache`.
- Commit graph: `ui/graph/CommitGraphLayout`, `CommitGraphNode`, `CommitGraphComponent`.

## Tests

Use tests to find the expected behavior before broad code reading.

- Domain behavior: `src/test/java/io/github/luma/domain/model` and `src/test/java/io/github/luma/domain/service`.
- Storage round-trips and layout: `src/test/java/io/github/luma/storage`.
- Minecraft capture/apply/runtime: `src/test/java/io/github/luma/minecraft`.
- Optional integrations: `src/test/java/io/github/luma/integration`.
- Client UI controllers/overlays/previews/graphs: `src/test/java/io/github/luma/ui` and `src/test/java/io/github/luma/client`.
- Resource and localization checks: `src/test/java/io/github/luma/resources`.
- Fabric runtime suites: `src/gametest`, `src/idleGametest`, `src/baselineGametest`, `src/baselineIdleGametest`.
- Launch and comparison scripts: `scripts/*.ps1`.
