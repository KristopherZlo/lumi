package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.mixin.ChunkMapPersistenceAccessor;
import io.github.lumi.mixin.EntityStoragePersistenceAccessor;
import io.github.lumi.mixin.PersistentEntityManagerPersistenceAccessor;
import io.github.lumi.mixin.PlayerListPersistenceAccessor;
import io.github.lumi.mixin.SectionStoragePersistenceAccessor;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final boolean chunkVerificationRequired;
    private final List<EntityChunkKey> entityChunks;
    private final List<PlayerTarget> players;
    private final Set<ChunkCoordinate> relightChunks;
    private final SimpleRegionStorage entityStorage;
    private final MinecraftPersistedBatchVerifier verifier;
    private final boolean poiSyncRequired;
    private final boolean forceAndVerify;
    private final Map<ChunkCoordinate, Integer> pendingSnapshots =
            new LinkedHashMap<>();
    private final List<ChunkCoordinate> acceptedSnapshots = new ArrayList<>();
    private int nextChunk;
    private int nextEntityChunk;
    private int nextPlayer;
    private CompletableFuture<Void> lighting;
    private CompletableFuture<Void> synchronization;
    private Phase phase = Phase.LIGHTING;
    private long phaseStartedNanos;
    private long writeNanos;
    private long syncNanos;
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
        writeTarget.sectionKeys().forEach(key -> {
            ChunkCoordinate chunk = ChunkCoordinate.from(key);
            if (!alreadyStored.contains(chunk)) {
                DecodedSection section = writeTarget.sections().get(key);
                if (!section.hasPreparedDelta() || section.preparedDelta().lightChanged()) {
                    relight.add(chunk);
                }
            }
        });
        chunks = List.copyOf(grouped.keySet());
        chunkVerificationRequired = !verificationSections.isEmpty();
        relightChunks = Set.copyOf(relight);
        poiSyncRequired = forceAndVerify && chunkVerificationRequired;
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
            while (phase != Phase.COMPLETE && System.nanoTime() < deadlineNanos) {
                boolean advanced = switch (phase) {
                    case LIGHTING -> awaitLighting();
                    case CHUNKS -> saveChunk();
                    case ENTITIES -> saveEntityChunk();
                    case PLAYERS -> savePlayer();
                    case SYNCHRONIZING -> synchronizeStorage();
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

    private boolean awaitLighting() throws IOException {
        if (lighting == null) {
            lighting = CompletableFuture.allOf(relightChunks.stream()
                    .map(chunk -> level.getChunkSource().getLightEngine()
                            .waitForPendingTasks(chunk.x(), chunk.z()))
                    .toArray(CompletableFuture[]::new));
        }
        if (!lighting.isDone()) {
            return false;
        }
        MinecraftPersistenceFuture.join(lighting, "Restore lighting");
        synchronizeLighting();
        transitionTo(Phase.CHUNKS);
        return true;
    }

    private void synchronizeLighting() {
        Set<ChunkCoordinate> synchronizedChunks = new HashSet<>();
        for (ChunkCoordinate changed : relightChunks) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    ChunkCoordinate coordinate = new ChunkCoordinate(
                            changed.x() + offsetX, changed.z() + offsetZ);
                    if (!synchronizedChunks.add(coordinate)) {
                        continue;
                    }
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
        }
    }

    private boolean saveChunk() throws IOException {
        if (nextChunk == chunks.size()) {
            transitionTo(Phase.ENTITIES);
            return true;
        }
        var chunkMap = (ChunkMapPersistenceAccessor) level.getChunkSource().chunkMap;
        if (chunkMap.lumi$activeChunkWrites().get() >= MAX_PENDING_CHUNK_WRITES) {
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

    private boolean synchronizeStorage() throws IOException {
        if (synchronization == null) {
            var chunkSync = !forceAndVerify
                    || (chunks.isEmpty() && !chunkVerificationRequired)
                    ? CompletableFuture.completedFuture(null)
                    : level.getChunkSource().chunkMap.synchronize(true);
            var poiManager = level.getChunkSource().getPoiManager();
            for (ChunkCoordinate chunk : chunks) {
                poiManager.flush(new ChunkPos(chunk.x(), chunk.z()));
            }
            var poiSync = !poiSyncRequired
                    ? CompletableFuture.completedFuture(null)
                    : ((SectionStoragePersistenceAccessor) poiManager)
                            .lumi$simpleRegionStorage().synchronize(true);
            var entitySync = !forceAndVerify || entityStorage == null
                    ? CompletableFuture.completedFuture(null)
                    : entityStorage.synchronize(true);
            synchronization = CompletableFuture.allOf(chunkSync, poiSync, entitySync);
        }
        if (!synchronization.isDone()) {
            return false;
        }
        MinecraftPersistenceFuture.join(
                synchronization, "Restore storage synchronization");
        transitionTo(forceAndVerify ? Phase.VERIFYING : Phase.COMPLETE);
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
            case LIGHTING, SYNCHRONIZING -> syncNanos += elapsed;
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
            case SYNCHRONIZING -> "storage sync";
            case VERIFYING -> verifier.phase();
            case COMPLETE -> "verification";
        };
    }

    @Override
    public Timings timings() {
        return new Timings(writeNanos, syncNanos, verificationNanos);
    }

    private enum Phase {
        LIGHTING, CHUNKS, ENTITIES, PLAYERS, SYNCHRONIZING, VERIFYING, COMPLETE
    }
    private record PlayerTarget(ServerPlayer player, ServerPlayer.RespawnConfig expected) { }
}
