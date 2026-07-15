package io.github.lumi.minecraft.runtime;

import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import io.github.lumi.minecraft.world.BlockEntityBaselineStore;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.MinecraftBlockEntityBaselineCapture;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Server-authoritative Lumi state owned by one loaded Minecraft dimension. */
public final class FabricDimensionRuntime implements AutoCloseable {
    private final ServerLevel level;
    private final Path repository;
    private final DimensionFreezeState freeze;
    private final DimensionOperationCoordinator operations;
    private final MutationDurabilityTracker mutations;
    private final BlockEntityBaselineStore blockEntityBaselines = new BlockEntityBaselineStore();
    private final MinecraftBlockEntityBaselineCapture baselineCapture =
            new MinecraftBlockEntityBaselineCapture();

    private FabricDimensionRuntime(
            ServerLevel level,
            Path repository,
            DimensionFreezeState freeze,
            DimensionOperationCoordinator operations,
            MutationDurabilityTracker mutations) {
        this.level = level;
        this.repository = repository;
        this.freeze = freeze;
        this.operations = operations;
        this.mutations = mutations;
    }

    public static FabricDimensionRuntime open(
            ServerLevel level, DimensionRepositoryLayout layout, Executor background) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(background, "background");
        Path repository = layout.resolve(level.dimension().identifier().toString());
        DimensionFreezeState freeze = new DimensionFreezeState();
        return new FabricDimensionRuntime(
                level, repository, freeze, new DimensionOperationCoordinator(freeze),
                MutationDurabilityTracker.open(
                        new WorldObjectRepository(repository), new OriginStore(repository),
                        new WorkingIndexRepository(repository), background));
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

    public ServerLevel level() { return level; }
    public Path repository() { return repository; }
    public DimensionFreezeState freeze() { return freeze; }
    public DimensionOperationCoordinator operations() { return operations; }
    public MutationDurabilityTracker mutations() { return mutations; }
    public BlockEntityBaselineStore blockEntityBaselines() { return blockEntityBaselines; }

    @Override
    public void close() {
        // Repository state has no open handles; background work is owned by the server session.
    }
}
