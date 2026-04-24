package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.mixin.BlockDisplayEntityAccessor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

final class RisingGroundBlockService {

    private static final int HORIZONTAL_RADIUS = 30;
    private static final int MAX_ACTIVE = 128;
    private static final int MIN_SPAWN_DELAY_TICKS = 42;
    private static final int MAX_SPAWN_DELAY_TICKS = 96;

    private final List<RisingBlock> activeBlocks = new ArrayList<>();

    private long nextSpawnAt;

    void tick(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        this.tickExisting();
        if (this.activeBlocks.size() >= MAX_ACTIVE || world.getTime() < this.nextSpawnAt) {
            return;
        }

        this.nextSpawnAt = world.getTime() + random.nextInt(MIN_SPAWN_DELAY_TICKS, MAX_SPAWN_DELAY_TICKS + 1);
        this.spawnSlice(player, random);
    }

    int clear() {
        int removed = 0;
        for (RisingBlock block : this.activeBlocks) {
            Entity entity = block.world().getEntity(block.entityId());
            if (entity != null) {
                entity.discard();
                removed++;
            }
        }
        this.activeBlocks.clear();
        this.nextSpawnAt = 0L;
        return removed;
    }

    private void tickExisting() {
        Iterator<RisingBlock> iterator = this.activeBlocks.iterator();
        while (iterator.hasNext()) {
            RisingBlock block = iterator.next();
            Entity entity = block.world().getEntity(block.entityId());
            if (entity == null) {
                iterator.remove();
                continue;
            }
            if (block.world().getTime() >= block.expiresAt()) {
                entity.discard();
                iterator.remove();
                continue;
            }

            entity.setPosition(
                    entity.getX() + block.driftX(),
                    entity.getY() + block.verticalSpeed(),
                    entity.getZ() + block.driftZ()
            );
        }
    }

    private void spawnSlice(ServerPlayerEntity player, ThreadLocalRandom random) {
        ServerWorld world = player.getEntityWorld();
        BlockPos center = player.getBlockPos().add(
                random.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1),
                0,
                random.nextInt(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS + 1)
        );
        int sliceSize = random.nextBoolean() ? 3 : 5;
        int radius = sliceSize / 2;
        double verticalSpeed = random.nextDouble(0.014D, 0.034D);
        double driftX = random.nextDouble(-0.003D, 0.003D);
        double driftZ = random.nextDouble(-0.003D, 0.003D);
        long expiresAt = world.getTime() + random.nextInt(140, 261);
        for (int xOffset = -radius; xOffset <= radius && this.activeBlocks.size() < MAX_ACTIVE; xOffset++) {
            for (int zOffset = -radius; zOffset <= radius && this.activeBlocks.size() < MAX_ACTIVE; zOffset++) {
                BlockPos pos = this.surfacePos(world, center.getX() + xOffset, center.getZ() + zOffset);
                if (pos == null) {
                    continue;
                }

                BlockState state = world.getBlockState(pos);
                if (this.canLift(state)) {
                    this.spawn(world, pos, state, random, verticalSpeed, driftX, driftZ, expiresAt);
                }
            }
        }
    }

    private BlockPos surfacePos(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos pos = new BlockPos(x, y, z);
        return world.isInBuildLimit(pos) ? pos : null;
    }

    private void spawn(
            ServerWorld world,
            BlockPos pos,
            BlockState state,
            ThreadLocalRandom random,
            double verticalSpeed,
            double driftX,
            double driftZ,
            long expiresAt
    ) {
        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world, SpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        ((BlockDisplayEntityAccessor) display).gbreak$setBlockState(state);
        display.setPosition(
                pos.getX() + random.nextDouble(-0.08D, 0.08D),
                pos.getY() + 0.12D,
                pos.getZ() + random.nextDouble(-0.08D, 0.08D)
        );
        display.setTeleportDuration(3);
        display.setNoGravity(true);
        display.setSilent(true);
        display.setInvulnerable(true);
        world.spawnEntity(display);
        this.activeBlocks.add(new RisingBlock(
                world,
                display.getUuid(),
                expiresAt,
                verticalSpeed,
                driftX,
                driftZ
        ));
    }

    private boolean canLift(BlockState state) {
        return !state.isAir()
                && !state.hasBlockEntity()
                && !state.isOf(GBreakBlocks.MISSING_TEXTURE)
                && !state.isOf(GBreakBlocks.GROUND_CORRUPTION);
    }

    private record RisingBlock(
            ServerWorld world,
            UUID entityId,
            long expiresAt,
            double verticalSpeed,
            double driftX,
            double driftZ
    ) {
    }
}
