package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.mixin.BlockDisplayEntityAccessor;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class SkyCorruptionDisplayService {

    private static final int HORIZONTAL_RADIUS = 52;
    private static final int MIN_HEIGHT_OFFSET = 8;
    private static final int MAX_HEIGHT_OFFSET = 96;
    private static final int CLUSTER_VERTICAL_RADIUS = 14;
    private static final int MIN_CLUSTER_SIZE = 4;
    private static final int MAX_CLUSTER_SIZE = 13;
    private static final int SINGLE_DISPLAY_CHANCE = 35;
    private static final double JITTER_DISTANCE = 0.28D;
    private static final List<BlockState> DISPLAY_STATES = List.of(
            GBreakBlocks.MISSING_TEXTURE.getDefaultState(),
            Blocks.BASALT.getDefaultState(),
            Blocks.BLACKSTONE.getDefaultState(),
            Blocks.DEEPSLATE.getDefaultState(),
            Blocks.OBSIDIAN.getDefaultState(),
            Blocks.CRYING_OBSIDIAN.getDefaultState(),
            Blocks.NETHERRACK.getDefaultState(),
            Blocks.PURPUR_BLOCK.getDefaultState(),
            Blocks.COPPER_BLOCK.getDefaultState()
    );

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final List<SkyDisplay> activeDisplays = new ArrayList<>();

    private long nextSpawnAt;

    void tickExisting() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Iterator<SkyDisplay> iterator = this.activeDisplays.iterator();
        while (iterator.hasNext()) {
            SkyDisplay display = iterator.next();
            Entity entity = display.world().getEntity(display.entityId());
            if (entity == null) {
                iterator.remove();
                continue;
            }
            if (display.world().getTime() >= display.expiresAt()) {
                entity.discard();
                iterator.remove();
                continue;
            }
            this.jitter(entity, display.basePosition(), random);
        }
        this.trimToConfiguredLimit();
    }

    void spawnAround(ServerPlayerEntity player) {
        int maxDisplays = this.settings.maxSkyDisplays();
        if (this.activeDisplays.size() >= maxDisplays) {
            return;
        }

        ServerWorld world = player.getEntityWorld();
        if (world.getTime() < this.nextSpawnAt) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        this.nextSpawnAt = world.getTime() + random.nextInt(6, 15);
        BlockPos anchor = this.randomSkyAnchor(player, world, random);
        int clusterSize = random.nextInt(100) < SINGLE_DISPLAY_CHANCE
                ? 1
                : random.nextInt(MIN_CLUSTER_SIZE, MAX_CLUSTER_SIZE + 1);
        for (int index = 0; index < clusterSize && this.activeDisplays.size() < maxDisplays; index++) {
            BlockPos pos = anchor.add(
                    random.nextInt(-2, 3),
                    random.nextInt(-CLUSTER_VERTICAL_RADIUS, CLUSTER_VERTICAL_RADIUS + 1),
                    random.nextInt(-2, 3)
            );
            if (world.isInBuildLimit(pos)) {
                this.spawn(world, pos, random);
            }
        }
    }

    int clear() {
        int removed = 0;
        for (SkyDisplay display : this.activeDisplays) {
            Entity entity = display.world().getEntity(display.entityId());
            if (entity != null) {
                entity.discard();
                removed++;
            }
        }
        this.activeDisplays.clear();
        return removed;
    }

    int activeCount() {
        return this.activeDisplays.size();
    }

    private void trimToConfiguredLimit() {
        while (this.activeDisplays.size() > this.settings.maxSkyDisplays()) {
            SkyDisplay display = this.activeDisplays.remove(this.activeDisplays.size() - 1);
            Entity entity = display.world().getEntity(display.entityId());
            if (entity != null) {
                entity.discard();
            }
        }
    }

    private void spawn(ServerWorld world, BlockPos pos, ThreadLocalRandom random) {
        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world, SpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        ((BlockDisplayEntityAccessor) display).gbreak$setBlockState(this.randomDisplayState(random));
        display.setPosition(pos.getX(), pos.getY(), pos.getZ());
        display.setNoGravity(true);
        display.setSilent(true);
        display.setInvulnerable(true);
        world.spawnEntity(display);
        this.activeDisplays.add(new SkyDisplay(
                world,
                display.getUuid(),
                Vec3d.of(pos),
                world.getTime() + random.nextInt(80, 181)
        ));
    }

    private BlockState randomDisplayState(ThreadLocalRandom random) {
        if (random.nextInt(100) < 45) {
            return GBreakBlocks.MISSING_TEXTURE.getDefaultState();
        }
        return DISPLAY_STATES.get(1 + random.nextInt(DISPLAY_STATES.size() - 1));
    }

    private BlockPos randomSkyAnchor(ServerPlayerEntity player, ServerWorld world, ThreadLocalRandom random) {
        int x = player.getBlockX() + random.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1);
        int z = player.getBlockZ() + random.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1);
        int minY = Math.max(world.getBottomY() + 4, player.getBlockY() + MIN_HEIGHT_OFFSET);
        int maxY = Math.min(world.getTopYInclusive() - 4, player.getBlockY() + MAX_HEIGHT_OFFSET);
        int y = minY >= maxY ? Math.max(world.getBottomY() + 4, Math.min(minY, world.getTopYInclusive() - 4)) : random.nextInt(minY, maxY + 1);
        return new BlockPos(x, y, z);
    }

    private void jitter(Entity entity, Vec3d basePosition, ThreadLocalRandom random) {
        entity.setPosition(
                basePosition.x + random.nextDouble(-JITTER_DISTANCE, JITTER_DISTANCE),
                basePosition.y + random.nextDouble(-JITTER_DISTANCE, JITTER_DISTANCE),
                basePosition.z + random.nextDouble(-JITTER_DISTANCE, JITTER_DISTANCE)
        );
    }

    private record SkyDisplay(ServerWorld world, UUID entityId, Vec3d basePosition, long expiresAt) {}
}
