package io.github.lumi.minecraft.runtime;

import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestoreOperation;
import io.github.lumi.minecraft.operation.ReturnPointRestorePreparation;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
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
import io.github.lumi.minecraft.world.SavePreparation;
import io.github.lumi.minecraft.world.WorldStateCapture;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
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
    private final ReturnPointRestorePreparation returnPointRestores;
    private final Executor background;
    private final BranchRefRepository refs;
    private final UUID defaultWorkspaceId;
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
            UUID defaultWorkspaceId) {
        this.level = level;
        this.repository = repository;
        this.freeze = freeze;
        this.operations = operations;
        this.mutations = mutations;
        this.saves = saves;
        this.background = background;
        this.refs = refs;
        this.defaultWorkspaceId = defaultWorkspaceId;
        returnPointRestores = new ReturnPointRestorePreparation(
                restores, worldApply, refs, journals, background);
        entityDurability = new EntityChunkDurabilityGate(mutations);
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
        var journals = new OperationJournalRepository(repository);
        var origins = new OriginStore(repository);
        BranchRef main = new DimensionHistoryInitializer(objects, commits, refs)
                .initialize(UUID.randomUUID());
        UUID workspaceId = commits.read(main.commit()).workspaceId();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                objects, origins,
                new WorkingIndexRepository(repository), background);
        return new FabricDimensionRuntime(
                level, repository, freeze, new DimensionOperationCoordinator(freeze),
                mutations,
                new SaveService(objects, new MerkleTreeEditor(objects), commits, refs, journals),
                new RestoreService(objects, commits, origins),
                new MinecraftWorldStateApply(level, freeze), journals,
                background, refs, workspaceId);
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
        SaveCaptureOperation operation = createSave(request);
        operations.start(operation);
        return operation;
    }

    private SaveCaptureOperation createSave(SaveRequest request) {
        return new SaveCaptureOperation(
                Objects.requireNonNull(request, "request"), savePreparation, worldCapture,
                saves, mutations, background);
    }

    public synchronized ReturnPointRestoreOperation startRestore(
            CommitId target, CommitAuthor author) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(author, "author");
        if (operations.hasActiveOperation()) {
            throw new IllegalStateException("A dimension operation is already active");
        }
        BranchRef expected = mainRef();
        UUID operationId = UUID.randomUUID();
        SaveRequest returnPoint = new SaveRequest(
                expected, author, "Return point before Restore", Instant.now(),
                defaultWorkspaceId, Optional.empty(), CommitKind.HIDDEN_RETURN);
        BranchName hiddenRef = new BranchName("hidden/return/" + operationId);
        var operation = new ReturnPointRestoreOperation(
                createSave(returnPoint), saved -> returnPointRestores.prepare(
                        saved, target, hiddenRef, operationId));
        operations.start(operation);
        return operation;
    }

    public ServerLevel level() { return level; }
    public Path repository() { return repository; }
    public DimensionFreezeState freeze() { return freeze; }
    public DimensionOperationCoordinator operations() { return operations; }
    public MutationDurabilityTracker mutations() { return mutations; }
    public SavePreparation savePreparation() { return savePreparation; }
    public WorldStateCapture worldCapture() { return worldCapture; }
    public BranchRef mainRef() throws IOException {
        return refs.read(new BranchName("main")).orElseThrow(
                () -> new IOException("Lumi main branch is missing"));
    }
    public UUID defaultWorkspaceId() { return defaultWorkspaceId; }
    public BlockEntityBaselineStore blockEntityBaselines() { return blockEntityBaselines; }

    @Override
    public void close() {
        // Repository state has no open handles; background work is owned by the server session.
    }
}
