package io.github.luma.gbreak.server;

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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

final class GhostDisplayService {

    private final List<TimedGhost> activeGhosts = new ArrayList<>();

    void spawn(ServerWorld world, BlockPos pos, BlockState state) {
        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world, SpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        ((BlockDisplayEntityAccessor) display).gbreak$setBlockState(state);
        display.setPosition(pos.getX(), pos.getY(), pos.getZ());
        display.setNoGravity(true);
        world.spawnEntity(display);
        world.playSound(
                null,
                pos,
                state.getSoundGroup().getPlaceSound(),
                SoundCategory.BLOCKS,
                (state.getSoundGroup().getVolume() + 1.0F) * 0.5F,
                state.getSoundGroup().getPitch() * 0.9F
        );
        this.activeGhosts.add(new TimedGhost(
                world,
                display.getUuid(),
                world.getTime() + ThreadLocalRandom.current().nextInt(40, 101)
        ));
    }

    void tick() {
        Iterator<TimedGhost> iterator = this.activeGhosts.iterator();
        while (iterator.hasNext()) {
            TimedGhost ghost = iterator.next();
            Entity entity = ghost.world().getEntity(ghost.entityId());
            if (entity == null) {
                iterator.remove();
                continue;
            }
            if (ghost.world().getTime() >= ghost.expiresAt()) {
                entity.discard();
                iterator.remove();
            }
        }
    }

    private record TimedGhost(ServerWorld world, UUID entityId, long expiresAt) {
    }
}
