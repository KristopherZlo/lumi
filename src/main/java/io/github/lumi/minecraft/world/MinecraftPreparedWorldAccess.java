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
import java.util.stream.StreamSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.LevelData;

/** Minecraft adapter for authorized, loaded-chunk Restore mutation and reread. */
public final class MinecraftPreparedWorldAccess implements PreparedWorldAccess {
    private static final int UPDATE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final MinecraftSectionCapture sections = new MinecraftSectionCapture();
    private final MinecraftEntityChunkCapture entities = new MinecraftEntityChunkCapture();

    public MinecraftPreparedWorldAccess(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
    }

    @Override
    public void setBlock(SectionKey key, int localIndex, BlockState state) throws IOException {
        requireChunk(key.chunkX(), key.chunkZ());
        BlockPos position = position(key, localIndex);
        freeze.runAuthorized(() -> level.setBlock(position, state, UPDATE_FLAGS));
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
        BlockEntity existing = chunk.getBlockEntity(position);
        BlockEntity replacement = existing == null
                ? BlockEntity.loadStatic(
                        position, chunk.getBlockState(position), full, level.registryAccess())
                : null;
        if (existing == null && replacement == null) {
            throw new IOException("Cannot create restored block entity at " + position);
        }
        freeze.runAuthorized(() -> {
            if (existing == null) {
                chunk.setBlockEntity(replacement);
            } else {
                existing.loadWithComponents(TagValueInput.create(
                        ProblemReporter.DISCARDING, level.registryAccess(), full));
                existing.setChanged();
            }
        });
    }

    @Override
    public SectionBlob captureSection(SectionKey key) throws IOException {
        return sections.capture(
                level, requireChunk(key.chunkX(), key.chunkZ()), key.sectionY());
    }

    @Override
    public List<UUID> durableEntityIds(EntityChunkKey key) {
        return matchingEntities(key).filter(MinecraftPreparedWorldAccess::isDurableRoot)
                .map(Entity::getUUID).toList();
    }

    @Override
    public void removeEntity(EntityChunkKey key, UUID id) throws IOException {
        Entity entity = matchingEntities(key)
                .filter(candidate -> candidate.getUUID().equals(id))
                .filter(MinecraftPreparedWorldAccess::isDurableRoot)
                .findFirst().orElseThrow(
                        () -> new IOException("Restored entity disappeared before removal: " + id));
        freeze.runAuthorized(entity::discard);
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
        freeze.runAuthorized(() -> added[0] = level.addWithUUID(entity));
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
            if (expected != null && !Optional.of(expected).equals(currentSpawn(player))) {
                return false;
            }
        }
        return true;
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
        return StreamSupport.stream(level.getAllEntities().spliterator(), false)
                .filter(entity -> entity.chunkPosition().x == key.chunkX()
                        && entity.chunkPosition().z == key.chunkZ());
    }

    private static boolean isDurableRoot(Entity entity) {
        return !(entity instanceof Player) && !entity.isPassenger() && entity.shouldBeSaved();
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
