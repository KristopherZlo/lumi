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
- During stabilization refactors, add named owner classes around hot paths instead of growing `PatchDataRepository`, `RestoreService`, `WorldOperationManager`, or `HistoryCaptureManager`. Keep public facades stable and prove no behavior drift with focused tests.
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
| Pre-Lumi checkpoint and restore | `WorldEntryWarningController`, `WorldInitialBackupService`, `WorldInitialBackupRestoreService` | `WorldEntryBackupScreen`, `LumiBackupRestoreConfirmScreen`, `WorldOpenFlowsMixin`, `EditWorldScreenMixin`, `WorldInitialBackupRepository` | `WorldInitialBackupRestoreServiceTest`, `docs/storage-format.md`, `docs/user-guide.md` |
| Save, amend, quick save | `VersionService`, `QuickSaveScreenController` | `VersionSnapshotPlanner`, `OperationDraftRecoveryService`, `WorkingDraftSessionManager`, `CaptureSessionRegistry`, `CapturePersistenceCoordinator`, `PatchDataRepository`, `PatchMetaRepository`, `SnapshotCaptureService`, `PreviewCaptureRequestService` | `VersionServiceTest`, `OperationDraftRecoveryServiceTest`, `PatchDataRepositoryTest`, `SnapshotStorageTest` |
| Restore, quick rollback, full rollback, operation progress | `RestoreService`, `QuickRollbackService` | `RestoreRequestResolver`, `RestorePlanBuilder`, `WorldRootRestoreBaselineScope`, `DirectRestorePatchPlan`, `RestorePayloadLoader`, `RestoreChunkCollector`, `BlockTargetStateResolver`, `VersionLineageService`, `WorldOperationManager`, `ExactReplayStateQueue`, `ExactReplayStateGuard`, `FluidReplayUpdateScheduler`, `WorldOperationBossBarManager`, `WorldApplyBudgetPlanner`, `WorldChangeBatchPreparer`, `PreparedWorldChangeBatches`, `MechanismReplayScope`, `SnapshotBatchPreparer`, `BlockChangeApplier`, `SectionContainerRewriteCommitStrategy`, `SectionNativeBlockCommitStrategy`, `DirectChunkBlockCommitStrategy`, `RecoveryRepository` | `RestoreServiceTest`, `QuickRollbackServiceTest`, `RestoreRequestResolverTest`, `RestorePlanBuilderTest`, `RestorePayloadLoaderTest`, `RecoveryRepositoryTest`, `WorldChangeBatchPreparerTest`, `WorldApplyBudgetPlannerTest`, `ExactReplayStateQueueTest`, `FluidReplayUpdateSchedulerTest`, `docs/architecture.md` |
| Partial restore | `RestoreService`, `PartialRestorePlanner`, `PartialRestoreTargetStatePlanner` | `SaveDetailsScreen`, `SaveDetailsScreenController`, `SaveDetailsPartialRestoreSection`, `LumiRegionSelectionController`, `LumiRegionSelectionTeachingController`, `LumiRegionSelectionRenderer`, `PatchDataRepository`, `SnapshotReader`, `BaselineChunkRepository` | `PartialRestorePlannerTest`, `PartialRestoreTargetStatePlannerTest`, `PartialRestoreFormStateTest`, `LumiRegionSelectionStateTest`, `SelectionToolTeachingStateTest`, `docs/storage-format.md` |
| Live capture and recovery draft creation | `HistoryCaptureManager` | `CaptureEligibilityService`, `CaptureBaselineCoordinator`, `WorkingDraftSessionManager`, `CaptureSessionRegistry`, `TrackedProjectCatalog`, `ProjectTrackingIndex`, `WorldMutationCapturePolicy`, `EntityMutationCapturePolicy`, `EntitySpawnCaptureQueue`, `SessionStabilizationService`, relevant mixin | capture tests under `src/test/java/io/github/luma/minecraft/capture` |
| Undo/redo and recent actions | `UndoRedoService`, `UndoRedoHistoryManager`, `UndoRedoKeyController` | `UndoRedoRequestQueue`, `LiveUndoRedoActionRecorder`, `ExternalUndoRedoPolicy`, `AxiomUndoRedoBridge`, `WorldOperationManager`, `RecentChangesOverlayCoordinator`, `RecentChangesOverlayRenderer`, `LumiShortcutInteractionGate`, `EntityMutationCapturePolicy`, `EntitySpawnCaptureQueue` | `UndoRedoActionStackTest`, `UndoRedoRequestQueueTest`, `ExternalUndoRedoPolicyTest`, `AxiomUndoRedoBridgeTest`, `RecentChangesOverlayRendererStateTest`, `LumiShortcutInteractionGateTest` |
| Branches, branch switching, and history editing | `VariantService`, `HistoryEditService`, `VersionLineageService` | `VariantRepository`, `HistoryTombstoneRepository`, `VariantsScreenController`, `VariantsScreen`, `SaveDetailsScreen`, `BranchCreationDialogStateFactory`, `BranchCreationDialogView` | `VariantServiceTest`, `HistoryEditServiceTest`, `VersionLineageServiceTest`, `VariantsScreenControllerTest`, `BranchCreationDialogStateFactoryTest` |
| Standalone history journey client GameTest | `src/historyJourneyGametest/java/io/github/luma/minecraft/testing/HistoryJourneyClientGameTests`, `HistoryJourneyScenario` | `HistoryJourneyCheckpoint`, `HistoryJourneyKeyDriver`, `HistoryJourneySingleplayerSupport`, `VariantService`, `RestoreService`, `VersionService`, `UndoRedoService` | `runHistoryJourneyClientGameTest`, `docs/test-client.md`; this suite is separate from crash harnesses and does not add a `/lumi testing` command |
| Import/export/share/merge | `HistoryShareService`, `ProjectArchiveService`, `VariantMergeService` | `MergeConflictZoneBuilder`, `ProjectArchiveRepository`, `ShareScreenController`, `VariantsScreenController`, `MergePreviewCache`, `ShareMergeReviewSection` | `HistoryShareServiceTest`, `ProjectArchiveServiceTest`, `VariantMergeServiceTest` |
| Compare and material summaries | `DiffService`, `MaterialDeltaService` | `CompareScreenController`, `AsyncCompareCache`, `CompareOverlayPreparationService`, `CompareOverlayCoordinator`, `CompareOverlayRenderer`, `CompareOverlaySpatialIndex`, `OverlayMeshBatch`, `OverlayMeshBuffer`, `CompareOverlaySurfaceResolver` | `DiffServiceTest`, `AsyncCompareCacheTest`, compare overlay tests |
| Preview generation | `PreviewCaptureRequestService`, `PreviewService`, `PreviewCaptureCoordinator` | `PreviewBoundsResolver`, `TexturedPreviewCaptureService`, `PreviewRenderMeshBuilder`, `PreviewImageCropper` | `PreviewServiceTest`, `PreviewCaptureRequestRepositoryTest`, preview tests |
| Recovery UI and recovery actions | `RecoveryService`, `RecoveryScreenController` | `OperationDraftRecoveryService`, `RecoveryRepository`, `CapturePersistenceCoordinator`, `ScreenOperationStateSupport` | `OperationDraftRecoveryServiceTest`, `RecoveryRepositoryTest`, recovery model tests |
| Cleanup and integrity | `ProjectCleanupService`, `ProjectIntegrityService` | `ProjectCleanupRepository`, `CleanupScreenController`, `ProjectRepository` | `ProjectCleanupRepositoryTest`, `ProjectArchiveRepositoryTest` |
| Storage format or path changes | `ProjectLayout`, exact repository class | `StorageIo`, `GsonProvider`, matching domain model record | `ProjectLayoutTest`, repository tests, `docs/storage-format.md` |
| Optional builder tool integration and auto checkpoints | `ExternalToolIntegrationRegistry`, `OptionalIntegrationBootstrap`, `AutoCheckpointService`, `AutoCheckpointCommandClassifier` | `WorldEditSessionBridge`, `WorldEditEditSessionTracker`, Axiom classes, integration mixins, `ServerGamePacketListenerMixin` | integration tests, `AutoCheckpointCommandClassifierTest`, `docs/architecture.md` |
| Commands and runtime tests | `LumaCommands`, `LumaClientCommands`, `SingleplayerTestingService` | `ClientWorkspaceOpenService`, `SingleplayerGameplayRegressionSuite`, `SingleplayerBulkApplyDiagnostics`, `LumiBackupStressClientScenario`, `LumiTestFailpoints`, scripts under `scripts/` | `/lumi testing smoke`, `/lumi testing player-flow`, `/lumi testing crash-safety`, `/lumi testing external-tools`, `LUMI_SINGLEPLAYER_TEST_MODE=backup-stress`, `docs/commands.md`, `docs/test-client.md` |
| Client navigation and screen behavior | `ScreenRouter`, the route screen, route controller, route view state | `LumaScreen`, `ProjectWindowLayout`, `ProjectSidebarNavigation`, section builders | UI controller tests, `LumiScreenClientGameTests`, `docs/development.md` |
| Client update notices | `UpdateCheckService`, `UpdatePromptCoordinator` | `HttpUpdateSource`, `UpdateSourceChain`, `UpdateCandidateSelector`, `UpdateAvailableScreen`, `updates/lumi-fabric.json` | `src/test/java/io/github/luma/client/update`, `LanguageFilesTest`, `docs/user-guide.md`, `docs/development.md` |

## Bootstrap And Global Entry Points

- `src/main/java/io/github/luma/LumaMod.java`: Fabric server entry point, event wiring, server tick operation pump, shutdown capture flush.
- `src/client/java/io/github/luma/LumaClient.java`: Fabric client entry point, keybindings, overlays, preview coordinator, client UI registration.
- `src/main/resources/fabric.mod.json`: mod metadata, entrypoints, dependencies.
- `src/main/resources/lumi.mixins.json`: mixin registration; inspect only when capture hooks or Minecraft mutation entrypoints change.
- `src/main/java/io/github/luma/debug/LumaDebugLog.java`: debug logging categories and global/workspace debug checks.
- `src/main/java/io/github/luma/debug/LumaLoadLog.java`: separate opt-in runtime load log behind `-Dlumi.loadLog=true`, writing slow spans, operation metrics, and top cumulative cost summaries to `logs/lumi-load.log` by default.
- `src/client/java/io/github/luma/client/diagnostics/*`: test-client client-runtime load logging behind `-Dlumi.clientLoadLog=true`, including CPU/JVM memory/GC/direct-buffer samples, render-frame pressure, OpenGL renderer info, and optional background `nvidia-smi` GPU metrics.
- `src/main/java/io/github/luma/debug/LumaDiagnosticsLog.java`: focused light/shadow and restore block-apply diagnostic logs behind `-Dlumi.lightLog=true` and `-Dlumi.blockApplyLog=true`.
- `src/main/java/io/github/luma/debug/StructuredDiagnosticsLog.java`: shared key-value file sink for focused diagnostic logs.
- `src/main/java/io/github/luma/minecraft/debug/HistoryDebugLog.java`: focused history capture, undo/redo selection, replay, redstone, and mechanism callback diagnostics for runtime debugging.
- `src/main/java/io/github/luma/debug/StartupProfiler.java`: startup-only diagnostics behind `-Dlumi.startupProfile=true`.

## Domain Model

Use `src/main/java/io/github/luma/domain/model` for value objects, persisted records, summaries, and focused mutable runtime state. Do not add Minecraft APIs, file I/O, UI state, or broad orchestration here.

- Project identity/settings: `BuildProject`, `ProjectSettings`, `ProjectVariant`, `ProjectVersion`, `VersionKind`, `WorldOriginInfo`, `WorldInitialBackupManifest`.
- Coordinates/bounds/chunks: `BlockPoint`, `Bounds3i`, `ChunkPoint`, `ChunkSectionPoint`, `ChunkDelta`, `SectionChangeMask`.
- Stored changes and payloads: `StoredBlockChange`, `StoredEntityChange`, `StoredChangeAccumulator`, `StatePayload`, `EntityPayload`, `PatchWorldChanges`, `PatchMetadata`, `PatchStats`, `PatchChunkSlice`, `PatchEntityChunkIndex`, `SectionFingerprint`, `ChunkPayloadSlice`, `ContentRef`.
- Snapshots: `SnapshotRef`, `SnapshotMetadata`, `SnapshotData`, `SnapshotChunkData`, `SnapshotSectionData`, `ChunkSnapshotPayload`, `ChunkSectionSnapshotPayload`.
- Recovery: `RecoveryDraft`, `RecoveryDraftSummary`, `RecoveryJournalEntry`, `RestoreReturnPoint`, `PendingRestoreCompletion`, `PendingRestoreCompletionKind`.
- Operations/progress/HUD: `OperationHandle`, `OperationProgress`, `OperationSnapshot`, `OperationStage`, `WorkspaceHudSnapshot`; native in-world progress is adapted by `WorldOperationBossBarManager`.
- Diff/compare/material summaries: `VersionDiff`, `DiffBlockEntry`, `ChangeStats`, `PendingChangeSummary`, `MaterialDeltaEntry`.
- Restore and partial restore: `RestorePlanSummary`, `RestorePlanMode`, `PartialRestoreRequest`, `PartialRestorePlanSummary`, `PartialRestoreMode`, `PartialRestoreRegionSource`.
- Branch merge/share/archive: `VariantMergePlan`, `VariantMergeApplyRequest`, `MergeConflictZone`, `MergeConflictResolution`, `MergeConflictZoneResolution`, `ProjectArchiveManifest`, `ProjectArchiveEntry`, `ProjectArchiveScope`, `ProjectArchiveScopeType`, `ProjectArchiveExportResult`, `ProjectArchiveImportResult`, `HistoryPackageFileSummary`, `HistoryPackageImportResult`, `ImportedHistoryProjectSummary`, `ExternalSourceInfo`.
- Working draft and undo/redo runtime: `TrackedChangeBuffer`, `CaptureSessionState`, `UndoRedoAction`, `UndoRedoActionStack`, `WorldMutationSource`.
- Preview: `PreviewInfo`, `PreviewCaptureRequest`.
- History visibility: `HistoryTombstones`.
- Cleanup/integrity: `ProjectCleanupPolicy`, `ProjectCleanupCandidate`, `ProjectCleanupReport`, `ProjectIntegrityReport`.

## Domain Services

Use `src/main/java/io/github/luma/domain/service` for business workflows and product rules. Services may coordinate repositories and Minecraft adapters, but they should not render UI, parse raw paths ad hoc, or mutate Minecraft blocks directly.

- `ProjectService`: create/load/update projects, bootstrap dimension workspaces, world-origin and `WORLD_ROOT` project rules, and avoid promoting isolated operation drafts while a world operation is still active.
- `VersionService`: save/amend versions, isolate operation drafts, write patch-first history, stage partial-restore versions before world mutation, publish staged versions after successful apply, request snapshots/previews; delegates snapshot policy and chunk collection to `VersionSnapshotPlanner`.
- `RestoreService`: full restore, partial restore, pre-restore safety checkpoints, restore return points, operation orchestration, missing-baseline validation for `WORLD_ROOT`, staged partial-restore publication, and bounded mechanism target-state reconciliation; delegates request/variant safety checks to `RestoreRequestResolver`, `WORLD_ROOT` fallback baseline scoping to `WorldRootRestoreBaselineScope`, exact-position target reconstruction to `BlockTargetStateResolver`, and restore chunk/position collection to `RestoreChunkCollector`.
- `RestoreCompletionRecoveryService`: completes pending full/partial restore metadata publication after successful world apply when the original completion callback was interrupted.
- `RestoreRequestResolver`: target version/variant resolution and imported-package trust gating before restore planning.
- `RestorePlanBuilder`: checkpoint snapshot, patch metadata chain, and whole-dimension baseline-gap planning for full restore.
- `RestorePayloadLoader`: version patch metadata lookup plus full/selective block and entity payload reads for restore planning.
- `QuickRollbackService`: fast dirty-draft rollback to the active head, mechanism halo reconciliation with selected-area clipping, and return-before-restore workflow.
- `RecoveryService`: recover, discard, persist, and expose interrupted work.
- `OperationDraftRecoveryService`: promote or merge interrupted save/amend operation drafts back into visible recovery drafts after a crash, world exit, or cancelled operation once no active world operation can still own the isolated draft.
- `HistoryEditService`: rename saves, soft-delete safe saves, soft-delete inactive branches, and persist history tombstones.
- `VariantService`: branch creation, restore-backed branch switching, active head movement, and explicit metadata-only activation for runtime test setup.
- `VariantMergeService`: imported and local branch merge planning, merge apply through normal version persistence; delegates chunk-connected conflict zone grouping to `MergeConflictZoneBuilder`.
- `VersionLineageService`: reachable version filtering, ancestor/common-ancestor/path lookup shared by restore, diff, merge.
- `DiffService`: version-to-version and live-world diff reconstruction.
- `MaterialDeltaService`: material summary aggregation for UI.
- `HistoryShareService`: variant package export/import/delete/list flow for `lumi-projects`.
- `ProjectArchiveService`: full project archive import/export.
- `ProjectCleanupService`: conservative cleanup candidate calculation.
- `ProjectIntegrityService`: storage consistency validation.
- `PreviewCaptureRequestService`: durable request queue for client-side preview capture.
- `PreviewService`: legacy/simple preview sampling and PNG writing.
- `PreviewBoundsResolver`: changed-region bounds for previews, filtered through builder-visible changes.
- `PreviewColumnSampler`, `IsometricPreviewRenderer`: preview scene sampling/render helpers.
- `PartialRestorePlanner`: selected-region restore planning and filtering.
- `PartialRestoreDraftRewriter`: shared post-restore recovery-draft rewrite that preserves only out-of-scope pending changes.
- `BuilderChangeSurfacePolicy`: shared builder-visible block-change rule for stats, diffs, overlays, and preview framing.
- `ChangeStatsFactory`: change stats, patch metadata summaries, pending summaries.
- `ChunkSelectionFactory`: chunk list derivation from bounds or changes.

## Storage

Use `src/main/java/io/github/luma/storage` and `src/main/java/io/github/luma/storage/repository` for file paths, JSON manifests, binary payloads, archives, and atomic writes. Repositories should not depend on UI, screen state, or tick-time apply batches.

- `ProjectLayout`: single source of truth for world/project-relative paths.
- `GsonProvider`: shared JSON configuration.
- `StorageIo`, `StorageLimits`: atomic writes, bounded NBT/binary reads, low-level storage utilities.
- `ProjectRepository`: `project.json` metadata.
- `VariantRepository`: `variants.json` branch heads and branch list.
- `HistoryTombstoneRepository`: `history-tombstones.json` soft-delete visibility metadata.
- `VersionRepository`, `VersionIndexRepository`: `versions/*.json` manifests and disposable `versions/index.json` cache.
- `WorldOriginRepository`: shared `world-origin.json` manifest and corruption quarantine behavior.
- `WorldInstallationRepository`: world-level Lumi installation markers such as fresh-world creation and alpha checkpoint gate acknowledgement.
- `WorldInitialBackupRepository`: one-time pre-mod raw chunk backup manifest and size-budgeted compressed chunk NBT payloads.
- `PatchMetaRepository`: `patches/*.meta.json` chunk index, visible section index, entity old/new chunk index, and lightweight patch metadata.
- `PatchDataRepository`, `PatchPayloadWriter`, `PatchPayloadReader`, `PatchFrameCompression`, `PatchSectionFrameCodec`, `PatchPayloadMetadataBuilder`, `PatchEntityChunkIndexLookup`: `patches/*.bin.lz4` facade reads/writes, patch frame compression, section frame encoding, section fingerprint metadata, entity index construction, and selective chunk/section/entity-frame reads.
- `SnapshotRepository`, `SnapshotReader`, `SnapshotWriter`: checkpoint snapshot payload boundary, including chunk-addressable frame indexes and snapshot section content refs.
- `PayloadContentRepository`: content-addressed immutable payload blobs under `cache/content`.
- `RecoveryRepository`: recovery draft, WAL, operation draft, journal, restore return point, and pending restore completion persistence.
- `BaselineChunkRepository`: whole-dimension baseline chunks under `cache/baseline-chunks`.
- `PreviewCaptureRequestRepository`: `preview-requests/*.json` queue.
- `ProjectArchiveRepository`: zip archive import/export file copying and archive manifest boundary.
- `ProjectCleanupRepository`: conservative file scanning/deletion.
- `BackgroundThrottle`: background I/O throttling helper.

## Minecraft Adapter Layer

Use `src/main/java/io/github/luma/minecraft` for Minecraft APIs, capture hooks, world mutation, commands, access control, bootstrap, and runtime tests. Keep product rules in domain services and raw storage in repositories.

### Capture

- `HistoryCaptureManager`: mixin-facing capture facade.
- `CaptureEligibilityService`: source/access, causal-action, direct/deferred, and hidden builder-surface capture gates.
- `CaptureBaselineCoordinator`: active-session baseline snapshots and pre-mutation baseline corrections.
- `CaptureSessionRegistry`: active buffers, dirty flags, session states, flush fingerprints.
- `WorkingDraftSessionManager`: durable working draft session ownership, recovery draft persistence, freeze/consume/snapshot/discard, idle flushes, and post-save working-draft base rebasing.
- `ActiveSessionRegionPolicy`: active causal-envelope and player-loaded chunk membership for secondary capture that still carries or can reuse causal action ownership.
- `CaptureDiagnosticsRegistry`, `CaptureSessionDiagnostics`, `CaptureDiagnosticsLogger`: accepted mutation traces, capture summaries, reconcile summaries, and throttled skipped-capture logs.
- `TrackedProjectCatalog`, `ProjectCatalogCache`: active project metadata cache for capture matching, refreshed only by explicit invalidation and released on server shutdown.
- `TrackedProject`, `ProjectTrackingIndex`: dimension/chunk membership for tracked workspaces.
- `WorldMutationContext`: prevents Lumi operations from reentering capture, suppresses fallback capture while internal prepared apply, native WorldEdit/FAWE undo/redo, or experimental native Axiom replay diagnostics are running, and scopes deliberate history entity replay separately from incidental item drops created by internal apply.
- `WorldMutationCaptureGuard`: duplicate hook protection.
- `WorldMutationCapturePolicy`: block mutation classification for direct capture, deferred stabilization, and transient-state rejection.
- `BlockUpdateCaptureContext`: redstone/mechanism neighbor and scheduled-tick source scoping for final-state stabilization.
- `DeferredWorldMutationContext`, `DeferredWorldMutationContexts`: action/source/access propagation for delayed vanilla block events, scheduled ticks, and moving piston block entities so their settled fallout can join the originating live undo/redo action. Block events and scheduled ticks consume bounded mechanism depth so self-sustaining redstone clocks cannot regenerate the same action indefinitely; moving piston block entities preserve the existing piston action id without increasing that depth so chained piston carriers still reconcile their moved blocks. Dirty chunks reconcile only after a short tick-settle window unless a final save/freeze drain explicitly requires immediate loaded-chunk reconciliation; live undo/redo force-loads pending stabilization chunks before its pre-selection drain.
- `BlockEntityMutationSnapshotRegistry`: before/after payload capture for block entities whose NBT changes without a block-state replacement, including vanilla container slot mutations.
- `EntityMutationCapturePolicy`, `EntityMutationTracker`, `EntityCausalContextRegistry`, `EntitySpawnCaptureQueue`, `EntitySnapshotService`, `EntitySnapshotOverride`: entity capture filtering and payload handling, including player-caused death context and post-world-acceptance spawn snapshots that preserve the original live undo action order.
- `AutoCheckpointService`, `AutoCheckpointCommandClassifier`: pending-draft auto checkpoints before large vanilla commands and external WorldEdit/Axiom edits.
- `MutationSourcePolicy`: mutation source classification, including causal-action gates for ambient secondary direct capture, pending dirty-chunk redstone/piston fallback, and deferred physics fallout.
- `ExplosiveEntityContextRegistry`: TNT/explosion causal context.
- `SessionStabilizationService`: dirty chunk reconciliation before save/freeze/undo/redo; pending dirty chunks keep the latest causal action context and hidden builder-surface visibility so repeated mechanism toggles reconcile into the selected live undo/redo action while causal fluid/falling-block fallout stays durable but quiet. Chunks that still contain transient `moving_piston` stay pending instead of being snapshotted as settled air.
- `CapturePersistenceCoordinator`: separate async draft-flush queue and bounded baseline-write pool for recovery and baseline persistence.
- `ChunkSnapshotCaptureService`, `SnapshotCaptureService`: server-thread chunk/snapshot capture into immutable payloads.
- `ChunkSectionOwnershipRegistry`, `ChunkSectionOwnerLookup`, `DirectSectionMutationCaptureService`: lower-level section owner fallback capture.
- `LiveUndoRedoActionRecorder`: fan-out from accepted captured/stabilized deltas into the volatile live undo/redo action stack.
- `UndoRedoHistoryManager`: in-memory live undo/redo stacks and recent action source data; these stacks do not survive restart and are not consumed by save/amend.
- `UndoRedoActionGroupingPolicy`: source-aware live action identity selection, including buffer-level and set-block-packet Axiom place/break captures.
- `ServerThreadExecutor`: marshals capture state work to the Minecraft server thread.

### World Apply

- `WorldOperationManager`, `WorldOperationLifecycle`, `WorldOperationTickRunner`, `WorldOperationShutdownHandler`, `WorldOperationMetricsReporter`, `LightRefreshActiveOperation`, `WorldOperationRegistry`, `WorldApplyBudgetPlanner`, `WorldApplyOperationProfile`, `WorldApplyPerformanceGovernor`, `ApplyCostModel`, `WorldApplyChunkPreloader`, `ServerLevelChunkPreloadAccess`, `WorldApplyChunkLoadContext`, `WorldApplyChunkResolver`, `WorldApplyNoOpPruner`, `WorldApplyVerificationService`, `WorldApplyVerificationRepairer`: single-operation-per-world async prepare plus optional fast-profile chunk preload and tick-time apply orchestration, including explicit normal/history-fast/diagnostic/maximum profiles for preload/sync-load/sparse-block/native-cell/rewrite/redstone/light/block-entity/entity budgets, stall-aware one-at-a-time sync preload recovery after ticket progress stops, active/last operation state tracking with a bounded recent-operation window, per-tick advance failure handling, shutdown drain/failure handling, final metrics summary reporting, light-refresh drain/barrier/publish follow-up handling, observed kind-specific work cost gating, fast-profile-only chunk reacquire after preload across direct/native/rewrite paths, fast-profile mixed rewrite/native/sparse work within one tick budget, loaded-chunk no-op pruning that preserves only real updates plus forced/adjacent/boundary exact replay, touched-position post-apply verification/repair for restore-style operations, and minimum fast-profile sparse/direct floors after adaptive downscale, with final apply metrics available to runtime tests.
- `WorldChangeBatchPreparer`, `PreparedWorldChangeBatches`, `MechanismReplayScope`, `EntityApplyMode`, `RestoreEntityCleanupPolicy`, `BlockStatePaletteDecoder`: patch/recovery block/entity changes, large live undo/redo actions, v7/v8 section frames, and exact target-state maps to tick-ready sparse or section-native batches with operation-scoped palette decode caching, mechanism replay scope analysis, and explicit delta versus authoritative entity replacement modes.
- `SnapshotBatchPreparer`: snapshot payloads to tick-ready section-native batches without expanding dense sections into per-block placements, plus sparse selected-position batches for exact root restore without decoding palettes inside cell loops.
- `BlockChangeApplier`: actual section/block-entity/entity commit operations, including authoritative non-player entity cleanup for restore target chunks and scoped saved-entity replay that remains allowed while internal apply suppresses incidental item drops.
- `SectionContainerRewriteCommitStrategy`, `SectionRewriteApplyPlanner`, `PalettedContainerDataSwapper`, `SectionNativeBlockCommitStrategy`, `DirectChunkBlockCommitStrategy`, `SparseDeleteFastPathClassifier`, `ChunkHeightmapUpdatePlan`, `HeightmapColumnUpdater`, `DirectSectionBlockCommitStrategy`, `VanillaBlockCommitStrategy`: dense section container rewrite path, section-native loop path, chunk-level direct sparse apply path with fast-profile loaded-chunk reacquire, safe delete-to-air pruning, per-column heightmap coalescing, and safe vanilla fallback selection.
- `ChunkSectionUpdateBroadcaster`, `WorldLightUpdateQueue`, `WorldLightUpdateContext`, `ChunkSkylightRefreshQueue`, `ServerChunkSkylightRefreshAccess`: batched section/block-entity client update packets and operation-scoped deferred light preparation, dirty-chunk preload, bulk section-status and `ChunkSkyLightSources` refresh for low-level apply paths, check drains, loaded dirty chunk marking before and after threaded light-engine barriers with missing-chunk diagnostics, and automatic `light-refresh` follow-up actions after block/entity replay.
- `WorldApplyMetrics`: debug counters for rewrite/native/direct/fallback sections, changed blocks, packets, preload pipeline state, redstone updates, restore verification, prepared/applied light checks, apply/work ticks, redstone/light-drain ticks/duration, and fallback reasons.
- `WorldApplyBlockUpdatePolicy`: side-effect-suppressed update flags and apply behavior.
- `RedstoneReplayUpdatePlanner`, `RedstoneReplayUpdateQueue`, `WorldRedstoneReplayUpdateContext`: scoped, deduplicated neighbor notification planning for replayed redstone power/source state changes after the stored block state has been applied, with focused debug traces for queued and applied neighbor updates.
- `MechanismStatePolicy`, `ExactReplayStateGuard`, `ExactReplayStateQueue`, `ExactReplayGuardBlockPolicy`, `FluidReplayUpdateScheduler`, `WorldReplayTickSuppression`, `DeferredActionFalloutGuard`: shared redstone/mechanism classification plus short stale-callback replay protection for derived states and replay-created mechanism callbacks. Fluid replay hints keep stored target cells under the exact-state guard while loaded connected fluid tails receive bounded vanilla ticks, and replay-to-air targets immediately clear bounded connected non-source flowing tails without deleting saved source water/lava. Mechanism replay hints keep target-air mechanism removals in final replay and suppress stale callback envelopes. Piston bases, piston heads, moving pistons, and observers are not held by the exact-state guard unless an explicit mechanism replay hint targets air; their replay callback envelope is suppressed briefly instead. Mechanism replay and callback decisions are logged through `HistoryDebugLog` when debug tracing is enabled.
- `PersistentBlockStatePolicy`: restore/snapshot normalization for unsafe transient states such as `moving_piston`.
- `ConnectedBlockPlacementExpander`: paired blocks such as beds, doors, tall plants.
- `PistonMechanismPlacementExpander`: settled piston base/head replay companions. It may add the expected `piston_head` for an explicit extended piston base, replace normalized transient air at the expected head position, recover a retracted base when undo targets a transient moving-piston base, or clear the old head for a known retracting base; it must not infer a piston base from a head-only placement.
- `PreparedBlockPlacement`, `PreparedChunkBatch`, `PreparedSectionApplyBatch`, `PreparedChunkBatchCollapser`, `LumiSectionBuffer`: prepared immutable apply data and collapse logic for sparse and section-native work, including internal replay hints for generated mechanical companion placements. Section-cell masks use the storage-neutral domain `SectionChangeMask`.
- `ChunkBatch`, `SectionBatch`, `EntityBatch`: per-chunk apply units.
- `GlobalDispatcher`, `LocalQueue`, `BatchState`: queue/runtime state for bounded apply.
- `BlockStateNbtCodec`: Minecraft block-state and NBT conversion.

### Other Minecraft Modules

- `minecraft/access/LumaAccessControl.java`: permission gate for commands, UI entry points, and dedicated-server mutation workflows.
- `minecraft/bootstrap/WorldBootstrapService.java`: low-priority startup bootstrap for world-origin, completed pre-open checkpoint verification, migration, and root-version metadata.
- `minecraft/bootstrap/WorldInitialBackupService.java`: one-time pre-open checkpoint creation with opt-in raw chunk backup capture for server bootstrap verification and client progress.
- `minecraft/bootstrap/WorldInitialBackupRestoreService.java`: vanilla Edit World restore service that writes completed pre-mod backup chunks back into region files while preserving Lumi project history.
- `minecraft/bootstrap/WorldInitialBackupIdentityReader.java`: client-side world identity lookup from Lumi origin metadata or `level.dat` before server entry.
- `minecraft/bootstrap/WorldInitialBackupProgress.java`: immutable progress snapshot for pre-open checkpoint and opt-in backup UI.
- `minecraft/bootstrap/WorldInitialBackupWarningService.java`: pre-open decision logic for the alpha checkpoint gate on existing pre-Lumi worlds.
- `minecraft/command/LumaCommands.java`: diagnostics/help and singleplayer smoke/full/structure/crash-safety/external-tool runtime test command entries.
- `minecraft/testing/*`: integrated singleplayer regression service, performance monitor, test volume/run/log helpers.
- `debug/LumiTestFailpoints.java`: explicit opt-in crash-harness failpoints shared by storage, domain, and world-apply tests.

## Mixins

Use `src/main/java/io/github/luma/mixin` only for Minecraft hook entrypoints. Mixins should delegate quickly to capture/integration services.

- Block mutation hooks: `LevelSetBlockMixin`, `LevelChunkSetBlockStateMixin`, `LevelChunkSectionSetBlockStateMixin`, `BaseContainerBlockEntityMixin`, `BlockEntitySetChangedMixin`.
- Entity hooks: `EntityMutationMixin`, `ServerLevelEntityLifecycleMixin`, `ServerLevelEntityTickMixin`.
- Player/input/server hooks: `ServerPlayerGameModeMixin`, `ServerGamePacketListenerMixin`.
- Explosion/TNT/falling hooks: `TntBlockMixin`, `ServerLevelExplosionMixin`, `LevelExplosionMixin`, `FallingBlockMixin`, `FallingBlockEntityMixin`.
- Growth/fluid/fire/redstone/piston hooks: `SaplingBlockMixin`, `StemBlockMixin`, `CropBlockMixin` delegating growth source scoping to `minecraft/capture/GrowthMutationSourceScope`, plus `FlowingFluidMixin`, `FireBlockMixin`, `BlockUpdateCaptureMixin`, `BlockEventDataContextMixin`, `ScheduledTickContextMixin`, `LevelTicksContextMixin`, `ServerLevelBlockEventContextMixin`, `PistonBaseBlockMixin`, `PistonMovingBlockEntityContextMixin`, and `MovingPistonBlockTickerMixin` for delayed mechanism context propagation, piston capture scoping, and internal replay piston-physics suppression.
- Client interaction hooks: `MouseHandlerMixin`, `MinecraftInteractionMixin`, `WorldOpenFlowsMixin`, `EditWorldScreenMixin`. These must only establish input suppression/selection context, world-open gating, or vanilla Edit World entry points and delegate to client services such as `LumiShortcutInteractionGate`, `WorldEntryWarningController`, and `WorldInitialBackupRestoreService`.
- Section ownership and Axiom fallback: `ChunkAccessSectionOwnershipMixin`, `AxiomSetBufferPacketMixin`, `AxiomSetBlockPacketMixin`.

## Optional Integrations

Use `src/main/java/io/github/luma/integration` for external builder tool detection and adapters. Do not create hard runtime dependencies on optional tools.

- `OptionalIntegrationBootstrap`: reflectively enables optional integrations.
- `integration/common/*`: capability reporting, explicit external mutation detection, opt-in stack-trace fallback gating, clipboard/schematic/selection contracts.
- `integration/worldedit/WorldEditSessionBridge.java`: stable WorldEdit session selection, clipboard, and schematic-format bridge.
- `integration/worldedit/WorldEditEditSessionTracker.java`: guarded WorldEdit edit-session extent capture.
- `integration/axiom/*`: Axiom block-buffer extraction/capture helpers, set-block packet source scoping, and experimental native undo/redo replay guards.

## Client Layer

Use `src/client/java/io/github/luma` for client-only UI, key input, previews, overlays, and screens. Controllers call services; screens render and own transient route state; view-state records are immutable.

- `client/world/WorldEntryWarningController.java`: client-only world-open gate for the pre-Lumi alpha checkpoint flow and fresh-world marker.
- `client/world/WorldEntryBackupScreen.java`: pre-open alpha checkpoint screen with blue warning text, `Got it!` acceptance, loading label, and Minecraft experience-bar progress.
- `client/world/LumiBackupRestoreConfirmScreen.java`: red confirmation screen for restoring backed-up pre-Lumi chunks from the vanilla Edit World menu.
- `client/update/*`: client-only update-check manifest loading, same-Minecraft-version candidate selection, cached prompt state, website-first source chain, and GitHub fallback manifest handling.

### Navigation And Shared UI

- `ui/navigation/ScreenRouter.java`: route construction and screen transitions.
- `ui/navigation/ProjectWorkspaceTab.java`, `ProjectSidebarNavigation.java`: workspace tab/sidebar model.
- `ui/screen/LumaScreen.java`: common non-pausing owo-ui base screen and Escape behavior.
- `ui/ProjectWindowLayout.java`, `ProjectUiSupport.java`, `LumaUi.java`, `ContextualHelpPresenter.java`, `LumaScrollContainer.java`, `SimpleActionCard.java`, `OperationProgressPresenter.java`, `MaterialEntryView.java`: shared layout, contextual help, surfaces, progress, and compact UI helpers.

### Screens, Controllers, And View State

- Dashboard/projects: `DashboardScreen`, `DashboardScreenController`, `DashboardViewState`, `DashboardProjectItem`.
- Create/open workspace and onboarding: `CreateProjectScreen`, `CreateProjectScreenController`, `ProjectOpeningScreen`, `OnboardingScreen`, `OnboardingTour`, `OnboardingSpotlightOverlay`, `HotkeyInfoScreen`, `ClientOnboardingFlowCoordinator`, `OnboardingWorldPreviewShortcutController`, `OnboardingWorldPreviewDelay`, `ClientWorkspaceOpenService`, `ClientProjectAccess`, `ClientOnboardingService`, `ClientContextualHelpService`, `ClientOnboardingStateRepository`, `LumiRegionSelectionTeachingController`, `SelectionToolTeachingState`, `LumaClientCommands`, `KeyGlyphResolver`, `OnboardingHoldGate`.
- Project home/history: `ProjectScreen`, `ProjectScreenController`, `ProjectHomeScreenController`, `ProjectHomeViewState`, `ProjectScreenSections`, `BranchCreationDialogStateFactory`, `BranchCreationDialogState`, `BranchCreationDialogView`.
- Save and quick save: `SaveScreen`, `SaveDetailsScreen`, `SaveDetailsStateFactory`, `QuickSaveScreen`, `QuickSaveScreenController`, `SaveViewState`, `SaveDetailsViewState`, `SaveDetailsPartialRestoreSection`, `PartialRestoreFormState`.
- Compare: `CompareScreen`, `CompareScreenController`, `CompareViewState`, `CompareScreenSections`.
- Branches: `VariantsScreen`, `VariantsScreenController`, `VariantsViewState`.
- Import/export/share: `ShareScreen`, `ShareScreenController`, `ShareViewState`, `MergePreviewCache`, `MergePreviewKey`, `MergePreviewStatus`, `ShareMergeReviewSection`.
- Recovery: `RecoveryScreen`, `RecoveryScreenController`.
- Settings/more/tools: `SettingsScreen`, `SettingsScreenController`, `MoreScreen`, `CleanupScreen`, `CleanupScreenController`, `DiagnosticsScreen`, `ProjectAdvancedViewState`, `PartialRestoreFormState`. More includes Project tools and Deleted saves tabs.
- Operation polling helpers: `OperationSnapshotViewService`, `ScreenOperationStateSupport`, `WorkspaceHudController`.

### Overlays, Input, Preview, Graphs

- Input chords: `client/input/UndoRedoKeyController`, `UndoRedoKeyChordTracker`, `UndoRedoFailurePolicy`, `LumiShortcutScreenPolicy`, `ExternalUndoRedoPolicy`, `KeyBindingState`, `LumiClientKeyBindings`, `LumiShortcutCatalog`.
- HUD, selection, and compare/pending/recent overlays: `WorkspaceHudCoordinator`, `LumiRegionSelectionController`, `LumiRegionSelectionTeachingController`, `LoadedChunkBlockRaycaster`, `LumiRegionSelectionRenderer`, `CompareOverlayPreparationService`, `CompareOverlayCoordinator`, `CompareOverlayRenderer`, `CompareOverlaySurfaceResolver`, `CompareOverlayRenderTypes`, `PendingChangesOverlayCoordinator`, `PendingChangesOverlayRenderer`, `RecentChangesOverlayCoordinator`, `RecentChangesOverlayRenderer`, `OverlayMeshBatch` for cached section GPU meshes, `OverlayVolumeMerger` for bounded tiled over-cap compare and pending blobs, `OverlayFaceRenderer`, `OverlayDiagnostics`, `ClientRuntimeLoadSampler` for test-client frame/load sampling. Current-world compare refreshes are throttled and reuse existing meshes when the diff content is unchanged.
- Client preview renderer: `client/preview/TexturedPreviewCaptureService`, `PreviewCaptureCoordinator`, `PreviewRenderMeshBuilder`, `PreviewTranslatedBlockGetter`, `PreviewRenderMesh`, `PreviewImageCropper`, `PreviewFramingCalculator`, plus byte-budgeted `ui/preview/ProjectPreviewTextureCache` and `ui/preview/LoadingAnimationComponent`.
- Commit graph: `ui/graph/CommitGraphLayout`, `CommitGraphNode`, `CommitGraphComponent`.

## Tests

Use tests to find the expected behavior before broad code reading.

- Domain behavior: `src/test/java/io/github/luma/domain/model` and `src/test/java/io/github/luma/domain/service`.
- Storage round-trips and layout: `src/test/java/io/github/luma/storage`.
- Minecraft capture/apply/runtime: `src/test/java/io/github/luma/minecraft`.
- Optional integrations: `src/test/java/io/github/luma/integration`.
- Client UI controllers/overlays/previews/graphs: `src/test/java/io/github/luma/ui` and `src/test/java/io/github/luma/client`.
- Resource and localization checks: `src/test/java/io/github/luma/resources`.
- Fabric runtime suites: `src/gametest`, including `LumiScreenClientGameTests` for live screen render/button smoke coverage, plus `src/historyJourneyGametest`, `src/idleGametest`, `src/baselineGametest`, and `src/baselineIdleGametest`.
- Launch and comparison scripts: `scripts/*.ps1`.
