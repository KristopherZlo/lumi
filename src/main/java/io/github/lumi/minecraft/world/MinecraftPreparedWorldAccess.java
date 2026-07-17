package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.PlayerSpawn;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelData;

/** Minecraft adapter for authorized, loaded-chunk Restore mutation and reread. */
public final class MinecraftPreparedWorldAccess implements PreparedWorldAccess {
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final MinecraftSectionRewriter sectionRewriter;
    private final MinecraftSectionCapture sections = new MinecraftSectionCapture();
    private final MinecraftEntityChunkCapture entities = new MinecraftEntityChunkCapture();
    private final ChunkEntityLookup entityLookup;

    public MinecraftPreparedWorldAccess(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        sectionRewriter = new MinecraftSectionRewriter(level);
        entityLookup = ChunkEntityLookup.forLevel(level);
    }

    @Override
    public void applySection(SectionKey key, DecodedSection section) throws IOException {
        LevelChunk chunk = requireChunk(key.chunkX(), key.chunkZ());
        freeze.runAuthorized(() -> sectionRewriter.apply(chunk, key, section));
    }

    @Override
    public List<Integer> blockEntityIndexes(SectionKey key) throws IOException {
        var indexes = new ArrayList<Integer>();
        for (BlockPos position : requireChunk(key.chunkX(), key.chunkZ())
                .getBlockEntities().keySet()) {
            if (SectionPos.blockToSectionCoord(position.getY()) == key.sectionY()) {
                indexes.add(MinecraftSectionCapture.localIndex(position));
            }
        }
        return List.copyOf(indexes);
    }

    @Override
    public void removeBlockEntity(SectionKey key, int localIndex) throws IOException {
        LevelChunk chunk = requireChunk(key.chunkX(), key.chunkZ());
        BlockPos position = position(key, localIndex);
        freeze.runAuthorized(() -> chunk.removeBlockEntity(position));
    }

    @Override
    public void loadBlockEntity(
            SectionKey key, int localIndex, CompoundTag nbt) throws IOException {
        LevelChunk chunk = requireChunk(key.chunkX(), key.chunkZ());
        BlockPos position = position(key, localIndex);
        CompoundTag full = nbt.copy();
        full.putInt("x", position.getX());
        full.putInt("y", position.getY());
        full.putInt("z", position.getZ());
        BlockEntity replacement = BlockEntity.loadStatic(
                position, chunk.getBlockState(position), full, level.registryAccess());
        if (replacement == null) {
            throw new IOException("Cannot create restored block entity at " + position);
        }
        freeze.runAuthorized(() -> {
            chunk.removeBlockEntity(position);
            chunk.setBlockEntity(replacement);
        });
    }

    @Override
    public SectionBlob captureSection(SectionKey key) throws IOException {
        return sections.capture(
                level, requireChunk(key.chunkX(), key.chunkZ()), key.sectionY());
    }

    @Override
    public List<UUID> durableEntityIds(EntityChunkKey key) {
        return matchingEntities(key).filter(MinecraftEntityChunkCapture::isDurableRoot)
                .map(Entity::getUUID).toList();
    }

    @Override
    public void removeEntity(EntityChunkKey key, UUID id) throws IOException {
        Entity entity = matchingEntities(key)
                .filter(candidate -> candidate.getUUID().equals(id))
                .filter(MinecraftEntityChunkCapture::isDurableRoot)
                .findFirst().orElseThrow(
                        () -> new IOException("Restored entity disappeared before removal: " + id));
        List<Entity> graph = entity.getSelfAndPassengers()
                .filter(member -> !(member instanceof Player)).toList();
        freeze.runAuthorized(() -> graph.forEach(member ->
                member.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER)));
    }

    @Override
    public void addEntity(EntityChunkKey key, DecodedEntity decoded) throws IOException {
        Entity entity = EntityType.loadEntityRecursive(
                decoded.type(), decoded.nbt().copy(), level,
                EntitySpawnReason.LOAD, EntityProcessor.NOP);
        if (entity == null || entity.chunkPosition().x != key.chunkX()
                || entity.chunkPosition().z != key.chunkZ()) {
            throw new IOException("Restored entity position does not match " + key);
        }
        boolean[] added = {false};
        freeze.runAuthorized(() -> added[0] = level.tryAddFreshEntityWithPassengers(entity));
        if (!added[0]) {
            throw new IOException("Cannot add restored entity " + decoded.id());
        }
    }

    @Override
    public EntityChunkBlob captureEntities(EntityChunkKey key) throws IOException {
        return entities.capture(level, matchingEntities(key));
    }

    @Override
    public void applyPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PlayerSpawn spawn = spawns.get(player.getUUID());
            if (spawn == null) {
                if (currentSpawn(player).isPresent()) {
                    player.setRespawnPosition(null, false);
                }
                continue;
            }
            var data = LevelData.RespawnData.of(
                    level.dimension(), new BlockPos(spawn.x(), spawn.y(), spawn.z()),
                    spawn.yaw(), spawn.pitch());
            player.setRespawnPosition(
                    new ServerPlayer.RespawnConfig(data, spawn.forced()), false);
        }
    }

    @Override
    public boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PlayerSpawn expected = spawns.get(player.getUUID());
            if (!matchesSpawn(expected, currentSpawn(player))) {
                return false;
            }
        }
        return true;
    }

    static boolean matchesSpawn(PlayerSpawn expected, Optional<PlayerSpawn> actual) {
        return Optional.ofNullable(expected).equals(actual);
    }

    static BlockPos position(SectionKey key, int localIndex) {
        if (localIndex < 0 || localIndex >= SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException("Local section index must be 0-4095");
        }
        int x = localIndex & 15;
        int z = (localIndex >>> 4) & 15;
        int y = (localIndex >>> 8) & 15;
        return new BlockPos(
                key.chunkX() * 16 + x,
                key.sectionY() * 16 + y,
                key.chunkZ() * 16 + z);
    }

    private LevelChunk requireChunk(int chunkX, int chunkZ) throws IOException {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IOException("Restore chunk is not loaded: " + chunkX + "," + chunkZ);
        }
        return chunk;
    }

    private java.util.stream.Stream<Entity> matchingEntities(EntityChunkKey key) {
        return entityLookup.inChunk(key)
                .filter(Entity.class::isInstance)
                .map(Entity.class::cast);
    }

    private Optional<PlayerSpawn> currentSpawn(ServerPlayer player) {
        var config = player.getRespawnConfig();
        if (config == null || !config.respawnData().dimension().equals(level.dimension())) {
            return Optional.empty();
        }
        var data = config.respawnData();
        var position = data.pos();
        return Optional.of(new PlayerSpawn(
                position.getX(), position.getY(), position.getZ(),
                data.yaw(), data.pitch(), config.forced()));
    }
}
