package io.github.luma.gbreak.server;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.corruption.CorruptionMaskSampler;
import io.github.luma.gbreak.mixin.BlockDisplayEntityAccessor;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

final class SkyCorruptionDisplayService {

    private static final long STATE_HASH_SALT = 0x534B59474C495448L;
    private static final double MISSING_TEXTURE_CHANCE = 0.45D;
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

    private final CorruptionSettings settings;
    private final SkyDisplayPlacementPlanner placementPlanner;
    private final List<SkyDisplay> activeDisplays = new ArrayList<>();

    SkyCorruptionDisplayService(CorruptionSettings settings, CorruptionMaskSampler maskSampler) {
        this.settings = settings;
        this.placementPlanner = new SkyDisplayPlacementPlanner(settings, maskSampler);
    }

    void tickExisting() {
        Iterator<SkyDisplay> iterator = this.activeDisplays.iterator();
        while (iterator.hasNext()) {
            SkyDisplay display = iterator.next();
            Entity entity = display.world().getEntity(display.entityId());
            if (entity == null) {
                iterator.remove();
            }
        }
    }

    void generate(ServerPlayerEntity player) {
        this.clear();
        int maxDisplays = this.settings.maxSkyDisplays();
        if (maxDisplays <= 0) {
            return;
        }

        ServerWorld world = player.getEntityWorld();
        List<BlockPos> positions = this.placementPlanner.plan(
                player.getBlockPos(),
                world.getBottomY(),
                world.getTopYInclusive(),
                maxDisplays
        );
        for (BlockPos pos : positions) {
            if (world.isInBuildLimit(pos)) {
                this.spawn(world, pos);
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

    private void spawn(ServerWorld world, BlockPos pos) {
        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world, SpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        ((BlockDisplayEntityAccessor) display).gbreak$setBlockState(this.displayState(pos));
        display.setPosition(pos.getX(), pos.getY(), pos.getZ());
        display.setNoGravity(true);
        display.setSilent(true);
        display.setInvulnerable(true);
        world.spawnEntity(display);
        this.activeDisplays.add(new SkyDisplay(world, display.getUuid()));
    }

    private BlockState displayState(BlockPos pos) {
        double unit = this.hashUnit(pos.getX(), pos.getZ(), STATE_HASH_SALT);
        if (unit < MISSING_TEXTURE_CHANCE) {
            return GBreakBlocks.MISSING_TEXTURE.getDefaultState();
        }

        double normalized = (unit - MISSING_TEXTURE_CHANCE) / (1.0D - MISSING_TEXTURE_CHANCE);
        int stateIndex = 1 + Math.min(DISPLAY_STATES.size() - 2, (int) Math.floor(normalized * (DISPLAY_STATES.size() - 1)));
        return DISPLAY_STATES.get(stateIndex);
    }

    private double hashUnit(int x, int z, long salt) {
        long hash = (((long) x) << 32) ^ (z & 0xffffffffL) ^ salt;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (hash >>> 11) * 0x1.0p-53D;
    }

    private record SkyDisplay(ServerWorld world, UUID entityId) {
    }
}
