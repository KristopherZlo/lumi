package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.DeferredDimensionMutation;
import io.github.lumi.minecraft.operation.LiveActionOperation;
import io.github.lumi.minecraft.operation.BackgroundPreparedMutation;
import io.github.lumi.minecraft.operation.BranchSwitchRestorePublication;
import io.github.lumi.minecraft.operation.BranchRefRestorePublication;
import io.github.lumi.minecraft.operation.CapturedGenerationCompletion;
import io.github.lumi.minecraft.operation.PendingRestorePublication;
import io.github.lumi.minecraft.operation.OperationPriority;
import io.github.lumi.minecraft.operation.RestoreOperation;
import io.github.lumi.minecraft.operation.RestorePublication;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestoreOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestorePreparation;
import io.github.lumi.minecraft.operation.WorkspaceSwitchRestorePublication;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.ActiveWorkspace;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.PackageName;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkspaceSwitchPlan;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.DimensionHistoryViewService;
import io.github.lumi.domain.service.AutoVersionService;
import io.github.lumi.domain.service.HistoryQueryService;
import io.github.lumi.domain.service.ImportExportService;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.MergeService;
import io.github.lumi.domain.service.PreparedMerge;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SavePublisher;
import io.github.lumi.domain.service.SaveService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.domain.service.RecoveryService;
import io.github.lumi.domain.service.SaveJournalRecovery;
import io.github.lumi.domain.service.PublishedApplyRecovery;
import io.github.lumi.domain.service.WorkspaceService;
import io.github.lumi.domain.service.ZoneScope;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.domain.service.TombstoneService;
import io.github.lumi.minecraft.world.BlockEntityBaselineStore;
import io.github.lumi.minecraft.world.BatchedWorldStateCapture;
import io.github.lumi.minecraft.world.ChunkLoadingSavePreparation;
import io.github.lumi.minecraft.world.ChunkLoadSession;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.DurableSavePreparation;
import io.github.lumi.minecraft.world.EntityChunkDurabilityGate;
import io.github.lumi.minecraft.world.MinecraftBlockEntityBaselineCapture;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import io.github.lumi.minecraft.world.MinecraftLiveBlockWorldAccess;
import io.github.lumi.minecraft.world.MinecraftLiveEntityWorldAccess;
import io.github.lumi.minecraft.world.MinecraftChunkLoadAccess;
import io.github.lumi.minecraft.world.MinecraftChunkDurabilityRetention;
import io.github.lumi.minecraft.world.MinecraftWorldStateReader;
import io.github.lumi.minecraft.world.MinecraftWorldStateApply;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.minecraft.world.LoadedChunkMutationScope;
import io.github.lumi.minecraft.world.RestoreBaselineReconciler;
import io.github.lumi.minecraft.world.SavePreparation;
import io.github.lumi.minecraft.world.ScopedSavePreparation;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.repository.WorkspaceRepository;
import io.github.lumi.storage.repository.ZoneRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import io.github.lumi.storage.repository.GarbageCollector;
import io.github.lumi.telemetry.TelemetryService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;

/** Server-authoritative Lumi state owned by one loaded Minecraft dimension. */
public final class FabricDimensionRuntime implements AutoCloseable {
    private static final long AUTO_VERSION_INTERVAL_TICKS = 6_000;
    private static final long DURABILITY_RETRY_INTERVAL_TICKS = 20;
    private static final CommitAuthor AUTO_AUTHOR =
            new CommitAuthor(new UUID(0, 0), "Lumi");
    private final ServerLevel level;
    private final Path repository;
    private final DimensionFreezeState freeze;
    private final DimensionOperationCoordinator operations;
    private final MutationDurabilityTracker mutations;
    private final EntityChunkDurabilityGate entityDurability;
    private final SavePreparation savePreparation;
    private final MinecraftWorldStateReader worldReader;
    private final SaveService saves;
    private final RestoreService restores;
    private final MinecraftWorldStateApply worldApply;
    private final OperationJournalRepository journals;
    private final BranchService branches;
    private final MergeService merges;
    private final RecoveryService recoveries;
    private final WorkspaceService workspaces;
    private final ZoneService zones;
    private final AutoVersionService autoVersions;
    private final TombstoneService tombstones;
    private final DimensionHistoryViewService historyViews;
    private final CausalZoneGrowthTracker zoneGrowth;
    private final ReturnPointRestorePreparation returnPointRestores;
    private final DimensionPackageService packages;
    private final DimensionComparisonQueries comparisons;
    private final GarbageCollectionScheduler garbageCollection;
    private final Executor background;
    private final BranchRefRepository refs;
    private final UUID defaultWorkspaceId;
    private final LiveActionJournal liveActions = new LiveActionJournal();
    private final MinecraftLiveBlockWorldAccess liveWorld;
    private final MinecraftLiveEntityWorldAccess liveEntityWorld;
    private final MinecraftLiveEntityTracker liveEntities;
    private final MinecraftCausalTickTracker causalTicks;
    private final io.github.lumi.minecraft.operation.RestoreStateListener restoreStateListener;
    private final BlockEntityBaselineStore blockEntityBaselines = new BlockEntityBaselineStore();
    private final LoadedChunkMutationScope loadedChunks = new LoadedChunkMutationScope();
    private final MinecraftBlockEntityBaselineCapture baselineCapture =
            new MinecraftBlockEntityBaselineCapture();
    private final MinecraftEntityChunkCapture entityCapture = new MinecraftEntityChunkCapture();
    private OperationJournal pendingRecovery;
    private io.github.lumi.minecraft.world.DimensionFreeze.Lease recoveryLease;
    private volatile UUID selectedWorkspaceId;
    private final AtomicBoolean autoVersionScheduled = new AtomicBoolean();
    private volatile AutoVersionFingerprint lastAutoVersion;
    private long nextAutoVersionTick;

    private FabricDimensionRuntime(
            ServerLevel level,
            Path repository,
            DimensionFreezeState freeze,
            DimensionOperationCoordinator operations,
            MutationDurabilityTracker mutations,
            SaveService saves,
            RestoreService restores,
            MinecraftWorldStateApply worldApply,
            OperationJournalRepository journals,
            Executor background,
            BranchRefRepository refs,
            BranchService branches,
            MergeService merges,
            WorkspaceService workspaces,
            ZoneService zones,
            UUID defaultWorkspaceId,
            UUID activeWorkspaceId,
            OperationJournal pendingRecovery,
            io.github.lumi.minecraft.world.DimensionFreeze.Lease recoveryLease) {
        this.level = level;
        this.repository = repository;
        this.freeze = freeze;
        this.operations = operations;
        this.mutations = mutations;
        this.saves = saves;
        this.restores = restores;
        this.worldApply = worldApply;
        this.journals = journals;
        this.branches = branches;
        this.merges = merges;
        this.workspaces = workspaces;
        this.zones = zones;
        var commits = new CommitRepository(repository);
        autoVersions = new AutoVersionService(commits, refs);
        tombstones = new TombstoneService(
                commits, refs, new TombstoneRepository(repository));
        historyViews = new DimensionHistoryViewService(
                commits,
                new HistoryQueryService(
                        commits, refs, new TombstoneRepository(repository)),
                tombstones, branches, workspaces, zones, autoVersions);
        selectedWorkspaceId = activeWorkspaceId;
        nextAutoVersionTick = level.getGameTime() + AUTO_VERSION_INTERVAL_TICKS;
        zoneGrowth = new CausalZoneGrowthTracker(zones, background,
                failure -> LumiMod.LOGGER.error("Cannot persist causal zone growth", failure));
        recoveries = new RecoveryService(restores, zones);
        this.background = background;
        this.refs = refs;
        this.defaultWorkspaceId = defaultWorkspaceId;
        this.pendingRecovery = pendingRecovery;
        this.recoveryLease = recoveryLease;
        liveWorld = new MinecraftLiveBlockWorldAccess(level, freeze);
        liveEntityWorld = new MinecraftLiveEntityWorldAccess(level, freeze);
        liveEntities = new MinecraftLiveEntityTracker(liveActions, liveEntityWorld);
        causalTicks = new MinecraftCausalTickTracker(
                liveActions, level, freeze, level.getBlockTicks(), level.getFluidTicks());
        entityDurability = new EntityChunkDurabilityGate(mutations);
        restoreStateListener = new RestoreBaselineReconciler(
                entityDurability, blockEntityBaselines);
        returnPointRestores = new ReturnPointRestorePreparation(
                restores, worldApply, refs, journals,
                restoreStateListener, background);
        packages = new DimensionPackageService(
                level.dimension().identifier().toString(), repository,
                level.getServer().getWorldPath(LevelResource.ROOT),
                background, this::activeRef);
        comparisons = new DimensionComparisonQueries(
                repository, background, zones, this::activeWorkspaceId);
        garbageCollection = new GarbageCollectionScheduler(
                level.getGameTime(), background,
                () -> new GarbageCollector(repository).collect(
                        Set.of(), Instant.now().minus(Duration.ofHours(24))),
                result -> {
                    if (result.deletedCommits() > 0 || result.deletedObjects() > 0) {
                        LumiMod.LOGGER.info(
                                "Lumi GC removed {} commits and {} objects from {}",
                                result.deletedCommits(), result.deletedObjects(),
                                level.dimension().identifier());
                    }
                },
                failure -> LumiMod.LOGGER.error(
                        "Lumi background garbage collection failed", failure));
        worldReader = new MinecraftWorldStateReader(level);
        savePreparation = new DurableSavePreparation(worldReader, entityDurability, mutations);
    }

    public static FabricDimensionRuntime open(
            ServerLevel level,
            DimensionRepositoryLayout layout,
            Executor background,
            Executor durabilityBackground) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(durabilityBackground, "durabilityBackground");
        Path repository = layout.resolve(level.dimension().identifier().toString());
        DimensionFreezeState freeze = new DimensionFreezeState();
        var objects = new WorldObjectRepository(repository);
        var commits = new CommitRepository(repository);
        var refs = new BranchRefRepository(repository);
        var active = new ActiveBranchRepository(repository);
        var journals = new OperationJournalRepository(repository);
        var origins = new OriginStore(repository);
        new DimensionHistoryInitializer(objects, commits, refs, active)
                .initialize(UUID.randomUUID());
        var interrupted = journals.read();
        if (interrupted.filter(journal -> journal.kind() == OperationKind.SAVE).isPresent()) {
            new SaveJournalRecovery(commits, refs, journals)
                    .recover(interrupted.orElseThrow());
            interrupted = Optional.empty();
        }
        var activeWorkspaces = new ActiveWorkspaceRepository(repository);
        var workspaceService = new WorkspaceService(
                new WorkspaceRepository(repository), activeWorkspaces, commits, refs);
        UUID defaultWorkspaceId = workspaceService.defaultWorkspaceId();
        workspaceService.initializeDefault(defaultWorkspaceId);
        if (interrupted.isPresent()
                && new PublishedApplyRecovery(refs, active, activeWorkspaces, journals)
                        .finalizeIfPublished(interrupted.orElseThrow())) {
            interrupted = Optional.empty();
        }
        UUID activeWorkspaceId = workspaceService.active().id();
        var recoveryLease = interrupted.isPresent() ? freeze.acquire() : null;
        var working = new WorkingIndexRepository(repository);
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                objects, origins, working, durabilityBackground,
                new MinecraftChunkDurabilityRetention(level));
        var branches = new BranchService(commits, refs, active, working);
        var trees = new MerkleTreeEditor(objects);
        var restoreService = new RestoreService(objects, commits, origins);
        return new FabricDimensionRuntime(
                level, repository, freeze, new DimensionOperationCoordinator(
                        freeze, operation -> logTerminal(level, operation)),
                mutations,
                new SaveService(objects, trees, commits, refs, journals),
                restoreService,
                new MinecraftWorldStateApply(level, freeze), journals,
                background, refs, branches,
                new MergeService(objects, commits, origins, trees), workspaceService,
                new ZoneService(new ZoneRepository(repository)),
                defaultWorkspaceId, activeWorkspaceId,
                interrupted.orElse(null), recoveryLease);
    }

    private static void logTerminal(ServerLevel level, DimensionMutation operation) {
        String description = operation.getClass().getSimpleName()
                + " in " + level.dimension().identifier();
        if (operation.terminalState() == io.github.lumi.minecraft.operation.MutationTerminalState.FAILED
                || operation.terminalState()
                == io.github.lumi.minecraft.operation.MutationTerminalState.DEGRADED) {
            TelemetryService.getInstance().recordFailure(
                    operation.getClass().getSimpleName(),
                    operation.progress().phase(),
                    operation.failure().orElse(null));
        }
        switch (operation.terminalState()) {
            case SUCCEEDED -> LumiMod.LOGGER.info("Lumi operation completed: {}", description);
            case CANCELLED -> LumiMod.LOGGER.warn("Lumi operation cancelled: {}", description);
            case RETURNED -> LumiMod.LOGGER.warn(
                    "Lumi operation could not verify its target and returned safely: {}", description);
            case DEGRADED -> operation.failure().ifPresentOrElse(
                    failure -> LumiMod.LOGGER.error(
                            "Lumi operation degraded its dimension and retained the freeze: "
                                    + description, failure),
                    () -> LumiMod.LOGGER.error(
                            "Lumi operation degraded its dimension and retained the freeze: {}",
                            description));
            case FAILED -> operation.failure().ifPresentOrElse(
                    failure -> LumiMod.LOGGER.error("Lumi operation failed: " + description, failure),
                    () -> LumiMod.LOGGER.error("Lumi operation failed: {}", description));
        }
    }

    public void tick() throws IOException {
        try {
            operations.tick();
        } finally {
            zoneGrowth.flush();
        }
        if (level.getGameTime() % DURABILITY_RETRY_INTERVAL_TICKS == 0) {
            mutations.retryFailedWrites();
        }
        scheduleAutoVersion();
        garbageCollection.tick(level.getGameTime(),
                recoveryJournal().isPresent()
                        || operations.hasActiveOperation()
                        || operations.queuedCount() > 0
                        || !mutations.snapshot().generations().isEmpty());
    }

    private void scheduleAutoVersion() {
        long now = level.getGameTime();
        if (now < nextAutoVersionTick || autoVersionScheduled.get()) {
            return;
        }
        boolean busy = recoveryJournal().isPresent()
                || operations.hasActiveOperation() || operations.queuedCount() > 0;
        nextAutoVersionTick = now + (busy ? 200 : AUTO_VERSION_INTERVAL_TICKS);
        if (busy || mutations.snapshot().generations().isEmpty()
                || !autoVersionScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            background.execute(this::enqueueAutoVersion);
        } catch (RuntimeException rejected) {
            autoVersionScheduled.set(false);
            LumiMod.LOGGER.error("Cannot schedule automatic Lumi version", rejected);
        }
    }

    private void enqueueAutoVersion() {
        try {
            if (recoveryJournal().isPresent()
                    || operations.hasActiveOperation() || operations.queuedCount() > 0) {
                autoVersionScheduled.set(false);
                return;
            }
            BranchRef expected = activeRef();
            var dirty = mutations.snapshot();
            AutoVersionFingerprint fingerprint =
                    new AutoVersionFingerprint(expected.commit(), dirty);
            if (dirty.generations().isEmpty() || fingerprint.equals(lastAutoVersion)) {
                autoVersionScheduled.set(false);
                return;
            }
            UUID workspaceId = activeWorkspaceId();
            SaveRequest request = new SaveRequest(
                    expected, AUTO_AUTHOR, "Automatic version", Instant.now(),
                    workspaceId, Optional.empty(), CommitKind.AUTO);
            BranchName hidden = autoVersions.refName(expected.name(), UUID.randomUUID());
            SaveCaptureOperation operation = createChunkReadySave(
                    request, scopedSavePreparation(request),
                    (save, captured) -> {
                        var result = saves.checkpoint(save, captured, hidden);
                        autoVersions.prune(expected.name(), 64);
                        return result;
                    },
                    ignored -> { });
            operations.enqueue(operation, OperationPriority.NORMAL, completed -> {
                operation.result().ifPresent(result -> lastAutoVersion =
                        new AutoVersionFingerprint(expected.commit(),
                                result.capturedGenerations()));
                autoVersionScheduled.set(false);
            });
        } catch (IOException | RuntimeException failed) {
            autoVersionScheduled.set(false);
            LumiMod.LOGGER.error("Cannot create automatic Lumi version", failed);
        }
    }

    public synchronized Optional<OperationJournal> recoveryJournal() {
        return Optional.ofNullable(pendingRecovery);
    }

    public synchronized BackgroundPreparedMutation<RestoreOperation> startRecovery(
            RecoveryChoice choice,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(terminalObserver, "terminalObserver");
        if (pendingRecovery == null || recoveryLease == null) {
            throw new IllegalStateException("This dimension has no pending recovery");
        }
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
        OperationJournal journal = pendingRecovery;
        RestorePublication publication = recoveryPublication(journal, choice);
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var restore = recoveries.prepare(journal, choice);
                return RestoreOperation.resume(
                        restore, worldApply, publication, journals, journal,
                        restoreStateListener);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
        var operation = new BackgroundPreparedMutation<>(
                preparation,
                () -> {
                    if (!journals.read().filter(journal::equals).isPresent()) {
                        throw new IOException("Recovery journal changed during preparation");
                    }
                },
                ignored -> { }, true, true);
        var lease = recoveryLease;
        recoveryLease = null;
        operations.startWithLease(operation, lease, completed -> {
            synchronized (FabricDimensionRuntime.this) {
                if (completed.terminalState()
                        != io.github.lumi.minecraft.operation.MutationTerminalState.DEGRADED) {
                    pendingRecovery = null;
                }
            }
            terminalObserver.accept(completed);
        });
        return operation;
    }

    private RestorePublication recoveryPublication(
            OperationJournal journal, RecoveryChoice choice) throws IOException {
        if (choice == RecoveryChoice.RETURN_CHECKPOINT) {
            return ignored -> { };
        }
        if (journal.kind() == OperationKind.BRANCH_SWITCH) {
            var target = journal.target();
            var switchTarget = target.branchSwitch().orElseThrow(
                    () -> new IOException("Branch-switch recovery target is missing"));
            BranchRef source = new BranchRef(
                    target.branch(), target.expectedHead(), target.expectedRevision());
            BranchRef destination = refs.read(switchTarget.branch()).orElseThrow(
                    () -> new IOException("Branch-switch recovery branch is missing"));
            if (destination.revision() != switchTarget.targetRevision()
                    || !destination.commit().equals(target.target().orElseThrow())) {
                throw new IOException("Branch-switch recovery target changed");
            }
            var plan = new BranchSwitchPlan(
                    new ActiveBranch(source.name(), switchTarget.expectedActiveRevision()),
                    source, destination);
            if (target.workspaceSwitch().isPresent()) {
                var workspaceTarget = target.workspaceSwitch().orElseThrow();
                var workspacePlan = new WorkspaceSwitchPlan(
                        new ActiveWorkspace(
                                workspaceTarget.expectedWorkspace(),
                                workspaceTarget.expectedRevision()),
                        workspaceTarget.targetWorkspace(), plan);
                return workspaceSwitchPublication(workspacePlan);
            }
            return new BranchSwitchRestorePublication(branches, plan);
        }
        if (journal.target().blockArea().isPresent()
                || journal.target().zoneRestore().isPresent()) {
            return new PendingRestorePublication(mutations);
        }
        if (journal.kind() == OperationKind.QUICK_ROLLBACK) {
            return ignored -> mutations.clear(mutations.snapshot());
        }
        return new BranchRefRestorePublication(refs);
    }

    public void chunkLoaded(LevelChunk chunk) throws IOException {
        loadedChunks.loaded(chunk.getPos().x, chunk.getPos().z);
        baselineCapture.remember(level, chunk, mutations, blockEntityBaselines);
    }

    public void chunkUnloaded(LevelChunk chunk) {
        loadedChunks.unloaded(chunk.getPos().x, chunk.getPos().z);
        blockEntityBaselines.discardChunk(chunk.getPos().x, chunk.getPos().z);
    }

    public boolean isChunkMutationTrackable(int chunkX, int chunkZ) {
        return loadedChunks.contains(chunkX, chunkZ);
    }

    public void entityChunkLoaded(ChunkEntities<? extends EntityAccess> chunk) throws IOException {
        entityDurability.rememberLoaded(
                MinecraftEntityChunkCapture.key(chunk.getPos()),
                entityCapture.capture(level, chunk.getEntities()));
    }

    public boolean permitEntityStore(
            ChunkPos position, Stream<? extends EntityAccess> entities)
            throws IOException {
        var key = MinecraftEntityChunkCapture.key(position);
        return entityDurability.permitStore(key, entityCapture.capture(level, entities));
    }

    public void entityChunkUnloaded(ChunkPos position) {
        entityDurability.discard(MinecraftEntityChunkCapture.key(position));
    }

    public synchronized SaveCaptureOperation startSave(SaveRequest request) throws IOException {
        return startSave(request, ignored -> { });
    }

    public synchronized SaveCaptureOperation startSave(
            SaveRequest request, Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        SaveCaptureOperation operation = createSave(request);
        operations.enqueue(operation, OperationPriority.NORMAL, terminalObserver);
        return operation;
    }

    public synchronized SaveCaptureOperation startZoneSave(
            BranchRef expected,
            CommitAuthor author,
            UUID actor,
            UUID zoneId,
            String message,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        UUID workspaceId = activeWorkspaceId();
        zones.requireActorActive(workspaceId, zoneId, actor);
        return startSave(new SaveRequest(
                expected, author, message, Instant.now(), workspaceId,
                Optional.of(zoneId), CommitKind.ZONE), terminalObserver);
    }

    private SaveCaptureOperation createSave(SaveRequest request) throws IOException {
        return createChunkReadySave(
                request, scopedSavePreparation(request), saves, mutations);
    }

    private SaveCaptureOperation createChunkReadySave(
            SaveRequest request,
            SavePreparation preparation,
            SavePublisher publisher,
            CapturedGenerationCompletion completion) {
        ChunkLoadSession chunks = new ChunkLoadSession(new MinecraftChunkLoadAccess(level));
        return new SaveCaptureOperation(
                Objects.requireNonNull(request, "request"),
                new ChunkLoadingSavePreparation(preparation, chunks),
                new BatchedWorldStateCapture(worldReader, chunks::close),
                publisher, completion, background);
    }

    private SavePreparation scopedSavePreparation(SaveRequest request) throws IOException {
        var workspace = workspaces.require(request.workspaceId());
        if (!workspace.id().equals(workspaces.active().id())) {
            throw new IOException("Save workspace is not active: " + workspace.id());
        }
        Predicate<io.github.lumi.domain.model.HistoryKey> scope = workspace::includes;
        if (request.zoneId().isPresent()) {
            if (request.kind() != CommitKind.ZONE) {
                throw new IOException("Zone Save requires zone commit kind");
            }
            ZoneScope zone = new ZoneScope(zones.require(
                    workspace.id(), request.zoneId().orElseThrow()));
            scope = key -> workspace.includes(key) && zone.includes(key);
        } else if (request.kind() == CommitKind.ZONE) {
            throw new IOException("Zone commit requires a zone ID");
        }
        return scopedSavePreparation(scope);
    }

    private SavePreparation scopedSavePreparation(
            Predicate<io.github.lumi.domain.model.HistoryKey> scope) {
        return new ScopedSavePreparation(
                new DurableSavePreparation(
                        worldReader, entityDurability, mutations, scope),
                scope);
    }

    public synchronized DimensionMutation startRestore(
            CommitId target, CommitAuthor author) throws IOException {
        return startRestore(
                target, author, activeWorkspace().settings().includeEntitiesOnRestore(),
                ignored -> { });
    }

    public synchronized DimensionMutation startRestore(
            CommitId target,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        return startRestore(
                target, author, activeWorkspace().settings().includeEntitiesOnRestore(),
                terminalObserver);
    }

    public synchronized DimensionMutation startRestore(
            CommitId target,
            CommitAuthor author,
            boolean includeEntities,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(author, "author");
        var operation = new DeferredDimensionMutation(
                () -> createRestore(target, author, includeEntities));
        operations.enqueue(
                operation, OperationPriority.NORMAL, clearingLiveHistory(terminalObserver));
        return operation;
    }

    private ReturnPointRestoreOperation createRestore(
            CommitId target, CommitAuthor author, boolean includeEntities) throws IOException {
        BranchRef expected = activeRef();
        UUID workspaceId = activeWorkspaceId();
        restores.requireTargetInWorkspace(target, workspaceId);
        UUID operationId = UUID.randomUUID();
        SaveRequest returnPoint = new SaveRequest(
                expected, author, "Return point before Restore", Instant.now(),
                workspaceId, Optional.empty(), CommitKind.HIDDEN_RETURN);
        BranchName hiddenRef = new BranchName("hidden/return/" + operationId);
        var operation = new ReturnPointRestoreOperation(
                createSave(returnPoint), saved -> returnPointRestores.prepare(
                        saved, target, hiddenRef, operationId, includeEntities));
        return operation;
    }

    public synchronized LiveActionOperation startLiveAction(
            UUID player,
            LiveActionJournal.Direction direction,
            Consumer<DimensionMutation> terminalObserver) {
        requireNoRecovery();
        var operation = new LiveActionOperation(
                liveActions, player, direction, liveWorld,
                liveEntityWorld, this::cancelLiveAction, this::publishLiveAction);
        operations.enqueue(operation, OperationPriority.URGENT, terminalObserver);
        return operation;
    }

    public LiveActionJournal liveActions() { return liveActions; }
    public MinecraftLiveBlockWorldAccess liveWorld() { return liveWorld; }
    public MinecraftLiveEntityTracker liveEntities() { return liveEntities; }
    public MinecraftCausalTickTracker causalTicks() { return causalTicks; }

    private Consumer<DimensionMutation> clearingLiveHistory(
            Consumer<DimensionMutation> observer) {
        return operation -> {
            if (operation.terminalState() == io.github.lumi.minecraft.operation.MutationTerminalState.SUCCEEDED) {
                liveEntities.clear();
                liveActions.clear();
                liveWorld.clear();
                liveEntityWorld.clear();
                causalTicks.cancelAll();
            }
            observer.accept(operation);
        };
    }

    private void cancelLiveAction(UUID action) {
        causalTicks.cancel(action);
        try {
            liveEntities.finalizeOwned(action);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(
                    "Cannot finalize owned live entities", failed);
        }
    }

    private void publishLiveAction(LiveActionJournal.Plan plan) {
        Map<SectionKey, Long> generations = new HashMap<>();
        plan.expected().keySet().forEach(position -> {
            SectionKey section = new SectionKey(
                    Math.floorDiv(position.x(), 16),
                    Math.floorDiv(position.y(), 16),
                    Math.floorDiv(position.z(), 16));
            long generation = generations.computeIfAbsent(
                    section, mutations::markTrackedSection);
            mutations.recordBlockMutation(position, generation);
        });
        Stream.concat(
                        plan.expectedEntities().values().stream(),
                        plan.replacementEntities().values().stream())
                .flatMap(Optional::stream)
                .map(this::liveEntityChunk)
                .distinct()
                .forEach(this::publishLiveEntityChunk);
    }

    private EntityChunkKey liveEntityChunk(EntityState state) {
        try {
            return liveEntityWorld.chunk(state);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private void publishLiveEntityChunk(EntityChunkKey key) {
        try {
            entityDurability.observeCurrent(key, worldReader.read(key));
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    public synchronized DimensionMutation startBranchSwitch(
            BranchName target) throws IOException {
        return startBranchSwitch(target, ignored -> { });
    }

    public synchronized DimensionMutation startBranchSwitch(
            BranchName target,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(target, "target");
        var operation = new DeferredDimensionMutation(() -> createBranchSwitch(target));
        operations.enqueue(
                operation, OperationPriority.NORMAL, clearingLiveHistory(terminalObserver));
        return operation;
    }

    private BackgroundPreparedMutation<RestoreOperation> createBranchSwitch(
            BranchName target) throws IOException {
        BranchSwitchPlan plan = branches.prepareSwitch(target, activeWorkspaceId());
        UUID operationId = UUID.randomUUID();
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var prepared = restores.prepare(plan.source(), plan.target().commit());
                return RestoreOperation.startBranchSwitch(
                        prepared, worldApply,
                        new BranchSwitchRestorePublication(branches, plan),
                        journals, operationId, restoreStateListener, plan);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
        var operation = new BackgroundPreparedMutation<>(
                preparation, () -> branches.validateSwitch(plan),
                RestoreOperation::cancelBeforeApply, true);
        return operation;
    }

    public synchronized DimensionMutation startWorkspaceSwitch(
            UUID targetWorkspace) throws IOException {
        return startWorkspaceSwitch(targetWorkspace, ignored -> { });
    }

    public synchronized DimensionMutation startWorkspaceSwitch(
            UUID targetWorkspace,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(targetWorkspace, "targetWorkspace");
        var operation = new DeferredDimensionMutation(
                () -> createWorkspaceSwitch(targetWorkspace));
        operations.enqueue(
                operation, OperationPriority.NORMAL, clearingLiveHistory(terminalObserver));
        return operation;
    }

    private BackgroundPreparedMutation<RestoreOperation> createWorkspaceSwitch(
            UUID targetWorkspace) throws IOException {
        BranchSwitchPlan branch = branches.prepareSwitch(
                WorkspaceService.mainBranch(targetWorkspace));
        WorkspaceSwitchPlan plan = workspaces.prepareSwitch(targetWorkspace, branch);
        UUID operationId = UUID.randomUUID();
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var prepared = restores.prepare(branch.source(), branch.target().commit());
                return RestoreOperation.startWorkspaceSwitch(
                        prepared, worldApply, workspaceSwitchPublication(plan),
                        journals, operationId, restoreStateListener, plan);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
        var operation = new BackgroundPreparedMutation<>(preparation, () -> {
            branches.validateSwitch(branch);
            workspaces.validateSwitch(plan);
        }, RestoreOperation::cancelBeforeApply, true);
        return operation;
    }

    public BranchRef createBranch(BranchName name) throws IOException {
        requireNoRecovery();
        return branches.create(
                WorkspaceService.branchName(activeWorkspaceId(), name),
                activeRef().commit());
    }

    public BranchName visibleBranchName(BranchName name) throws IOException {
        return WorkspaceService.visibleBranchName(
                activeWorkspaceId(), defaultWorkspaceId, name);
    }

    public WorkspaceService.Creation createWorkspace(
            String name,
            Optional<io.github.lumi.domain.model.BlockBox> bounds,
            CommitAuthor author) throws IOException {
        requireNoRecovery();
        return workspaces.create(
                UUID.randomUUID(), name, bounds,
                io.github.lumi.domain.model.WorkspaceSettings.defaults(),
                activeRef(), Objects.requireNonNull(author, "author"), Instant.now());
    }

    public io.github.lumi.domain.model.Workspace activeWorkspace() throws IOException {
        return historyViews.activeWorkspace();
    }

    public io.github.lumi.domain.model.Workspace updateWorkspaceSettings(
            io.github.lumi.domain.model.WorkspaceSettings settings) throws IOException {
        requireHistoryMetadataMutable();
        return workspaces.updateActiveSettings(settings);
    }

    public List<io.github.lumi.domain.model.Workspace> visibleWorkspaces() throws IOException {
        return historyViews.workspaces();
    }

    public io.github.lumi.domain.model.WorkingIndexPreview pendingPreview(
            int maximumSections) throws IOException {
        var workspace = activeWorkspace();
        return mutations.preview(workspace::includes, maximumSections);
    }

    public List<io.github.lumi.domain.model.HistoryEntry> history(int limit)
            throws IOException {
        return historyViews.history(limit);
    }

    public Map<UUID, List<io.github.lumi.domain.model.HistoryEntry>> zoneHistories(
            Set<UUID> zoneIds, int limit) throws IOException {
        return historyViews.zoneHistories(zoneIds, limit);
    }

    public io.github.lumi.domain.model.CommitTombstone softDelete(
            CommitId target, CommitAuthor author) throws IOException {
        requireHistoryMetadataMutable();
        return tombstones.softDelete(
                target, activeWorkspaceId(), author, Instant.now());
    }

    public List<io.github.lumi.domain.model.HistoryEntry> deletedVersions(int limit)
            throws IOException {
        return historyViews.deletedVersions(limit);
    }

    public void cleanupTombstone(CommitId target) throws IOException {
        requireHistoryMetadataMutable();
        tombstones.cleanup(target, activeWorkspaceId());
    }

    public List<BranchRef> visibleBranches() throws IOException {
        return historyViews.branches();
    }

    public CompletableFuture<ComparisonSummary> compare(
            CommitId before, CommitId after) throws IOException {
        return compare(before, after, () -> false);
    }

    public CompletableFuture<ImportExportService.PackageInspection> exportPackage(
            PackageName name, BranchRef expected) {
        return packages.exportPackage(name, expected);
    }

    public CompletableFuture<ImportExportService.PackageInspection> inspectPackage(
            PackageName name) {
        return packages.inspectPackage(name);
    }

    public CompletableFuture<ImportExportService.ImportResult> importPackage(
            PackageName name,
            ImportExportService.PackageInspection inspection,
            BranchRef expected,
            CommitAuthor author) {
        return packages.importPackage(name, inspection, expected, author);
    }

    public CompletableFuture<ComparisonSummary> compare(
            CommitId before, CommitId after, BooleanSupplier cancelled) throws IOException {
        return comparisons.compare(before, after, cancelled);
    }

    public CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            UUID zoneId,
            BooleanSupplier cancelled) throws IOException {
        return comparisons.compare(before, after, zoneId, cancelled);
    }

    public io.github.lumi.domain.model.Zone createZone(
            String name, int color, java.util.Set<SectionKey> cells) throws IOException {
        requireZoneMetadataMutable();
        java.util.Set<SectionKey> selected = java.util.Set.copyOf(cells);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Zone selection cannot be empty");
        }
        var workspace = activeWorkspace();
        if (selected.stream().anyMatch(cell -> !workspace.includes(cell))) {
            throw new IllegalArgumentException(
                    "Zone selection extends outside the active workspace");
        }
        return zones.create(UUID.randomUUID(), workspace.id(), name, color, selected);
    }

    public List<io.github.lumi.domain.model.Zone> visibleZones() throws IOException {
        return historyViews.zones();
    }

    public io.github.lumi.domain.model.Zone setZoneActorActive(
            UUID zoneId, UUID actor, boolean enabled) throws IOException {
        requireZoneMetadataMutable();
        return zones.setActorActive(activeWorkspaceId(), zoneId, actor, enabled);
    }

    public io.github.lumi.domain.model.Zone growZoneForActor(
            UUID zoneId, UUID actor, SectionKey cell) throws IOException {
        requireZoneMetadataMutable();
        return zones.growForActor(activeWorkspaceId(), zoneId, actor, cell);
    }

    public void recordCausalZoneGrowth(UUID actionId, BlockPosition position) {
        Objects.requireNonNull(position, "position");
        liveActions.owner(actionId).ifPresent(actor -> zoneGrowth.record(
                selectedWorkspaceId, actor,
                new SectionKey(
                        Math.floorDiv(position.x(), 16), Math.floorDiv(position.y(), 16),
                        Math.floorDiv(position.z(), 16))));
    }

    private RestorePublication workspaceSwitchPublication(WorkspaceSwitchPlan plan) {
        var publication = new WorkspaceSwitchRestorePublication(branches, workspaces, plan);
        return restore -> {
            publication.publish(restore);
            selectedWorkspaceId = plan.targetWorkspace();
        };
    }

    private void requireZoneMetadataMutable() {
        requireNoRecovery();
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("Zone metadata cannot change during an operation");
        }
    }

    private void requireHistoryMetadataMutable() {
        requireNoRecovery();
        if (operations.hasActiveOperation() || operations.queuedCount() > 0) {
            throw new IllegalStateException("History metadata cannot change during an operation");
        }
    }

    public CompletableFuture<PreparedMerge> prepareMerge(
            BranchName source,
            CommitAuthor author,
            String message) throws IOException {
        requireNoRecovery();
        BranchRef current = activeRef();
        requireCleanMerge();
        BranchRef sourceRef = refs.read(Objects.requireNonNull(source, "source"))
                .orElseThrow(() -> new IOException("Merge source branch is missing: " + source));
        var request = new MergeService.Request(
                current, sourceRef, Objects.requireNonNull(author, "author"), message,
                Instant.now(), activeWorkspaceId(), Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new PreparedMerge(request, merges.prepare(request));
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    public synchronized DimensionMutation startMerge(
            PreparedMerge plan,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(plan, "plan");
        var operation = new DeferredDimensionMutation(() -> createMerge(plan));
        operations.enqueue(
                operation, OperationPriority.NORMAL, clearingLiveHistory(terminalObserver));
        return operation;
    }

    private BackgroundPreparedMutation<RestoreOperation> createMerge(PreparedMerge plan)
            throws IOException {
        validateMerge(plan);
        UUID operationId = UUID.randomUUID();
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var prepared = restores.prepare(
                        plan.request().current(), plan.result().commit());
                return RestoreOperation.startMerge(
                        prepared, worldApply, new BranchRefRestorePublication(refs),
                        journals, operationId, restoreStateListener);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
        var operation = new BackgroundPreparedMutation<>(
                preparation, () -> validateMerge(plan),
                RestoreOperation::cancelBeforeApply, true);
        return operation;
    }

    private void validateMerge(PreparedMerge plan) throws IOException {
        requireCleanMerge();
        if (!activeRef().equals(plan.request().current())) {
            throw new IOException("Active branch changed after merge preview");
        }
        BranchRef source = refs.read(plan.request().source().name()).orElseThrow(
                () -> new IOException("Merge source branch no longer exists"));
        if (!source.equals(plan.request().source())) {
            throw new IOException("Merge source branch changed after preview");
        }
    }

    private void requireCleanMerge() {
        if (!mutations.snapshot().generations().isEmpty()) {
            throw new IllegalStateException("Merge requires no pending world changes");
        }
    }

    public synchronized DimensionMutation startPartialRestore(
            CommitId target,
            BlockAreaTarget area,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(author, "author");
        var operation = new DeferredDimensionMutation(
                () -> createPartialRestore(target, area, author));
        operations.enqueue(operation, OperationPriority.NORMAL, terminalObserver);
        return operation;
    }

    private ReturnPointRestoreOperation createPartialRestore(
            CommitId target, BlockAreaTarget area, CommitAuthor author) throws IOException {
        BranchRef expected = activeRef();
        UUID workspaceId = activeWorkspaceId();
        restores.requireTargetInWorkspace(target, workspaceId);
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/partial/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                expected, author,
                "Checkpoint before partial Restore", Instant.now(), workspaceId,
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        SaveCaptureOperation checkpoint = createChunkReadySave(
                checkpointRequest, savePreparation,
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { });
        var operation = new ReturnPointRestoreOperation(checkpoint, saved ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!activeRef().equals(expected)) {
                            throw new IOException("Active branch changed during partial Restore");
                        }
                        var prepared = restores.preparePartial(
                                expected, saved.commitId(), target, area.area(), area.outside());
                        return RestoreOperation.startPartial(
                                prepared, worldApply, new PendingRestorePublication(mutations),
                                journals, operationId, restoreStateListener, area,
                                saved.commitId());
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background));
        return operation;
    }

    public synchronized DimensionMutation startZoneRestore(
            CommitId target,
            UUID zoneId,
            CommitAuthor author) throws IOException {
        return startZoneRestore(target, zoneId, author, ignored -> { });
    }

    public synchronized DimensionMutation startZoneRestore(
            CommitId target,
            UUID zoneId,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(author, "author");
        var operation = new DeferredDimensionMutation(
                () -> createZoneRestore(target, zoneId, author));
        operations.enqueue(operation, OperationPriority.NORMAL, terminalObserver);
        return operation;
    }

    private ReturnPointRestoreOperation createZoneRestore(
            CommitId target, UUID zoneId, CommitAuthor author) throws IOException {
        BranchRef expected = activeRef();
        var workspace = workspaces.active();
        var zone = zones.require(workspace.id(), zoneId);
        ZoneScope scope = new ZoneScope(zone);
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/zone/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                expected, author,
                "Checkpoint before zone Restore", Instant.now(), workspace.id(),
                Optional.of(zone.id()), CommitKind.HIDDEN_RETURN);
        SavePreparation scoped = scopedSavePreparation(
                key -> workspace.includes(key) && scope.includes(key));
        SaveCaptureOperation checkpoint = createChunkReadySave(
                checkpointRequest, scoped,
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { });
        var operation = new ReturnPointRestoreOperation(checkpoint, saved ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!activeRef().equals(expected)) {
                            throw new IOException("Active branch changed during zone Restore");
                        }
                        var currentZone = zones.require(workspace.id(), zone.id());
                        if (currentZone.revision() != zone.revision()) {
                            throw new IOException("Zone changed during Restore preparation");
                        }
                        var prepared = restores.prepareZone(
                                expected, saved.commitId(), target, zone);
                        return RestoreOperation.startZone(
                                prepared, worldApply, new PendingRestorePublication(mutations),
                                journals, operationId, restoreStateListener, zone,
                                saved.commitId());
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background));
        return operation;
    }

    public synchronized DimensionMutation startQuickRollback(
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(author, "author");
        var operation = new DeferredDimensionMutation(() -> new LiveRecordedMutation(
                liveActions, author.id(), createQuickRollback(author)));
        operations.enqueue(operation, OperationPriority.URGENT, terminalObserver);
        return operation;
    }

    private ReturnPointRestoreOperation createQuickRollback(CommitAuthor author)
            throws IOException {
        BranchRef expected = activeRef();
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/rollback/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                expected, author,
                "Checkpoint before Quick Rollback", Instant.now(), activeWorkspaceId(),
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        SaveCaptureOperation checkpoint = createChunkReadySave(
                checkpointRequest, savePreparation,
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { });
        var operation = new ReturnPointRestoreOperation(checkpoint, saved ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!activeRef().equals(expected)) {
                            throw new IOException("Active branch changed during Quick Rollback");
                        }
                        var prepared = restores.prepare(
                                expected, saved.commitId(), expected.commit());
                        return RestoreOperation.startQuickRollback(
                                prepared, worldApply,
                                ignored -> mutations.clear(saved.capturedGenerations()),
                                journals, operationId, restoreStateListener,
                                saved.commitId());
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background));
        return operation;
    }

    public ServerLevel level() { return level; }
    public Path repository() { return repository; }
    public DimensionFreezeState freeze() { return freeze; }
    public DimensionOperationCoordinator operations() { return operations; }
    public MutationDurabilityTracker mutations() { return mutations; }
    public SavePreparation savePreparation() { return savePreparation; }
    public BranchRef activeRef() throws IOException {
        return historyViews.activeBranch();
    }
    public UUID defaultWorkspaceId() { return defaultWorkspaceId; }
    public UUID activeWorkspaceId() throws IOException { return selectedWorkspaceId; }
    public BlockEntityBaselineStore blockEntityBaselines() { return blockEntityBaselines; }

    private synchronized void requireNoRecovery() {
        if (pendingRecovery != null) {
            throw new IllegalStateException(
                    "Dimension recovery must Resume target or Return checkpoint first");
        }
    }

    @Override
    public void close() throws IOException {
        zoneGrowth.flush();
        try {
            operations.close();
        } finally {
            if (recoveryLease != null) {
                recoveryLease.release();
                recoveryLease = null;
            }
            causalTicks.close();
        }
        // Repository state has no open handles; background work is owned by the server session.
    }

    private record AutoVersionFingerprint(
            CommitId base,
            io.github.lumi.domain.model.WorkingIndexSnapshot generations) { }
}
