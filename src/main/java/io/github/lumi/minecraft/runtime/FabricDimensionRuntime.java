package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.BackgroundPreparedMutation;
import io.github.lumi.minecraft.operation.BranchSwitchRestorePublication;
import io.github.lumi.minecraft.operation.RestoreOperation;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestoreOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestorePreparation;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SaveService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.minecraft.world.BlockEntityBaselineStore;
import io.github.lumi.minecraft.world.BatchedWorldStateCapture;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.DurableSavePreparation;
import io.github.lumi.minecraft.world.EntityChunkDurabilityGate;
import io.github.lumi.minecraft.world.MinecraftBlockEntityBaselineCapture;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
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
    private final SaveService saves;
    private final RestoreService restores;
    private final MinecraftWorldStateApply worldApply;
    private final OperationJournalRepository journals;
    private final BranchService branches;
    private final ReturnPointRestorePreparation returnPointRestores;
    private final Executor background;
    private final BranchRefRepository refs;
    private final ActiveBranchRepository active;
    private final UUID defaultWorkspaceId;
    private final io.github.lumi.minecraft.operation.RestoreStateListener restoreStateListener;
    private final BlockEntityBaselineStore blockEntityBaselines = new BlockEntityBaselineStore();
    private final MinecraftBlockEntityBaselineCapture baselineCapture =
            new MinecraftBlockEntityBaselineCapture();
    private final MinecraftEntityChunkCapture entityCapture = new MinecraftEntityChunkCapture();

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
            UUID defaultWorkspaceId) {
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
        this.background = background;
        this.refs = refs;
        this.active = active;
        this.defaultWorkspaceId = defaultWorkspaceId;
        entityDurability = new EntityChunkDurabilityGate(mutations);
        restoreStateListener = new RestoreBaselineReconciler(
                entityDurability, blockEntityBaselines);
        returnPointRestores = new ReturnPointRestorePreparation(
                restores, worldApply, refs, journals,
                restoreStateListener, background);
        var worldReader = new MinecraftWorldStateReader(level);
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
        BranchRef selected = refs.read(active.read().orElseThrow().name()).orElseThrow();
        UUID workspaceId = commits.read(selected.commit()).workspaceId();
        var working = new WorkingIndexRepository(repository);
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                objects, origins, working, background);
        var branches = new BranchService(commits, refs, active, working);
        return new FabricDimensionRuntime(
                level, repository, freeze, new DimensionOperationCoordinator(
                        freeze, operation -> logTerminal(level, operation)),
                mutations,
                new SaveService(objects, new MerkleTreeEditor(objects), commits, refs, journals),
                new RestoreService(objects, commits, origins),
                new MinecraftWorldStateApply(level, freeze), journals,
                background, refs, active, branches, workspaceId);
    }

    private static void logTerminal(ServerLevel level, DimensionMutation operation) {
        String description = operation.getClass().getSimpleName()
                + " in " + level.dimension().identifier();
        switch (operation.terminalState()) {
            case SUCCEEDED -> LumiMod.LOGGER.info("Lumi operation completed: {}", description);
            case CANCELLED -> LumiMod.LOGGER.warn("Lumi operation cancelled: {}", description);
            case RETURNED -> LumiMod.LOGGER.warn(
                    "Lumi operation could not verify its target and returned safely: {}", description);
            case DEGRADED -> LumiMod.LOGGER.error(
                    "Lumi operation degraded its dimension and retained the freeze: {}", description);
            case FAILED -> operation.failure().ifPresentOrElse(
                    failure -> LumiMod.LOGGER.error("Lumi operation failed: " + description, failure),
                    () -> LumiMod.LOGGER.error("Lumi operation failed: {}", description));
        }
    }

    public void tick() throws IOException {
        operations.tick();
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
        operations.start(operation, terminalObserver);
        return operation;
    }

    public synchronized BackgroundPreparedMutation<RestoreOperation> startBranchSwitch(
            BranchName target) throws IOException {
        return startBranchSwitch(target, ignored -> { });
    }

    public synchronized BackgroundPreparedMutation<RestoreOperation> startBranchSwitch(
            BranchName target,
            Consumer<DimensionMutation> terminalObserver) throws IOException {
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
        operations.start(operation, terminalObserver);
        return operation;
    }

    public BranchRef createBranch(BranchName name) throws IOException {
        return branches.create(name, activeRef().commit());
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

    @Override
    public void close() {
        // Repository state has no open handles; background work is owned by the server session.
    }
}
