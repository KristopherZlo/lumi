package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;

/** Complete Minecraft WorldStateApply adapter: off-thread decode plus bounded mutation. */
public final class MinecraftWorldStateApply implements WorldStateApply {
    private final MinecraftRestorePreparation preparation;
    private final PreparedWorldAccess world;
    private final ServerLevel level;
    private final Executor background;

    public MinecraftWorldStateApply(ServerLevel level, DimensionFreezeState freeze) {
        this(level, freeze, Runnable::run);
    }

    public MinecraftWorldStateApply(
            ServerLevel level, DimensionFreezeState freeze, Executor background) {
        this.level = Objects.requireNonNull(level, "level");
        this.background = Objects.requireNonNull(background, "background");
        preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(
                        level.registryAccess().lookupOrThrow(Registries.BLOCK)),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));
        world = new MinecraftPreparedWorldAccess(level, freeze);
    }

    @Override
    public PreparedState prepare(State target) throws IOException {
        return preparation.prepare(target);
    }

    @Override
    public PreparedState prepare(State target, LongConsumer progress) throws IOException {
        return preparation.prepare(target, progress);
    }

    @Override
    public PreparedState prepare(
            State target, State base, LongConsumer progress) throws IOException {
        PreparedMinecraftPlanState plan = preparation.preflight(target, base, progress);
        return plan.withSectionKeys(prioritize(plan.sectionKeys(), playerChunks()));
    }

    @Override
    public PreparedStates prepareBoth(
            State target,
            State returnPoint,
            LongConsumer targetProgress,
            LongConsumer returnProgress) throws IOException {
        targetProgress.accept(0);
        PreparedMinecraftPlanState plan = preparation.preflight(
                target, returnPoint, targetProgress);
        plan = plan.withSectionKeys(prioritize(plan.sectionKeys(), playerChunks()));
        returnProgress.accept(0);
        returnProgress.accept((long) returnPoint.sections().size()
                + returnPoint.entities().size());
        return new PreparedStates(plan, plan.reversed());
    }

    @Override
    public ApplySession begin(PreparedState target) {
        if (target instanceof PreparedMinecraftPlanState plan) {
            return new StreamingPreparedWorldMutationSession(
                    plan, preparation, world, background,
                    () -> new ChunkLoadSession(new MinecraftChunkLoadAccess(level)));
        }
        if (!(target instanceof PreparedMinecraftState minecraft)) {
            throw new IllegalArgumentException("Restore state was not prepared for Minecraft");
        }
        return new PreparedWorldMutationSession(
                minecraft, world, System::nanoTime,
                new ChunkLoadSession(new MinecraftChunkLoadAccess(level)));
    }

    private List<ChunkCoordinate> playerChunks() {
        if (level.getServer().isSameThread()) {
            return snapshotPlayerChunks();
        }
        CompletableFuture<List<ChunkCoordinate>> snapshot = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                snapshot.complete(snapshotPlayerChunks());
            } catch (RuntimeException failed) {
                snapshot.completeExceptionally(failed);
            }
        });
        return snapshot.join();
    }

    private List<ChunkCoordinate> snapshotPlayerChunks() {
        return level.players().stream()
                .map(player -> new ChunkCoordinate(
                        player.chunkPosition().x, player.chunkPosition().z))
                .toList();
    }

    static List<io.github.lumi.domain.model.SectionKey> prioritize(
            List<io.github.lumi.domain.model.SectionKey> sections,
            List<ChunkCoordinate> players) {
        Comparator<io.github.lumi.domain.model.SectionKey> order =
                Comparator.comparingLong(key -> distanceSquared(key, players));
        return sections.stream().sorted(order
                .thenComparingInt(io.github.lumi.domain.model.SectionKey::chunkX)
                .thenComparingInt(io.github.lumi.domain.model.SectionKey::chunkZ)
                .thenComparingInt(io.github.lumi.domain.model.SectionKey::sectionY))
                .toList();
    }

    private static long distanceSquared(
            io.github.lumi.domain.model.SectionKey key,
            List<ChunkCoordinate> players) {
        long nearest = Long.MAX_VALUE;
        for (ChunkCoordinate player : players) {
            long x = (long) key.chunkX() - player.x();
            long z = (long) key.chunkZ() - player.z();
            nearest = Math.min(nearest, x * x + z * z);
        }
        return nearest;
    }
}
