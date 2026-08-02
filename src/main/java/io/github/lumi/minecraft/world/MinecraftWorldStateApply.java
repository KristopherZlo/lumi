package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Complete Minecraft WorldStateApply adapter: off-thread decode plus bounded mutation. */
public final class MinecraftWorldStateApply implements WorldStateApply {
    private final MinecraftRestorePreparation preparation;
    private final PreparedWorldAccess world;
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final Executor background;

    public MinecraftWorldStateApply(ServerLevel level, DimensionFreezeState freeze) {
        this(level, freeze, Runnable::run);
    }

    public MinecraftWorldStateApply(
            ServerLevel level, DimensionFreezeState freeze, Executor background) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.background = Objects.requireNonNull(background, "background");
        preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(
                        level.registryAccess().lookupOrThrow(Registries.BLOCK)),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));
        world = new MinecraftPreparedWorldAccess(level, freeze, background);
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
        return preparation.preflight(target, base, progress);
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
        returnProgress.accept(0);
        returnProgress.accept((long) returnPoint.sections().size()
                + returnPoint.entities().size());
        return new PreparedStates(plan, plan.reversed());
    }

    @Override
    public PreparedState replacePreparedSource(
            PreparedState prepared, State source) throws IOException {
        return prepared instanceof PreparedMinecraftPlanState plan
                ? plan.withSource(source)
                : WorldStateApply.super.replacePreparedSource(prepared, source);
    }

    @Override
    public ApplySession begin(PreparedState target) {
        if (target instanceof PreparedMinecraftPlanState plan) {
            return new StreamingPreparedWorldMutationSession(
                    prioritize(plan), preparation, world, background,
                    this::chunkLoads, chunk -> level.getChunkSource().getChunkNow(
                            chunk.x(), chunk.z()) != null);
        }
        if (!(target instanceof PreparedMinecraftState minecraft)) {
            throw new IllegalArgumentException("Restore state was not prepared for Minecraft");
        }
        return new PreparedWorldMutationSession(
                minecraft, world, System::nanoTime,
                chunkLoads(minecraft.entityKeys().isEmpty()
                        ? ChunkLoadAccess.Readiness.TERRAIN_WITH_NEIGHBORS
                        : ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES));
    }

    private ChunkLoadSession chunkLoads(ChunkLoadAccess.Readiness readiness) {
        return new ChunkLoadSession(new MinecraftChunkLoadAccess(level, freeze, readiness));
    }

    private PreparedMinecraftPlanState prioritize(PreparedMinecraftPlanState plan) {
        PrioritySnapshot priority = prioritySnapshot(plan.sectionKeys());
        return plan.withOrder(
                prioritize(plan.sectionKeys(), priority.players(), priority.resident()),
                prioritizeEntities(plan.entityKeys(), priority.players()));
    }

    private PrioritySnapshot prioritySnapshot(
            List<SectionKey> sections) {
        if (level.getServer().isSameThread()) {
            return snapshotPriority(sections);
        }
        CompletableFuture<PrioritySnapshot> snapshot = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                snapshot.complete(snapshotPriority(sections));
            } catch (RuntimeException failed) {
                snapshot.completeExceptionally(failed);
            }
        });
        return snapshot.join();
    }

    private PrioritySnapshot snapshotPriority(
            List<io.github.lumi.domain.model.SectionKey> sections) {
        List<ChunkCoordinate> players = level.players().stream()
                .map(player -> new ChunkCoordinate(
                        player.chunkPosition().x, player.chunkPosition().z))
                .toList();
        Set<ChunkCoordinate> checked = new HashSet<>();
        Set<ChunkCoordinate> resident = new HashSet<>();
        for (var key : sections) {
            ChunkCoordinate chunk = ChunkCoordinate.from(key);
            if (!checked.add(chunk)) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                resident.add(chunk);
            }
        }
        return new PrioritySnapshot(players, Set.copyOf(resident));
    }

    static List<SectionKey> prioritize(
            List<SectionKey> sections,
            List<ChunkCoordinate> players,
            Set<ChunkCoordinate> resident) {
        Comparator<SectionKey> visibleOrder =
                Comparator.comparingLong(key -> distanceSquared(key, players));
        visibleOrder = visibleOrder
                .thenComparingInt(SectionKey::chunkX)
                .thenComparingInt(SectionKey::chunkZ)
                .thenComparingInt(SectionKey::sectionY);
        Comparator<SectionKey> storedOrder = MinecraftWorldStateApply
                .<SectionKey>regionOrder(players)
                .thenComparingInt(SectionKey::sectionY);
        return java.util.stream.Stream.concat(
                sections.stream()
                        .filter(key -> resident.contains(ChunkCoordinate.from(key)))
                        .sorted(visibleOrder),
                sections.stream()
                        .filter(key -> !resident.contains(ChunkCoordinate.from(key)))
                        .sorted(storedOrder))
                .toList();
    }

    static List<EntityChunkKey> prioritizeEntities(
            List<EntityChunkKey> entities,
            List<ChunkCoordinate> players) {
        return entities.stream().sorted(regionOrder(players)).toList();
    }

    private static <T extends HistoryKey> Comparator<T> regionOrder(
            List<ChunkCoordinate> players) {
        Comparator<T> order = Comparator.comparingLong(
                key -> regionDistanceSquared(key, players));
        return order
                .thenComparingInt(key -> Math.floorDiv(
                        key.chunkZ(), ChunkPos.REGION_SIZE))
                .thenComparingInt(key -> Math.floorDiv(
                        key.chunkX(), ChunkPos.REGION_SIZE))
                .thenComparingInt(key -> Math.floorMod(
                        key.chunkZ(), ChunkPos.REGION_SIZE))
                .thenComparingInt(key -> Math.floorMod(
                        key.chunkX(), ChunkPos.REGION_SIZE));
    }

    private static long distanceSquared(
            SectionKey key,
            List<ChunkCoordinate> players) {
        long nearest = Long.MAX_VALUE;
        for (ChunkCoordinate player : players) {
            long x = (long) key.chunkX() - player.x();
            long z = (long) key.chunkZ() - player.z();
            nearest = Math.min(nearest, x * x + z * z);
        }
        return nearest;
    }

    private static long regionDistanceSquared(
            HistoryKey key,
            List<ChunkCoordinate> players) {
        int regionX = Math.floorDiv(key.chunkX(), ChunkPos.REGION_SIZE);
        int regionZ = Math.floorDiv(key.chunkZ(), ChunkPos.REGION_SIZE);
        long nearest = Long.MAX_VALUE;
        for (ChunkCoordinate player : players) {
            long x = (long) regionX
                    - Math.floorDiv(player.x(), ChunkPos.REGION_SIZE);
            long z = (long) regionZ
                    - Math.floorDiv(player.z(), ChunkPos.REGION_SIZE);
            nearest = Math.min(nearest, x * x + z * z);
        }
        return nearest;
    }

    private record PrioritySnapshot(
            List<ChunkCoordinate> players,
            Set<ChunkCoordinate> resident) { }
}
