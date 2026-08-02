package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.operation.DeadlineFuture;
import io.github.lumi.mixin.ChunkMapPersistenceAccessor;
import io.github.lumi.mixin.EntityStoragePersistenceAccessor;
import io.github.lumi.mixin.PersistentEntityManagerPersistenceAccessor;
import io.github.lumi.mixin.PlayerListPersistenceAccessor;
import io.github.lumi.mixin.SectionStoragePersistenceAccessor;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.TagValueInput;

/** Stages or forces one loaded Restore section/entity persistence boundary. */
final class MinecraftRestorePersistenceSession implements WorldPersistenceSession {
    private static final int MAX_PENDING_CHUNK_WRITES =
            StreamingPreparedWorldMutationSession.MAX_CHUNKS;
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final List<ChunkCoordinate> chunks;
    private final Set<ChunkCoordinate> chunkVerificationChunks;
    private final List<EntityChunkKey> entityChunks;
    private final List<PlayerTarget> players;
    private final Set<ChunkCoordinate> relightChunks;
    private final Set<ChunkCoordinate> lightSyncChunks;
    private final Set<ChunkCoordinate> poiChunks;
    private final Set<ChunkCoordinate> entityVerificationChunks;
    private final SimpleRegionStorage entityStorage;
    private final MinecraftPersistedBatchVerifier verifier;
    private final boolean forceAndVerify;
    private final Map<ChunkCoordinate, Integer> pendingSnapshots =
            new LinkedHashMap<>();
    private final List<ChunkCoordinate> acceptedSnapshots = new ArrayList<>();
    private final int firstLightAffectedChunk;
    private int nextChunk;
    private int nextEntityChunk;
    private int nextPlayer;
    private CompletableFuture<Void> lighting;
    private CompletableFuture<Void> synchronization;
    private CompletableFuture<Void> forcing;
    private List<MinecraftRegionStorageSynchronizer.Synchronization>
            storageSynchronizations = List.of();
    private Phase phase = Phase.CHUNKS;
    private boolean lightingSynchronized;
    private long phaseStartedNanos;
    private long lightingNanos;
    private long writeNanos;
    private long syncNanos;
    private long forceNanos;
    private long verificationNanos;

    MinecraftRestorePersistenceSession(
            ServerLevel level,
            DimensionFreezeState freeze,
            Executor background,
            MinecraftStoredChunkAccess storedChunks,
            MinecraftEntityChunkCapture entityCapture,
            PreparedMinecraftState writeTarget,
            WorldStateApply.State verificationTarget,
            List<SectionKey> verificationSections,
            List<EntityChunkKey> verificationEntities,
            Set<ChunkCoordinate> alreadyStored,
            boolean savePlayers,
            boolean forceAndVerify) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(storedChunks, "storedChunks");
        Objects.requireNonNull(entityCapture, "entityCapture");
        Objects.requireNonNull(writeTarget, "writeTarget");
        Objects.requireNonNull(verificationTarget, "verificationTarget");
        Objects.requireNonNull(verificationSections, "verificationSections");
        Objects.requireNonNull(verificationEntities, "verificationEntities");
        Objects.requireNonNull(alreadyStored, "alreadyStored");
        this.forceAndVerify = forceAndVerify;

        Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> grouped =
                groupedSections(writeTarget, alreadyStored);
        Set<ChunkCoordinate> relight = new HashSet<>();
        Set<ChunkCoordinate> poi = new HashSet<>();
        writeTarget.sectionKeys().forEach(key -> {
            ChunkCoordinate chunk = ChunkCoordinate.from(key);
            if (!alreadyStored.contains(chunk)) {
                DecodedSection section = writeTarget.sections().get(key);
                PreparedSectionDelta delta = section.hasPreparedDelta()
                        ? section.preparedDelta() : null;
                if (delta == null || delta.lightChanged()) {
                    relight.add(chunk);
                }
                if (delta == null || delta.poiIndexes().length != 0) {
                    poi.add(chunk);
                }
            }
        });
        chunkVerificationChunks = verificationSections.stream()
                .map(ChunkCoordinate::from)
                .collect(Collectors.toUnmodifiableSet());
        entityVerificationChunks = verificationEntities.stream()
                .map(ChunkCoordinate::from)
                .collect(Collectors.toUnmodifiableSet());
        relightChunks = Set.copyOf(relight);
        lightSyncChunks = surroundingChunks(relightChunks);
        List<ChunkCoordinate> orderedChunks = new ArrayList<>(grouped.size());
        orderedChunks.addAll(grouped.keySet());
        orderedChunks.sort(Comparator.comparing(lightSyncChunks::contains));
        int firstAffected = 0;
        while (firstAffected < orderedChunks.size()
                && !lightSyncChunks.contains(orderedChunks.get(firstAffected))) {
            firstAffected++;
        }
        firstLightAffectedChunk = firstAffected;
        chunks = List.copyOf(orderedChunks);
        lightingSynchronized = relightChunks.isEmpty();
        poiChunks = Set.copyOf(poi);
        entityChunks = writeTarget.entityKeys();
        chunks.forEach(chunk -> pendingSnapshots.merge(chunk, 1, Integer::sum));
        entityChunks.forEach(key -> pendingSnapshots.merge(
                ChunkCoordinate.from(key), 1, Integer::sum));
        players = savePlayers ? level.getServer().getPlayerList().getPlayers().stream()
                .map(player -> playerTarget(player, writeTarget.source().playerSpawns()))
                .toList() : List.of();
        entityStorage = entityChunks.isEmpty()
                && verificationEntities.isEmpty() ? null
                : ((EntityStoragePersistenceAccessor) entityAccess().lumi$permanentStorage())
                        .lumi$simpleRegionStorage();
        verifier = forceAndVerify
                ? new MinecraftPersistedBatchVerifier(
                        level, background, storedChunks, entityCapture,
                        verificationTarget, verificationSections,
                        verificationEntities, entityStorage)
                : null;
        phaseStartedNanos = System.nanoTime();
    }

    private static Map<ChunkCoordinate, Map<SectionKey, DecodedSection>>
            groupedSections(
                    PreparedMinecraftState target,
                    Set<ChunkCoordinate> alreadyStored) {
        Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> grouped =
                new LinkedHashMap<>();
        target.sectionKeys().forEach(key -> {
            ChunkCoordinate chunk = ChunkCoordinate.from(key);
            if (!alreadyStored.contains(chunk)) {
                grouped.computeIfAbsent(chunk, ignored -> new LinkedHashMap<>())
                        .put(key, target.sections().get(key));
            }
        });
        grouped.replaceAll((ignored, sections) -> Map.copyOf(sections));
        return grouped;
    }

    @Override
    public boolean advanceUntil(long deadlineNanos) throws IOException {
        try {
            startLighting();
            while (phase != Phase.COMPLETE && System.nanoTime() < deadlineNanos) {
                boolean advanced = switch (phase) {
                    case LIGHTING -> awaitLighting(deadlineNanos);
                    case CHUNKS -> saveChunk(deadlineNanos);
                    case ENTITIES -> saveEntityChunk();
                    case PLAYERS -> savePlayer();
                    case SYNCHRONIZING -> synchronizeStorage(deadlineNanos);
                    case FORCING -> forceStorage(deadlineNanos);
                    case VERIFYING -> verifyPersisted(deadlineNanos);
                    case COMPLETE -> true;
                };
                if (!advanced) {
                    return false;
                }
            }
            return phase == Phase.COMPLETE;
        } catch (RuntimeException failed) {
            throw new IOException("Restore persistence failed during " + phase(), failed);
        }
    }

    private void startLighting() {
        if (lighting == null) {
            lighting = CompletableFuture.allOf(relightChunks.stream()
                    .map(chunk -> level.getChunkSource().getLightEngine()
                            .waitForPendingTasks(chunk.x(), chunk.z()))
                    .toArray(CompletableFuture[]::new));
        }
    }

    private boolean awaitLighting(long deadlineNanos) throws IOException {
        if (!DeadlineFuture.await(lighting, deadlineNanos)) {
            return false;
        }
        MinecraftPersistenceFuture.join(lighting, "Restore lighting");
        synchronizeLighting();
        lightingSynchronized = true;
        transitionTo(Phase.CHUNKS);
        return true;
    }

    private void synchronizeLighting() {
        for (ChunkCoordinate coordinate : lightSyncChunks) {
            ChunkPos position = new ChunkPos(coordinate.x(), coordinate.z());
            var players = level.getChunkSource().chunkMap.getPlayers(position, false);
            if (players.isEmpty()) {
                continue;
            }
            var packet = new ClientboundLightUpdatePacket(
                    position, level.getLightEngine(), null, null);
            players.forEach(player -> player.connection.send(packet));
        }
    }

    private static Set<ChunkCoordinate> surroundingChunks(
            Set<ChunkCoordinate> chunks) {
        Set<ChunkCoordinate> surrounding = new HashSet<>();
        for (ChunkCoordinate chunk : chunks) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    surrounding.add(new ChunkCoordinate(
                            chunk.x() + offsetX, chunk.z() + offsetZ));
                }
            }
        }
        return Set.copyOf(surrounding);
    }

    private boolean saveChunk(long deadlineNanos) throws IOException {
        if (!lightingSynchronized && nextChunk == firstLightAffectedChunk) {
            transitionTo(Phase.LIGHTING);
            return true;
        }
        if (nextChunk == chunks.size()) {
            transitionTo(Phase.ENTITIES);
            return true;
        }
        var chunkMap = (ChunkMapPersistenceAccessor) level.getChunkSource().chunkMap;
        if (!DeadlineFuture.await(() -> chunkMap.lumi$activeChunkWrites().get()
                < MAX_PENDING_CHUNK_WRITES, deadlineNanos)) {
            return false;
        }
        ChunkCoordinate coordinate = chunks.get(nextChunk);
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                coordinate.x(), coordinate.z());
        if (chunk == null) {
            throw new IOException("Restore chunk unloaded before persistence: " + coordinate);
        }
        chunk.markUnsaved();
        if (!chunkMap.lumi$save(chunk)) {
            throw new IOException("Cannot persist restored chunk " + coordinate);
        }
        nextChunk++;
        acceptSnapshot(coordinate);
        return true;
    }

    private boolean saveEntityChunk() throws IOException {
        if (nextEntityChunk == entityChunks.size()) {
            transitionTo(Phase.PLAYERS);
            return true;
        }
        EntityChunkKey key = entityChunks.get(nextEntityChunk);
        var access = entityAccess();
        boolean[] saved = {false};
        freeze.runAuthorized(() -> saved[0] = access.lumi$storeChunkSections(
                new ChunkPos(key.chunkX(), key.chunkZ()).toLong(), ignored -> { }));
        if (!saved[0]) {
            return false;
        }
        acceptSnapshot(ChunkCoordinate.from(key));
        nextEntityChunk++;
        return true;
    }

    private boolean savePlayer() throws IOException {
        if (nextPlayer == players.size()) {
            transitionTo(Phase.SYNCHRONIZING);
            return true;
        }
        PlayerTarget target = players.get(nextPlayer++);
        ServerPlayer player = target.player();
        var playerData = ((PlayerListPersistenceAccessor) level.getServer().getPlayerList())
                .lumi$playerDataStorage();
        playerData.save(player);
        CompoundTag saved = playerData.load(player.nameAndId()).orElseThrow(() ->
                new IOException("Cannot reread restored player " + player.getUUID()));
        boolean hasPersistedSpawn = saved.contains("respawn");
        var persisted = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), saved)
                .read("respawn", ServerPlayer.RespawnConfig.CODEC).orElse(null);
        if (hasPersistedSpawn != (target.expected() != null)
                || !Objects.equals(target.expected(), persisted)) {
            throw new IOException("Persisted player spawn mismatch for " + player.getUUID());
        }
        return true;
    }

    private PlayerTarget playerTarget(
            ServerPlayer player, Map<java.util.UUID, PlayerSpawn> targetSpawns) {
        PlayerSpawn spawn = targetSpawns.get(player.getUUID());
        ServerPlayer.RespawnConfig expected = spawn == null
                ? player.getRespawnConfig()
                : MinecraftPreparedWorldAccess.respawnConfig(level, spawn);
        if (spawn == null && expected != null
                && expected.respawnData().dimension().equals(level.dimension())) {
            expected = null;
        }
        return new PlayerTarget(player, expected);
    }

    private boolean synchronizeStorage(long deadlineNanos) throws IOException {
        if (synchronization == null) {
            var poiManager = level.getChunkSource().getPoiManager();
            for (ChunkCoordinate chunk : poiChunks) {
                poiManager.flush(new ChunkPos(chunk.x(), chunk.z()));
            }
            if (!forceAndVerify) {
                transitionTo(Phase.COMPLETE);
                return true;
            }
            List<MinecraftRegionStorageSynchronizer.Synchronization> prepared =
                    new ArrayList<>(3);
            if (!chunkVerificationChunks.isEmpty()) {
                prepared.add(MinecraftRegionStorageSynchronizer.prepare(
                        level.getChunkSource().chunkMap,
                        chunkVerificationChunks));
                prepared.add(MinecraftRegionStorageSynchronizer.prepare(
                        ((SectionStoragePersistenceAccessor) poiManager)
                                .lumi$simpleRegionStorage(),
                        chunkVerificationChunks));
            }
            if (entityStorage != null && !entityVerificationChunks.isEmpty()) {
                prepared.add(MinecraftRegionStorageSynchronizer.prepare(
                        entityStorage, entityVerificationChunks));
            }
            storageSynchronizations = List.copyOf(prepared);
            synchronization = CompletableFuture.allOf(storageSynchronizations.stream()
                    .map(MinecraftRegionStorageSynchronizer.Synchronization::writeBarrier)
                    .toArray(CompletableFuture[]::new));
        }
        if (!DeadlineFuture.await(synchronization, deadlineNanos)) {
            return false;
        }
        MinecraftPersistenceFuture.join(
                synchronization, "Restore storage synchronization");
        transitionTo(Phase.FORCING);
        return true;
    }

    private boolean forceStorage(long deadlineNanos) throws IOException {
        if (forcing == null) {
            forcing = CompletableFuture.allOf(storageSynchronizations.stream()
                    .map(MinecraftRegionStorageSynchronizer.Synchronization::forceAffected)
                    .toArray(CompletableFuture[]::new));
        }
        if (!DeadlineFuture.await(forcing, deadlineNanos)) {
            return false;
        }
        MinecraftPersistenceFuture.join(forcing, "Restore affected-region force");
        transitionTo(Phase.VERIFYING);
        return true;
    }

    private boolean verifyPersisted(long deadlineNanos) throws IOException {
        if (verifier.advanceUntil(deadlineNanos)) {
            transitionTo(Phase.COMPLETE);
        }
        return phase == Phase.COMPLETE;
    }

    private void transitionTo(Phase next) {
        long now = System.nanoTime();
        long elapsed = Math.max(0, now - phaseStartedNanos);
        switch (phase) {
            case LIGHTING -> lightingNanos += elapsed;
            case SYNCHRONIZING -> syncNanos += elapsed;
            case FORCING -> forceNanos += elapsed;
            case CHUNKS, ENTITIES, PLAYERS -> writeNanos += elapsed;
            case VERIFYING -> verificationNanos += elapsed;
            case COMPLETE -> { }
        }
        phase = next;
        phaseStartedNanos = now;
    }

    private void acceptSnapshot(ChunkCoordinate chunk) {
        int remaining = pendingSnapshots.getOrDefault(chunk, 0);
        if (remaining <= 0) {
            throw new IllegalStateException("Unexpected Restore snapshot: " + chunk);
        }
        if (remaining == 1) {
            pendingSnapshots.remove(chunk);
            acceptedSnapshots.add(chunk);
        } else {
            pendingSnapshots.put(chunk, remaining - 1);
        }
    }

    @Override
    public List<ChunkCoordinate> drainAcceptedSnapshotChunks() {
        List<ChunkCoordinate> accepted = List.copyOf(acceptedSnapshots);
        acceptedSnapshots.clear();
        return accepted;
    }

    @SuppressWarnings("unchecked")
    private PersistentEntityManagerPersistenceAccessor<Entity> entityAccess() {
        return (PersistentEntityManagerPersistenceAccessor<Entity>)
                ((ServerLevelEntityManagerAccessor) level).lumi$entityManager();
    }

    @Override
    public String phase() {
        return switch (phase) {
            case LIGHTING -> "waiting for lighting";
            case CHUNKS -> "persisting loaded chunks";
            case ENTITIES -> "persisting entities";
            case PLAYERS -> "persisting players";
            case SYNCHRONIZING -> "waiting for storage writes";
            case FORCING -> "forcing affected regions";
            case VERIFYING -> verifier.phase();
            case COMPLETE -> "verification";
        };
    }

    @Override
    public Timings timings() {
        return new Timings(
                writeNanos, lightingNanos, syncNanos, forceNanos,
                verificationNanos);
    }

    @Override
    public void close() {
        if (verifier != null) {
            verifier.close();
        }
    }

    private enum Phase {
        LIGHTING, CHUNKS, ENTITIES, PLAYERS, SYNCHRONIZING, FORCING,
        VERIFYING, COMPLETE
    }
    private record PlayerTarget(ServerPlayer player, ServerPlayer.RespawnConfig expected) { }
}
