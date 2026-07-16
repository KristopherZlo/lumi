package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.LiveActionOperation;
import io.github.lumi.minecraft.operation.BackgroundPreparedMutation;
import io.github.lumi.minecraft.operation.BranchSwitchRestorePublication;
import io.github.lumi.minecraft.operation.BranchRefRestorePublication;
import io.github.lumi.minecraft.operation.PendingRestorePublication;
import io.github.lumi.minecraft.operation.RestoreOperation;
import io.github.lumi.minecraft.operation.RestorePublication;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestoreOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestorePreparation;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.MergeService;
import io.github.lumi.domain.service.PreparedMerge;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SaveService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.domain.service.RecoveryService;
import io.github.lumi.domain.service.SaveJournalRecovery;
import io.github.lumi.minecraft.world.BlockEntityBaselineStore;
import io.github.lumi.minecraft.world.BatchedWorldStateCapture;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.DurableSavePreparation;
import io.github.lumi.minecraft.world.EntityChunkDurabilityGate;
import io.github.lumi.minecraft.world.MinecraftBlockEntityBaselineCapture;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import io.github.lumi.minecraft.world.MinecraftLiveBlockWorldAccess;
import io.github.lumi.minecraft.world.MinecraftLiveEntityWorldAccess;
import io.github.lumi.minecraft.world.MinecraftWorldStateReader;
import io.github.lumi.minecraft.world.MinecraftWorldStateApply;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.minecraft.world.RestoreBaselineReconciler;
import io.github.lumi.minecraft.world.SavePreparation;
import io.github.lumi.minecraft.world.WorldStateCapture;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;

/** Server-authoritative Lumi state owned by one loaded Minecraft dimension. */
public final class FabricDimensionRuntime implements AutoCloseable {
    private final ServerLevel level;
    private final Path repository;
    private final DimensionFreezeState freeze;
    private final DimensionOperationCoordinator operations;
    private final MutationDurabilityTracker mutations;
    private final EntityChunkDurabilityGate entityDurability;
    private final SavePreparation savePreparation;
    private final WorldStateCapture worldCapture;
    private final MinecraftWorldStateReader worldReader;
    private final SaveService saves;
    private final RestoreService restores;
    private final MinecraftWorldStateApply worldApply;
    private final OperationJournalRepository journals;
    private final BranchService branches;
    private final MergeService merges;
    private final RecoveryService recoveries;
    private final ReturnPointRestorePreparation returnPointRestores;
    private final Executor background;
    private final BranchRefRepository refs;
    private final ActiveBranchRepository active;
    private final UUID defaultWorkspaceId;
    private final LiveActionJournal liveActions = new LiveActionJournal();
    private final MinecraftLiveBlockWorldAccess liveWorld;
    private final MinecraftLiveEntityWorldAccess liveEntityWorld;
    private final MinecraftLiveEntityTracker liveEntities;
    private final MinecraftCausalTickTracker causalTicks;
    private final io.github.lumi.minecraft.operation.RestoreStateListener restoreStateListener;
    private final BlockEntityBaselineStore blockEntityBaselines = new BlockEntityBaselineStore();
    private final MinecraftBlockEntityBaselineCapture baselineCapture =
            new MinecraftBlockEntityBaselineCapture();
    private final MinecraftEntityChunkCapture entityCapture = new MinecraftEntityChunkCapture();
    private OperationJournal pendingRecovery;
    private io.github.lumi.minecraft.world.DimensionFreeze.Lease recoveryLease;

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
            ActiveBranchRepository active,
            BranchService branches,
            MergeService merges,
            UUID defaultWorkspaceId,
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
        recoveries = new RecoveryService(restores);
        this.background = background;
        this.refs = refs;
        this.active = active;
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
        worldReader = new MinecraftWorldStateReader(level);
        savePreparation = new DurableSavePreparation(worldReader, entityDurability, mutations);
        worldCapture = new BatchedWorldStateCapture(worldReader);
    }

    public static FabricDimensionRuntime open(
            ServerLevel level, DimensionRepositoryLayout layout, Executor background) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(background, "background");
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
        var recoveryLease = interrupted.isPresent() ? freeze.acquire() : null;
        BranchRef selected = refs.read(active.read().orElseThrow().name()).orElseThrow();
        UUID workspaceId = commits.read(selected.commit()).workspaceId();
        var working = new WorkingIndexRepository(repository);
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                objects, origins, working, background);
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
                background, refs, active, branches,
                new MergeService(objects, commits, origins, trees), workspaceId,
                interrupted.orElse(null), recoveryLease);
    }

    private static void logTerminal(ServerLevel level, DimensionMutation operation) {
        String description = operation.getClass().getSimpleName()
                + " in " + level.dimension().identifier();
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
        operations.tick();
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
            return new BranchSwitchRestorePublication(branches, plan);
        }
        if (journal.target().blockArea().isPresent()) {
            return new PendingRestorePublication(mutations);
        }
        if (journal.kind() == OperationKind.QUICK_ROLLBACK) {
            return ignored -> mutations.clear(mutations.snapshot());
        }
        return new BranchRefRestorePublication(refs);
    }

    public void chunkLoaded(LevelChunk chunk) throws IOException {
        baselineCapture.remember(level, chunk, mutations, blockEntityBaselines);
    }

    public void chunkUnloaded(LevelChunk chunk) {
        blockEntityBaselines.discardChunk(chunk.getPos().x, chunk.getPos().z);
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

    public synchronized SaveCaptureOperation startSave(SaveRequest request) {
        return startSave(request, ignored -> { });
    }

    public synchronized SaveCaptureOperation startSave(
            SaveRequest request, Consumer<DimensionMutation> terminalObserver) {
        requireNoRecovery();
        SaveCaptureOperation operation = createSave(request);
        operations.start(operation, terminalObserver);
        return operation;
    }

    private SaveCaptureOperation createSave(SaveRequest request) {
        return new SaveCaptureOperation(
                Objects.requireNonNull(request, "request"), savePreparation, worldCapture,
                saves, mutations, background);
    }

    public synchronized ReturnPointRestoreOperation startRestore(
            CommitId target, CommitAuthor author) throws IOException {
        return startRestore(target, author, ignored -> { });
    }

    public synchronized ReturnPointRestoreOperation startRestore(
            CommitId target,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(author, "author");
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
        BranchRef expected = activeRef();
        UUID operationId = UUID.randomUUID();
        SaveRequest returnPoint = new SaveRequest(
                expected, author, "Return point before Restore", Instant.now(),
                defaultWorkspaceId, Optional.empty(), CommitKind.HIDDEN_RETURN);
        BranchName hiddenRef = new BranchName("hidden/return/" + operationId);
        var operation = new ReturnPointRestoreOperation(
                createSave(returnPoint), saved -> returnPointRestores.prepare(
                        saved, target, hiddenRef, operationId));
        operations.start(operation, clearingLiveHistory(terminalObserver));
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
        operations.start(operation, terminalObserver);
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
        plan.expected().keySet().stream()
                .map(position -> new SectionKey(
                        Math.floorDiv(position.x(), 16),
                        Math.floorDiv(position.y(), 16),
                        Math.floorDiv(position.z(), 16)))
                .distinct()
                .forEach(mutations::markTrackedSection);
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

    public synchronized BackgroundPreparedMutation<RestoreOperation> startBranchSwitch(
            BranchName target) throws IOException {
        return startBranchSwitch(target, ignored -> { });
    }

    public synchronized BackgroundPreparedMutation<RestoreOperation> startBranchSwitch(
            BranchName target,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
        BranchSwitchPlan plan = branches.prepareSwitch(target);
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
        operations.start(operation, clearingLiveHistory(terminalObserver));
        return operation;
    }

    public BranchRef createBranch(BranchName name) throws IOException {
        requireNoRecovery();
        return branches.create(name, activeRef().commit());
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
                Instant.now(), defaultWorkspaceId, Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new PreparedMerge(request, merges.prepare(request));
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    public synchronized BackgroundPreparedMutation<RestoreOperation> startMerge(
            PreparedMerge plan,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        Objects.requireNonNull(plan, "plan");
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
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
        operations.start(operation, clearingLiveHistory(terminalObserver));
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

    public synchronized ReturnPointRestoreOperation startPartialRestore(
            CommitId target,
            BlockAreaTarget area,
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
        BranchRef expected = activeRef();
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/partial/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                expected, Objects.requireNonNull(author, "author"),
                "Checkpoint before partial Restore", Instant.now(), defaultWorkspaceId,
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        SaveCaptureOperation checkpoint = new SaveCaptureOperation(
                checkpointRequest, savePreparation, worldCapture,
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { }, background);
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
        operations.start(operation, terminalObserver);
        return operation;
    }

    public synchronized ReturnPointRestoreOperation startQuickRollback(
            CommitAuthor author,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
        requireNoRecovery();
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
        BranchRef expected = activeRef();
        UUID operationId = UUID.randomUUID();
        BranchName hidden = new BranchName("hidden/rollback/" + operationId);
        SaveRequest checkpointRequest = new SaveRequest(
                expected, Objects.requireNonNull(author, "author"),
                "Checkpoint before Quick Rollback", Instant.now(), defaultWorkspaceId,
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        SaveCaptureOperation checkpoint = new SaveCaptureOperation(
                checkpointRequest, savePreparation, worldCapture,
                (request, captured) -> saves.checkpoint(request, captured, hidden),
                ignored -> { }, background);
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
        operations.start(new LiveRecordedMutation(
                liveActions, author.id(), operation), terminalObserver);
        return operation;
    }

    public ServerLevel level() { return level; }
    public Path repository() { return repository; }
    public DimensionFreezeState freeze() { return freeze; }
    public DimensionOperationCoordinator operations() { return operations; }
    public MutationDurabilityTracker mutations() { return mutations; }
    public SavePreparation savePreparation() { return savePreparation; }
    public WorldStateCapture worldCapture() { return worldCapture; }
    public BranchRef activeRef() throws IOException {
        BranchName name = active.read().orElseThrow(
                () -> new IOException("Active Lumi branch is missing")).name();
        return refs.read(name).orElseThrow(
                () -> new IOException("Active Lumi branch ref is missing: " + name));
    }
    public UUID defaultWorkspaceId() { return defaultWorkspaceId; }
    public BlockEntityBaselineStore blockEntityBaselines() { return blockEntityBaselines; }

    private synchronized void requireNoRecovery() {
        if (pendingRecovery != null) {
            throw new IllegalStateException(
                    "Dimension recovery must Resume target or Return checkpoint first");
        }
    }

    @Override
    public void close() {
        if (recoveryLease != null) {
            recoveryLease.release();
            recoveryLease = null;
        }
        causalTicks.close();
        // Repository state has no open handles; background work is owned by the server session.
    }
}
