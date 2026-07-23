package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.DeferredDimensionMutation;
import io.github.lumi.minecraft.operation.GarbageCollectionOperation;
import io.github.lumi.minecraft.operation.LiveActionOperation;
import io.github.lumi.minecraft.operation.NoChangeMutation;
import io.github.lumi.minecraft.operation.BackgroundPreparedMutation;
import io.github.lumi.minecraft.operation.BranchSwitchRestorePublication;
import io.github.lumi.minecraft.operation.BranchRefRestorePublication;
import io.github.lumi.minecraft.operation.CapturedGenerationCompletion;
import io.github.lumi.minecraft.operation.PendingRestorePublication;
import io.github.lumi.minecraft.operation.PendingStatisticsOperation;
import io.github.lumi.minecraft.operation.OperationPriority;
import io.github.lumi.minecraft.operation.OperationProgress;
import io.github.lumi.minecraft.operation.RestoreOperation;
import io.github.lumi.minecraft.operation.RestorePublication;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestoreOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestorePreparation;
import io.github.lumi.minecraft.operation.WorkspaceSwitchRestorePublication;
import io.github.lumi.minecraft.operation.WorkingIndexClearPublication;
import io.github.lumi.minecraft.operation.WorkingIndexRecoveryPublication;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.ActiveWorkspace;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.PackageName;
import io.github.lumi.domain.model.PartialRestorePlan;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.VersionDisplayName;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.WorkspaceSwitchPlan;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.DimensionHistoryViewService;
import io.github.lumi.domain.service.AutoVersionService;
import io.github.lumi.domain.service.HistoryQueryService;
import io.github.lumi.domain.service.ForwardHistoryService;
import io.github.lumi.domain.service.ImportExportService;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.MergeService;
import io.github.lumi.domain.service.PreparedMerge;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.BlockOnlyRestoreService;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SavePublisher;
import io.github.lumi.domain.service.SaveService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.domain.service.RecoveryService;
import io.github.lumi.domain.service.SaveJournalRecovery;
import io.github.lumi.domain.service.PublishedApplyRecovery;
import io.github.lumi.domain.service.PendingChangeStatisticsService;
import io.github.lumi.domain.service.WorkspaceService;
import io.github.lumi.domain.service.ZoneScope;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.domain.service.TombstoneService;
import io.github.lumi.domain.service.VersionTagService;
import io.github.lumi.domain.service.VersionDisplayNameService;
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
import io.github.lumi.storage.repository.VersionTagRepository;
import io.github.lumi.storage.repository.VersionDisplayNameRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.storage.LevelResource;

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
    private final VersionTagService versionTagService;
    private final VersionDisplayNameService versionDisplayNames;
    private final DimensionHistoryViewService historyViews;
    private final PendingChangeStatisticsService pendingStatistics;
    private final CausalZoneGrowthTracker zoneGrowth;
    private final ReturnPointRestorePreparation returnPointRestores;
    private final DimensionPackageService packages;
    private final DimensionComparisonQueries comparisons;
    private final DimensionZoneOverlayQueries zoneOverlays;
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
    private PartialRestorePreview partialRestorePreview;
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
            BlockOnlyRestoreService blockOnlyRestores,
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
        versionTagService = new VersionTagService(
                commits, new VersionTagRepository(repository));
        versionDisplayNames = new VersionDisplayNameService(
                commits, new VersionDisplayNameRepository(repository));
        pendingStatistics = new PendingChangeStatisticsService(
                new WorldObjectRepository(repository), commits,
                new OriginStore(repository));
        historyViews = new DimensionHistoryViewService(
                commits,
                new HistoryQueryService(
                        commits, refs, new TombstoneRepository(repository)),
                tombstones, branches, workspaces, zones, autoVersions,
                versionDisplayNames, versionTagService);
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
        worldReader = new MinecraftWorldStateReader(level);
        entityDurability = new EntityChunkDurabilityGate(mutations);
        liveWorld = new MinecraftLiveBlockWorldAccess(level, freeze);
        liveEntityWorld = new MinecraftLiveEntityWorldAccess(level, freeze);
        liveEntities = new MinecraftLiveEntityTracker(
                liveActions, liveEntityWorld, this::publishBuilderEntityMutation);
        causalTicks = new MinecraftCausalTickTracker(
                liveActions, level, freeze, level.getBlockTicks(), level.getFluidTicks());
        restoreStateListener = new RestoreBaselineReconciler(
                entityDurability, blockEntityBaselines);
        returnPointRestores = new ReturnPointRestorePreparation(
                restores, blockOnlyRestores, worldApply, refs, journals,
                new ForwardHistoryService(commits, refs),
                restoreStateListener, background);
        packages = new DimensionPackageService(
                level.dimension().identifier().toString(), repository,
                level.getServer().getWorldPath(LevelResource.ROOT),
                background, this::activeRef);
        comparisons = new DimensionComparisonQueries(
                repository, background, zones, this::activeWorkspaceId);
        zoneOverlays = new DimensionZoneOverlayQueries(
                background, zones, this::activeWorkspaceId);
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
        savePreparation = new DurableSavePreparation(worldReader, entityDurability, mutations);
    }

    public static FabricDimensionRuntime open(
            ServerLevel level,
            DimensionRepositoryLayout layout,
            Executor background,
            Executor durabilityBackground) throws IOException {
        long started = System.nanoTime();
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
        var working = new WorkingIndexRepository(repository);
        new DimensionHistoryInitializer(objects, commits, refs, active)
                .initialize(UUID.randomUUID());
        var interrupted = journals.read();
        if (interrupted.filter(journal -> journal.kind() == OperationKind.SAVE).isPresent()) {
            new SaveJournalRecovery(commits, refs, journals, working)
                    .recover(interrupted.orElseThrow());
            interrupted = Optional.empty();
        }
        var activeWorkspaces = new ActiveWorkspaceRepository(repository);
        var workspaceService = new WorkspaceService(
                new WorkspaceRepository(repository), activeWorkspaces, commits, refs);
        UUID defaultWorkspaceId = workspaceService.defaultWorkspaceId();
        workspaceService.initializeDefault(defaultWorkspaceId);
        if (interrupted.isPresent()
                && new PublishedApplyRecovery(
                        refs, active, activeWorkspaces, working, journals)
                        .finalizeIfPublished(interrupted.orElseThrow())) {
            interrupted = Optional.empty();
        }
        UUID activeWorkspaceId = workspaceService.active().id();
        var recoveryLease = interrupted.isPresent() ? freeze.acquire() : null;
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                objects, origins, working, durabilityBackground,
                new MinecraftChunkDurabilityRetention(level));
        var branches = new BranchService(commits, refs, active, working);
        var trees = new MerkleTreeEditor(objects);
        var restoreService = new RestoreService(objects, commits, origins);
        var blockOnlyRestoreService = new BlockOnlyRestoreService(
                objects, commits, origins);
        FabricDimensionRuntime runtime = new FabricDimensionRuntime(
                level, repository, freeze, new DimensionOperationCoordinator(
                        freeze,
                        operation -> logTerminal(level, operation),
                        failure -> LumiMod.LOGGER.error(
                                "Lumi operation observer failed", failure)),
                mutations,
                new SaveService(objects, trees, commits, refs, journals,
                        new VersionTagRepository(repository)),
                restoreService, blockOnlyRestoreService,
                new MinecraftWorldStateApply(level, freeze, background), journals,
                background, refs, branches,
                new MergeService(objects, commits, origins, trees), workspaceService,
                new ZoneService(new ZoneRepository(repository)),
                defaultWorkspaceId, activeWorkspaceId,
                interrupted.orElse(null), recoveryLease);
        LumiMod.LOGGER.info(
                "Lumi opened dimension {} in {} ms",
                level.dimension().identifier(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started));
        return runtime;
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
            case RETURNED -> operation.failure().ifPresentOrElse(
                    failure -> LumiMod.LOGGER.warn(
                            "Lumi operation could not apply its target and returned safely: "
                                    + description, failure),
                    () -> LumiMod.LOGGER.warn(
                            "Lumi operation could not verify its target and returned safely: {}",
                            description));
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
                        || mutations.hasPendingChanges());
    }

    private void scheduleAutoVersion() throws IOException {
        if (!workspaces.active().settings().automaticVersionsEnabled()) return;
        long now = level.getGameTime();
        if (now < nextAutoVersionTick || autoVersionScheduled.get()) {
            return;
        }
        boolean busy = recoveryJournal().isPresent()
                || operations.hasActiveOperation() || operations.queuedCount() > 0;
        nextAutoVersionTick = now + (busy ? 200 : AUTO_VERSION_INTERVAL_TICKS);
        if (busy || !mutations.hasPendingBuilderChanges()
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
            var workspace = activeWorkspace();
            var dirty = mutations.builderSnapshot(workspace::includes);
            AutoVersionFingerprint fingerprint =
                    new AutoVersionFingerprint(expected.commit(), dirty);
            if (dirty.generations().isEmpty() || fingerprint.equals(lastAutoVersion)) {
                autoVersionScheduled.set(false);
                return;
            }
            SaveRequest request = new SaveRequest(
                    expected, AUTO_AUTHOR, "Automatic version", Instant.now(),
                    workspace.id(), Optional.empty(), CommitKind.AUTO);
            BranchName hidden = autoVersions.refName(expected.name(), UUID.randomUUID());
            SaveCaptureOperation operation = createChunkReadySave(
                    request, scopedSavePreparation(dirty.generations()::containsKey),
                    (save, captured) -> {
                        var result = saves.checkpoint(save, captured, hidden);
                        autoVersions.prune(expected.name(), 64);
                        return result;
                    },
                    ignored -> { });
            operations.enqueue(operation, OperationPriority.NORMAL, completed -> {
                operation.result().ifPresent(ignored -> lastAutoVersion = fingerprint);
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
        var progress = new AtomicReference<>(OperationProgress.indeterminate(
                "Restore: reading saved state"));
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var restore = recoveries.prepare(journal, choice,
                        value -> publishRestoreDiffProgress(progress::set, value));
                return RestoreOperation.resume(
                        restore, worldApply, publication, journals, journal,
                        restoreStateListener, progress::set);
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
                ignored -> { }, true, true, progress::get);
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
        if (choice == RecoveryChoice.RETURN_CHECKPOINT
                && journal.kind() != OperationKind.QUICK_ROLLBACK) {
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
            return journal.capturedGenerations()
                    .<RestorePublication>map(captured ->
                            new BranchSwitchRestorePublication(
                                    branches, plan, mutations, captured))
                    .orElseGet(() -> new BranchSwitchRestorePublication(
                            branches, plan));
        }
        if (journal.target().blockArea().isPresent()
                || journal.target().zoneRestore().isPresent()) {
            return new PendingRestorePublication(mutations);
        }
        if (journal.kind() == OperationKind.QUICK_ROLLBACK) {
            var action = choice == RecoveryChoice.RESUME_TARGET
                    ? WorkingIndexRecoveryPublication.TargetAction.CLEAR
                    : WorkingIndexRecoveryPublication.TargetAction.RESTORE;
            return new WorkingIndexRecoveryPublication(
                    mutations, journal.capturedGenerations(), action);
        }
        return journal.capturedGenerations()
                .<RestorePublication>map(captured ->
                        new BranchRefRestorePublication(refs, mutations, captured))
                .orElseGet(() -> new BranchRefRestorePublication(refs));
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
        if (freeze.isAuthorizedMutation()) {
            return true;
        }
        if (freeze.isFrozen()) {
            return false;
        }
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
            VersionTags tags,
            boolean amend,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        UUID workspaceId = activeWorkspaceId();
        zones.requireActorActive(workspaceId, zoneId, actor);
        return startSave(new SaveRequest(
                expected, author, message, Instant.now(), workspaceId,
                Optional.of(zoneId), CommitKind.ZONE, tags, amend), terminalObserver);
    }

    private SaveCaptureOperation createSave(SaveRequest request) throws IOException {
        Predicate<io.github.lumi.domain.model.HistoryKey> scope = saveScope(request);
        if (requiresBuilderChanges(request.kind())
                && mutations.builderSnapshot(scope).generations().isEmpty()) {
            throw new IOException("luma.save.empty_title");
        }
        return createChunkReadySave(
                request, scopedSavePreparation(scope), saves, mutations);
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
        return scopedSavePreparation(saveScope(request));
    }

    private Predicate<io.github.lumi.domain.model.HistoryKey> saveScope(
            SaveRequest request) throws IOException {
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
        return scope;
    }

    static boolean requiresBuilderChanges(CommitKind kind) {
        return kind == CommitKind.MANUAL
                || kind == CommitKind.AMEND
                || kind == CommitKind.ZONE;
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

    private DimensionMutation createRestore(
            CommitId target, CommitAuthor author, boolean includeEntities) throws IOException {
        BranchRef expected = activeRef();
        UUID workspaceId = activeWorkspaceId();
        restores.requireTargetInWorkspace(target, workspaceId);
        if (isRestoreNoOp(expected.commit(), target, mutations.hasPendingChanges())) {
            return new NoChangeMutation("luma.status.nothing_to_restore");
        }
        UUID operationId = UUID.randomUUID();
        SaveRequest returnPoint = new SaveRequest(
                expected, author, "Return point before Restore", Instant.now(),
                workspaceId, Optional.empty(), CommitKind.HIDDEN_RETURN);
        BranchName hiddenRef = new BranchName("hidden/return/" + operationId);
        SaveCaptureOperation checkpoint = createChunkReadySave(
                returnPoint, scopedSavePreparation(returnPoint),
                (request, captured) -> saves.checkpoint(
                        request, captured, hiddenRef), ignored -> { });
        return new ReturnPointRestoreOperation(checkpoint, (saved, progress) -> {
            var publication = new BranchRefRestorePublication(
                    refs, mutations, saved.capturedGenerations());
            return includeEntities
                    ? returnPointRestores.prepareCheckpoint(
                            expected, saved, target, operationId,
                            publication, progress)
                    : returnPointRestores.prepareBlockOnlyCheckpoint(
                            expected, saved, target, author,
                            returnPoint.timestamp(), operationId,
                            publication, progress);
        });
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

    private boolean cancelLiveAction(UUID action) {
        boolean changed = causalTicks.cancel(
                action, entity -> liveEntities.owns(action, entity));
        try {
            return liveEntities.finalizeOwned(action) || changed;
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
            mutations.recordBuilderBlockMutation(position, generation);
        });
        Stream.concat(
                        plan.expectedEntities().values().stream(),
                        plan.replacementEntities().values().stream())
                .flatMap(Optional::stream)
                .map(this::liveEntityChunk)
                .distinct()
                .forEach(this::publishBuilderEntityChunk);
        liveEntities.trackApplied(
                plan.actionId(), plan.direction(),
                plan.expectedEntities(), plan.replacementEntities());
        if (plan.direction() == LiveActionJournal.Direction.REDO) {
            plan.replacementEntities().forEach((id, state) -> state.ifPresent(ignored -> {
                Entity carrier = level.getEntity(id);
                if (carrier != null) {
                    causalTicks.rememberAppliedCarrier(plan.actionId(), carrier);
                }
            }));
        }
    }

    private EntityChunkKey liveEntityChunk(EntityState state) {
        try {
            return liveEntityWorld.chunk(state);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private void publishBuilderEntityMutation(
            Optional<EntityState> before,
            Optional<EntityState> after) throws IOException {
        if (freeze.isAuthorizedMutation()) {
            return;
        }
        Set<EntityChunkKey> chunks = new java.util.LinkedHashSet<>();
        before.ifPresent(state -> chunks.add(liveEntityChunk(state)));
        after.ifPresent(state -> chunks.add(liveEntityChunk(state)));
        for (EntityChunkKey key : chunks) {
            entityDurability.observeCurrent(key, worldReader.read(key));
            mutations.markBuilderMutation(key);
        }
    }

    private void publishBuilderEntityChunk(EntityChunkKey key) {
        try {
            entityDurability.observeCurrent(key, worldReader.read(key));
            mutations.markBuilderMutation(key);
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

    private ReturnPointRestoreOperation createBranchSwitch(
            BranchName target) throws IOException {
        BranchSwitchPlan plan = branches.prepareSwitch(target, activeWorkspaceId());
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/branch-switch/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                plan.source(), AUTO_AUTHOR,
                "Checkpoint before Branch Switch", Instant.now(), activeWorkspaceId(),
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        SaveCaptureOperation checkpoint = createChunkReadySave(
                checkpointRequest, scopedSavePreparation(checkpointRequest),
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { });
        return new ReturnPointRestoreOperation(checkpoint, (saved, progress) ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        branches.validateSwitch(plan);
                        var prepared = restores.prepare(
                                plan.source(), saved.commitId(), plan.target().commit(),
                                value -> publishRestoreDiffProgress(progress, value));
                        return RestoreOperation.startBranchSwitch(
                                prepared, worldApply,
                                new BranchSwitchRestorePublication(
                                        branches, plan, mutations,
                                        saved.capturedGenerations()),
                                journals, operationId, restoreStateListener, plan,
                                saved.commitId(), saved.capturedGenerations(), progress);
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background));
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
        branches.requireNoPendingChanges();
        BranchSwitchPlan branch = branches.prepareSwitch(
                WorkspaceService.mainBranch(targetWorkspace));
        WorkspaceSwitchPlan plan = workspaces.prepareSwitch(targetWorkspace, branch);
        UUID operationId = UUID.randomUUID();
        var progress = new AtomicReference<>(OperationProgress.indeterminate(
                "Restore: reading saved state"));
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var prepared = restores.prepare(
                        branch.source(), branch.target().commit(),
                        value -> publishRestoreDiffProgress(progress::set, value));
                return RestoreOperation.startWorkspaceSwitch(
                        prepared, worldApply, workspaceSwitchPublication(plan),
                        journals, operationId, restoreStateListener, plan, progress::set);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
        var operation = new BackgroundPreparedMutation<>(preparation, () -> {
            branches.requireNoPendingChanges();
            branches.validateSwitch(branch);
            workspaces.validateSwitch(plan);
        }, RestoreOperation::cancelBeforeApply, true, false, progress::get);
        return operation;
    }

    public BranchRef createBranch(BranchName name) throws IOException {
        return createBranch(name, activeRef().commit());
    }

    public BranchRef createBranch(BranchName name, CommitId startingPoint)
            throws IOException {
        requireNoRecovery();
        UUID workspaceId = activeWorkspaceId();
        restores.requireTargetInWorkspace(startingPoint, workspaceId);
        return branches.create(
                WorkspaceService.branchName(workspaceId, name), startingPoint);
    }

    public synchronized GarbageCollectionOperation startGarbageCollection(
            boolean apply, Consumer<DimensionMutation> terminalObserver)
            throws IOException {
        requireNoRecovery();
        if (garbageCollection.running()) {
            throw new IllegalStateException("Automatic Lumi cleanup is already running");
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        var operation = new GarbageCollectionOperation(apply, () -> {
            var collector = new GarbageCollector(repository);
            if (apply) {
                var result = collector.collect(Set.of(), cutoff);
                return new GarbageCollectionOperation.Counts(
                        result.deletedCommits(), result.deletedObjects());
            }
            var result = collector.inspect(Set.of(), cutoff);
            return new GarbageCollectionOperation.Counts(
                    result.commits(), result.objects());
        }, background);
        operations.enqueue(operation, OperationPriority.NORMAL, terminalObserver);
        return operation;
    }

    static boolean isRestoreNoOp(
            CommitId head, CommitId target, boolean hasPendingChanges) {
        return Objects.requireNonNull(head, "head").equals(
                Objects.requireNonNull(target, "target")) && !hasPendingChanges;
    }

    public void deleteBranch(BranchName name) throws IOException {
        requireHistoryMetadataMutable();
        branches.delete(name, activeWorkspaceId());
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

    public long pendingRevision() {
        return mutations.pendingRevision();
    }

    public synchronized PendingStatisticsOperation startPendingStatistics(
            BranchRef expected,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        if (!activeRef().equals(Objects.requireNonNull(expected, "expected"))) {
            throw new IOException(
                    "History changed before pending statistics started");
        }
        var workspace = activeWorkspace();
        WorkingIndexSnapshot boundary = pendingBoundary(workspace);
        var durability = mutations.durabilityBoundary(boundary);
        ChunkLoadSession chunks = new ChunkLoadSession(
                new MinecraftChunkLoadAccess(level));
        chunks.retain(boundary.generations().keySet());
        var operation = new PendingStatisticsOperation(
                expected.commit(), boundary, historyViews.zones(), worldReader,
                () -> pendingBoundary(workspace),
                () -> mutations.isDurable(durability), pendingStatistics,
                background, chunks);
        operations.enqueue(
                operation, OperationPriority.NORMAL,
                Objects.requireNonNull(terminalObserver, "terminalObserver"));
        return operation;
    }

    private WorkingIndexSnapshot pendingBoundary(
            io.github.lumi.domain.model.Workspace workspace) {
        return mutations.builderSnapshot(workspace::includes);
    }

    public List<io.github.lumi.domain.model.HistoryEntry> history(int limit)
            throws IOException {
        return historyViews.history(limit);
    }

    public CompletableFuture<io.github.lumi.domain.model.HistoryPage> historyPage(
            BranchName branch, int offset, int limit, String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return historyViews.historyPage(
                        branch, offset, limit, query);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    public CompletableFuture<io.github.lumi.domain.model.ZoneHistoryPage> zoneHistoryPage(
            BranchName branch,
            UUID zoneId,
            int offset,
            int limit,
            String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return historyViews.zoneHistory(
                        branch, zoneId, offset, limit, query);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    public VersionTags versionTags(CommitId target) {
        try {
            return versionTagService.read(target);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn(
                    "Cannot read non-authoritative Lumi tags for commit {}",
                    target, failed);
            return VersionTags.empty();
        }
    }

    public void updateVersionTags(CommitId target, VersionTags tags)
            throws IOException {
        requireHistoryMetadataMutable();
        versionTagService.replace(target, activeWorkspaceId(), tags);
    }

    public String versionDisplayName(CommitId target, String commitMessage) {
        try {
            return versionDisplayNames.read(target, commitMessage);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn(
                    "Cannot read non-authoritative Lumi name for commit {}",
                    target, failed);
            return commitMessage;
        }
    }

    public void renameVersion(CommitId target, VersionDisplayName replacement)
            throws IOException {
        requireHistoryMetadataMutable();
        versionDisplayNames.replace(target, activeWorkspaceId(), replacement);
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

    public void restoreTombstone(CommitId target) throws IOException {
        requireHistoryMetadataMutable();
        tombstones.restore(target, activeWorkspaceId());
    }

    public List<BranchRef> visibleBranches() throws IOException {
        return historyViews.branches();
    }

    public CompletableFuture<ComparisonSummary> compare(
            CommitId before, CommitId after) throws IOException {
        return compare(before, after, () -> false);
    }

    public synchronized CompletableFuture<PartialRestorePreview> planPartialRestore(
            CommitId target, BlockAreaTarget area) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(area, "area");
        requireCleanPartialPreview();
        BranchRef expected = activeRef();
        UUID workspaceId = activeWorkspaceId();
        restores.requireTargetInWorkspace(target, workspaceId);
        UUID token = UUID.randomUUID();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return restores.planPartial(expected, target, area);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background).thenApply(plan -> acceptPartialRestorePreview(
                token, workspaceId, expected, plan));
    }

    private synchronized PartialRestorePreview acceptPartialRestorePreview(
            UUID token,
            UUID workspaceId,
            BranchRef expected,
            PartialRestorePlan plan) {
        try {
            requireNoRecovery();
            requireCleanPartialPreview();
            if (!activeRef().equals(expected)
                    || !activeWorkspaceId().equals(workspaceId)) {
                throw new IOException(
                        "History changed during partial Restore preview");
            }
            partialRestorePreview = new PartialRestorePreview(
                    token, workspaceId, expected, plan);
            return partialRestorePreview;
        } catch (IOException | IllegalStateException failed) {
            throw new CompletionException(failed);
        }
    }

    public CompletableFuture<ImportExportService.PackageInspection> exportPackage(
            PackageName name, BranchRef expected, boolean includePreview) {
        return packages.exportPackage(name, expected, includePreview);
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
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) throws IOException {
        return comparisons.compare(before, after, cancelled, batches);
    }

    public CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            UUID zoneId,
            BooleanSupplier cancelled) throws IOException {
        return comparisons.compare(before, after, zoneId, cancelled);
    }

    public CompletableFuture<ComparisonSummary> compare(
            CommitId before,
            CommitId after,
            UUID zoneId,
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) throws IOException {
        return comparisons.compare(before, after, zoneId, cancelled, batches);
    }

    public CompletableFuture<io.github.lumi.domain.model.ZoneShellSnapshot>
            zoneOverlay(UUID actor, SectionKey center, boolean all) {
        return zoneOverlays.query(actor, center, all);
    }

    public io.github.lumi.domain.model.Zone createZone(
            String name, UUID actor) throws IOException {
        requireZoneMetadataMutable();
        var workspace = activeWorkspace();
        return zones.createActive(
                UUID.randomUUID(), workspace.id(), name,
                Objects.requireNonNull(actor, "actor"));
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
        var progress = new AtomicReference<>(OperationProgress.indeterminate(
                "Restore: reading saved state"));
        CompletableFuture<RestoreOperation> preparation = CompletableFuture.supplyAsync(() -> {
            try {
                var prepared = restores.prepare(
                        plan.request().current(), plan.result().commit(),
                        value -> publishRestoreDiffProgress(progress::set, value));
                return RestoreOperation.startMerge(
                        prepared, worldApply, new BranchRefRestorePublication(refs),
                        journals, operationId, restoreStateListener, progress::set);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
        var operation = new BackgroundPreparedMutation<>(
                preparation, () -> validateMerge(plan),
                RestoreOperation::cancelBeforeApply, true, false, progress::get);
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
        var operation = new ReturnPointRestoreOperation(checkpoint, (saved, progress) ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!activeRef().equals(expected)) {
                            throw new IOException("Active branch changed during partial Restore");
                        }
                        var prepared = restores.preparePartial(
                                expected, saved.commitId(), target, area.area(), area.outside(),
                                value -> publishRestoreDiffProgress(progress, value));
                        return RestoreOperation.startPartial(
                                prepared, worldApply, new PendingRestorePublication(mutations),
                                journals, operationId, restoreStateListener, area,
                                saved.commitId(), progress);
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
        var operation = new ReturnPointRestoreOperation(checkpoint, (saved, progress) ->
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
                                expected, saved.commitId(), target, zone,
                                value -> publishRestoreDiffProgress(progress, value));
                        return RestoreOperation.startZone(
                                prepared, worldApply, new PendingRestorePublication(mutations),
                                journals, operationId, restoreStateListener, zone,
                                saved.commitId(), progress);
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background));
        return operation;
    }

    public synchronized DimensionMutation startQuickRollback(
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        return startQuickRollback(Optional.empty(), author, terminalObserver);
    }

    public io.github.lumi.domain.model.Zone editActiveZone(
            UUID actor, BlockBox area, boolean add) throws IOException {
        requireZoneMetadataMutable();
        var workspace = activeWorkspace();
        java.util.Set<SectionKey> selected = area.sectionCells(65_536);
        if (selected.stream().anyMatch(cell -> !workspace.includes(cell))) {
            throw new IllegalArgumentException(
                    "Zone edit extends outside the active workspace");
        }
        return zones.updateActiveForActor(
                workspace.id(), Objects.requireNonNull(actor, "actor"),
                selected, add);
    }

    public void deleteZone(UUID zoneId, long expectedRevision)
            throws IOException {
        requireZoneMetadataMutable();
        zones.delete(activeWorkspaceId(), zoneId, expectedRevision);
    }

    public synchronized DimensionMutation startQuickRollback(
            Optional<BlockBox> selection,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(author, "author");
        var operation = new DeferredDimensionMutation(true, () -> {
            var workspace = activeWorkspace();
            WorkingIndexSnapshot builder = mutations.builderSnapshot(key ->
                    workspace.includes(key) && inside(selection, key));
            return builder.generations().isEmpty()
                    ? new NoChangeMutation("luma.status.nothing_to_restore")
                    : new LiveRecordedMutation(
                            liveActions, author.id(), action ->
                            createQuickRollback(author, builder, selection, action));
        });
        operations.enqueue(operation, OperationPriority.URGENT, terminalObserver);
        return operation;
    }

    public synchronized DimensionMutation startPlannedPartialRestore(
            UUID token,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(author, "author");
        PartialRestorePreview preview = partialRestorePreview;
        if (preview == null || !preview.token().equals(token)) {
            throw new IllegalStateException(
                    "Partial Restore preview is missing; preview again");
        }
        var operation = new DeferredDimensionMutation(
                () -> createPlannedPartialRestore(token, author));
        operations.enqueue(operation, OperationPriority.NORMAL, terminalObserver);
        return operation;
    }

    private synchronized ReturnPointRestoreOperation createPlannedPartialRestore(
            UUID token, CommitAuthor author) throws IOException {
        requireNoRecovery();
        PartialRestorePreview preview = partialRestorePreview;
        if (preview == null || !preview.token().equals(token)
                || !activeRef().equals(preview.expectedRef())
                || !activeWorkspaceId().equals(preview.workspaceId())) {
            throw new IOException("Partial Restore preview is stale; preview again");
        }
        requireCleanPartialPreview();
        partialRestorePreview = null;
        return createPartialRestore(
                preview.plan().target(), preview.plan().area(), author);
    }

    private void requireCleanPartialPreview() {
        if (!mutations.snapshot().generations().isEmpty()) {
            throw new IllegalStateException(
                    "Save or roll back current work before partial Restore preview");
        }
    }

    private ReturnPointRestoreOperation createQuickRollback(
            CommitAuthor author, WorkingIndexSnapshot builder,
            Optional<BlockBox> selection, UUID liveAction)
            throws IOException {
        BranchRef expected = activeRef();
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/rollback/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                expected, author,
                "Checkpoint before Quick Rollback", Instant.now(), activeWorkspaceId(),
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        SaveCaptureOperation checkpoint = createChunkReadySave(
                checkpointRequest,
                scopedSavePreparation(builder.generations()::containsKey),
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { });
        var operation = new ReturnPointRestoreOperation(checkpoint, (saved, progress) ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!activeRef().equals(expected)) {
                            throw new IOException("Active branch changed during Quick Rollback");
                        }
                        var full = restores.prepare(
                                expected, saved.commitId(), expected.commit(),
                                value -> publishRestoreDiffProgress(progress, value)).materialize();
                        var prepared = selection.isEmpty() ? full
                                : restores.preparePartial(
                                        expected, saved.commitId(), expected.commit(),
                                        selection.orElseThrow(), false,
                                        value -> publishRestoreDiffProgress(progress, value))
                                        .materialize();
                        liveWorld.prepareRestore(
                                prepared.sections(), prepared.returnSections());
                        var targetEntities = liveEntityWorld.prepareRestore(
                                prepared.entities());
                        var returnEntities = liveEntityWorld.prepareRestore(
                                prepared.returnEntities());
                        liveActions.recordRestore(liveAction, new PreparedRestore(
                                prepared.expectedRef(), prepared.targetCommit(),
                                prepared.sections(), targetEntities,
                                prepared.returnSections(), returnEntities,
                                prepared.playerSpawns(), prepared.returnPlayerSpawns(),
                                prepared.restorePlayerSpawns()));
                        return RestoreOperation.startQuickRollback(
                                prepared, worldApply, new WorkingIndexClearPublication(
                                        mutations, clearableQuickRollbackKeys(
                                                saved.capturedGenerations(),
                                                full, selection)),
                                journals, operationId, restoreStateListener,
                                saved.commitId(), saved.capturedGenerations(), progress);
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background));
        return operation;
    }

    private static void publishRestoreDiffProgress(
            Consumer<OperationProgress> target,
            RestoreService.PreparationProgress progress) {
        target.accept(new OperationProgress(
                "Restore: comparing region " + progress.regionIndex()
                        + "/" + progress.regionTotal(),
                progress.chunkCompleted(), progress.chunkTotal()));
    }

    static boolean inside(Optional<BlockBox> selection, HistoryKey key) {
        if (selection.isEmpty()) {
            return true;
        }
        BlockBox area = selection.orElseThrow();
        return key instanceof SectionKey section && area.intersects(section);
    }

    private static WorkingIndexSnapshot clearableQuickRollbackKeys(
            WorkingIndexSnapshot captured,
            PreparedRestore full,
            Optional<BlockBox> selection) {
        if (selection.isEmpty()) {
            return captured;
        }
        Map<HistoryKey, Long> clearable = new HashMap<>();
        captured.generations().forEach((key, generation) -> {
            if (!(key instanceof SectionKey section)) {
                return;
            }
            SectionBlob before = full.returnSections().get(section);
            SectionBlob after = full.sections().get(section);
            if (before == null || after == null
                    || changesOnlyInside(selection.orElseThrow(), section, before, after)) {
                clearable.put(key, generation);
            }
        });
        return new WorkingIndexSnapshot(clearable);
    }

    static boolean changesOnlyInside(
            BlockBox area, SectionKey section,
            SectionBlob before, SectionBlob after) {
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            if (before.blockStates().get(index).equals(after.blockStates().get(index))
                    && Objects.equals(before.blockEntities().get(index),
                            after.blockEntities().get(index))) {
                continue;
            }
            int x = section.chunkX() * 16 + (index & 15);
            int z = section.chunkZ() * 16 + (index >>> 4 & 15);
            int y = section.sectionY() * 16 + (index >>> 8 & 15);
            if (!area.contains(x, y, z)) {
                return false;
            }
        }
        return true;
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
