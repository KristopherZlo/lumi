# Save, Compare, and Restore

[Documentation home](README.md) / Workflows

Read the [Project map](project-map.md) first if a term is unfamiliar.
Each walkthrough starts with a player action and ends with an observable result.
Open the named method rather than reading each large file from its first line.

## Save

Example: a player places blocks, presses `Alt+S`, enters a name, and confirms Save.
The result must represent the exact frozen capture, even if players edit again during publication.

### Read in this order

| Step | Class and method | What it does |
| --- | --- | --- |
| 1 | [LumiClient.openSave](../../src/main/java/io/github/lumi/client/LumiClient.java) | Creates the Save screen and connects its handlers to client networking. |
| 2 | [LumiSaveScreen.submit](../../src/main/java/io/github/lumi/client/ui/LumiSaveScreen.java) | Reads the form and passes it to the controller. |
| 3 | [SaveScreenController.submit](../../src/main/java/io/github/lumi/client/ui/SaveScreenController.java) | Validates the name, parses tags, and sends one immutable intent. |
| 4 | [LumiClientNetworking.save](../../src/main/java/io/github/lumi/client/LumiClientNetworking.java) | Sends the command with a request ID and the client's expected shared state. |
| 5 | [LumiServerNetworking.receive and start](../../src/main/java/io/github/lumi/network/LumiServerNetworking.java) | Validates the server request and creates a SaveRequest for the active dimension. |
| 6 | [FabricDimensionRuntime.startSave and createChunkReadySave](../../src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java) | Connects scoped preparation, world capture, the publisher, and captured-generation completion. |
| 7 | [DimensionOperationCoordinator.tick](../../src/main/java/io/github/lumi/minecraft/operation/DimensionOperationCoordinator.java) | Activates queued work and manages the dimension freeze. |
| 8 | [SaveCaptureOperation.advance](../../src/main/java/io/github/lumi/minecraft/operation/SaveCaptureOperation.java) | Advances preparation and frozen capture, then starts background publication. |
| 9 | [SaveService.publishSave](../../src/main/java/io/github/lumi/domain/service/SaveService.java) | Writes immutable history and publishes the branch reference through a journal. |
| 10 | [LumiServerNetworking.terminal](../../src/main/java/io/github/lumi/network/LumiServerNetworking.java) | Sends the outcome and updated history state to clients. |

### What happens before capture

World hooks report mutations through [MutationDurabilityTracker](../../src/main/java/io/github/lumi/minecraft/world/MutationDurabilityTracker.java).
The working index associates each dirty section or entity chunk with a generation.
Origins preserve the state before a history key's first tracked mutation.

[ScopedSavePreparation](../../src/main/java/io/github/lumi/minecraft/world/ScopedSavePreparation.java) selects work inside the active workspace.
Zone Save adds the zone's section-cell scope.
[DurableSavePreparation](../../src/main/java/io/github/lumi/minecraft/world/DurableSavePreparation.java) prepares the durability boundary.
[ChunkLoadingSavePreparation](../../src/main/java/io/github/lumi/minecraft/world/ChunkLoadingSavePreparation.java) makes required chunks ready.

Capture reads live Minecraft state on the server tick while the dimension is frozen.
[BatchedWorldStateCapture](../../src/main/java/io/github/lumi/minecraft/world/BatchedWorldStateCapture.java) limits capture work by a deadline.
The captured result carries the same generation boundary as preparation.

### Publication order

`SaveCaptureOperation` changes to `WRITING` after capture finishes.
At that point, the coordinator can release Save's freeze while background publication continues.
The operation still owns the dimension's mutation slot until its terminal outcome.

`SaveService.publishSave` follows this order:

1. Write captured world objects and changed tree nodes.
2. Publish and validate the immutable object pack.
3. Write the commit and optional tags.
4. Create the `COMMIT_WRITTEN` operation journal.
5. Replace the expected branch reference with compare-and-set.
6. Advance the journal to `REF_PUBLISHED`.
7. Complete the captured-generation cleanup.
8. Mark the journal complete and remove it.

Compare-and-set rejects a branch reference that changed since the request.
Generation cleanup clears only captured changes.
Newer edits remain pending for a later Save.

Start with [SaveScreenControllerTest](../../src/test/java/io/github/lumi/client/ui/SaveScreenControllerTest.java) for form behavior.
Then read [SaveServiceTest](../../src/test/java/io/github/lumi/domain/service/SaveServiceTest.java) and
[SaveCaptureOperationTest](../../src/test/java/io/github/lumi/minecraft/operation/SaveCaptureOperationTest.java) for publication and capture rules.

## Compare

Example: a player selects two versions and asks Lumi to highlight their differences.
Compare reads immutable history.
It does not restore either version or freeze the live dimension.

### Read in this order

| Step | Class and method | What it does |
| --- | --- | --- |
| 1 | [LumiClient.showCompareChanges and startCompare](../../src/main/java/io/github/lumi/client/LumiClient.java) | Starts the selected comparison and shows initial feedback. |
| 2 | [LumiClientNetworking.compare](../../src/main/java/io/github/lumi/client/LumiClientNetworking.java) | Sends the two commit IDs and starts correlated client state. |
| 3 | [CompareCommandHandler.start](../../src/main/java/io/github/lumi/network/CompareCommandHandler.java) | Owns a cancellable request and schedules result batches. |
| 4 | [DimensionComparisonQueries.compare](../../src/main/java/io/github/lumi/minecraft/runtime/DimensionComparisonQueries.java) | Resolves the workspace and optional zone scope, then schedules background work. |
| 5 | [CompareService.compare](../../src/main/java/io/github/lumi/domain/service/CompareService.java) | Finds changed leaves by skipping equal tree hashes. |
| 6 | [BlockDifferenceService.scan](../../src/main/java/io/github/lumi/domain/service/BlockDifferenceService.java) | Decodes changed section pairs and emits directional block differences. |
| 7 | [ClientCompareStore](../../src/main/java/io/github/lumi/client/state/ClientCompareStore.java) | Accepts ordered results for the current request and rejects stale results. |
| 8 | [LumiCompareOverlayRenderer](../../src/main/java/io/github/lumi/client/LumiCompareOverlayRenderer.java) | Prepares and draws the accepted result in the world. |

### Separate history logic from drawing

[CompareOverlayGeometry](../../src/main/java/io/github/lumi/client/CompareOverlayGeometry.java) prepares exact faces and edges from immutable block changes.
[CompareOverlayGpuMesh](../../src/main/java/io/github/lumi/client/CompareOverlayGpuMesh.java) owns GPU buffers for those batches.
GPU upload, drawing, and resource release belong on the render thread.
See [Highlight preparation](highlight-preparation.md) for client progress and initial presentation.

For a wrong block difference, start in `CompareService` or `BlockDifferenceService`.
For a missing or incorrect highlight, start in the client store and renderer.
For responses from an older request, inspect correlation and cancellation before geometry.

Read [CompareServiceTest](../../src/test/java/io/github/lumi/domain/service/CompareServiceTest.java),
[CompareRequestRegistryTest](../../src/test/java/io/github/lumi/network/CompareRequestRegistryTest.java), and
[CompareOverlayGeometryTest](../../src/test/java/io/github/lumi/client/CompareOverlayGeometryTest.java).
The `COMPARE_RENDER` client suite measures the actual renderer.
See [Testing](testing.md#client-gametests).

## Restore

Example: a player selects a saved version and confirms a full Restore.
The server must preserve an exact return point before it changes the world.
It must verify durable world data before it publishes success.

### Read in this order

| Step | Class and method | What it does |
| --- | --- | --- |
| 1 | [LumiClient.openRestore](../../src/main/java/io/github/lumi/client/LumiClient.java) | Opens confirmation and selects the workspace's entity-restore behavior. |
| 2 | [LumiRestoreScreen](../../src/main/java/io/github/lumi/client/ui/LumiRestoreScreen.java) | Coordinates full, selected-area, or outside-area Restore intent. |
| 3 | [LumiClientNetworking.restore](../../src/main/java/io/github/lumi/client/LumiClientNetworking.java) | Sends the target commit with the expected shared state. |
| 4 | [FabricDimensionRuntime.startRestore and createRestore](../../src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java) | Queues activation checks and creates the hidden return-point Save. |
| 5 | [ReturnPointRestoreOperation](../../src/main/java/io/github/lumi/minecraft/operation/ReturnPointRestoreOperation.java) | Holds ownership across the checkpoint and background Restore preparation. |
| 6 | [ReturnPointRestorePreparation](../../src/main/java/io/github/lumi/minecraft/operation/ReturnPointRestorePreparation.java) | Connects the checkpoint, optional prewarm, prepared state, and publication. |
| 7 | [RestoreService.prepare](../../src/main/java/io/github/lumi/domain/service/RestoreService.java) | Plans the exact target and return state from immutable history. |
| 8 | [MinecraftWorldStateApply](../../src/main/java/io/github/lumi/minecraft/world/MinecraftWorldStateApply.java) | Decodes Minecraft state before incremental world mutation. |
| 9 | [RestoreOperation.tick](../../src/main/java/io/github/lumi/minecraft/operation/RestoreOperation.java) | Advances apply, verification, persistence, publication, or safe return. |
| 10 | [BranchRefRestorePublication](../../src/main/java/io/github/lumi/minecraft/operation/BranchRefRestorePublication.java) | Publishes the verified target and captured-boundary cleanup. |

### Preparation, apply, and durability

Preparation reads history and decodes state on background workers.
The apply path consumes prepared state through bounded sessions.
[StreamingPreparedWorldMutationSession](../../src/main/java/io/github/lumi/minecraft/world/StreamingPreparedWorldMutationSession.java)
handles larger plans with bounded decode windows.

Loaded chunks use Minecraft mutation adapters.
Eligible cold chunks can use the gated vanilla storage path.
Both paths must produce the same exact result.

[MinecraftRestorePersistenceSession](../../src/main/java/io/github/lumi/minecraft/world/MinecraftRestorePersistenceSession.java)
connects writes, lighting completion, persistence barriers, and durable rereads.
`WORLD_PERSISTED` records the verified durable world boundary before pointer publication.
The coordinator retains the dimension freeze through the operation's verified terminal outcome.

An apply failure can trigger repair or a safe return to the checkpoint.
An unresolved failure can retain the freeze and journal for recovery.
Read [Storage and recovery](storage.md#interrupted-operations) before you change these transitions.

Read [RestoreServiceTest](../../src/test/java/io/github/lumi/domain/service/RestoreServiceTest.java),
[ReturnPointRestoreOperationTest](../../src/test/java/io/github/lumi/minecraft/operation/ReturnPointRestoreOperationTest.java), and
[RestoreOperationTest](../../src/test/java/io/github/lumi/minecraft/operation/RestoreOperationTest.java).

## Related operations

| Feature | Start here | Difference from the basic walkthrough |
| --- | --- | --- |
| Amend | [SaveRequest](../../src/main/java/io/github/lumi/domain/service/SaveRequest.java), `SaveService` | Replaces HEAD while preserving its parents. |
| Partial or zone Restore | `FabricDimensionRuntime.createPartialRestore` and `createZoneRestore` | Applies a scope and publishes restored keys as pending work. |
| Block-only Restore | [BlockOnlyRestoreService](../../src/main/java/io/github/lumi/domain/service/BlockOnlyRestoreService.java) | Combines target blocks with current entity state. |
| Branch switch | [BranchService](../../src/main/java/io/github/lumi/domain/service/BranchService.java), `FabricDimensionRuntime.createBranchSwitch` | Uses a checkpoint and Restore before publishing the active branch pointer. |
| Workspace switch | [WorkspaceService](../../src/main/java/io/github/lumi/domain/service/WorkspaceService.java), `FabricDimensionRuntime.createWorkspaceSwitch` | Changes the dimension's canonical project scope through verified Restore. |
| Merge | [MergeService](../../src/main/java/io/github/lumi/domain/service/MergeService.java), [ThreeWayMerge](../../src/main/java/io/github/lumi/domain/service/ThreeWayMerge.java) | Prepares a two-parent result with source-wins conflicts. |
| Session Undo/Redo | [LiveActionJournal](../../src/main/java/io/github/lumi/domain/service/LiveActionJournal.java), [LiveActionOperation](../../src/main/java/io/github/lumi/minecraft/operation/LiveActionOperation.java) | Uses per-player session actions over one shared physical world. |
| Quick Restore | `FabricDimensionRuntime.startQuickRollback` | Restores unsaved work through a journaled operation and a session checkpoint. |
| Automatic versions | [AutoVersionService](../../src/main/java/io/github/lumi/domain/service/AutoVersionService.java) | Creates optional ref-neutral versions from durable builder changes. |
| Delete or recover a version | [TombstoneService](../../src/main/java/io/github/lumi/domain/service/TombstoneService.java) | Uses soft-delete metadata and protected refs before permanent cleanup. |
| Tags or rename | [VersionTagService](../../src/main/java/io/github/lumi/domain/service/VersionTagService.java), [VersionDisplayNameService](../../src/main/java/io/github/lumi/domain/service/VersionDisplayNameService.java) | Replaces bounded metadata without changing immutable commit identity. |
| Import or export | [ImportExportService](../../src/main/java/io/github/lumi/domain/service/ImportExportService.java), [DimensionPackageService](../../src/main/java/io/github/lumi/minecraft/runtime/DimensionPackageService.java) | Validates portable packages and applies confirmed import through server workflows. |

For a first change, use the [rename example](contributing.md#example-change-a-version-name-rule).
