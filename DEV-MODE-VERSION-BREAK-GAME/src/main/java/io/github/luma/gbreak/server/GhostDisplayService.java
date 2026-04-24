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
import net.minecraft.util.math.AffineTransformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class GhostDisplayService {

    private static final float OVERLAY_FACE_OFFSET = 0.001F;
    private static final float OVERLAY_SCALE = 1.0F + OVERLAY_FACE_OFFSET * 2.0F;
    private static final float OVERLAY_TRANSLATION = -OVERLAY_FACE_OFFSET;

    private final List<TimedGhost> activeGhosts = new ArrayList<>();

    void spawn(ServerWorld world, BlockPos pos, BlockState state) {
        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world, SpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        ((BlockDisplayEntityAccessor) display).gbreak$setBlockState(state);
        this.applyOverlayTransform(display);
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

    private void applyOverlayTransform(DisplayEntity.BlockDisplayEntity display) {
        display.setTransformation(new AffineTransformation(
                new Vector3f(OVERLAY_TRANSLATION, OVERLAY_TRANSLATION, OVERLAY_TRANSLATION),
                new Quaternionf(),
                new Vector3f(OVERLAY_SCALE, OVERLAY_SCALE, OVERLAY_SCALE),
                new Quaternionf()
        ));
        display.setInterpolationDuration(0);
        display.setStartInterpolation(0);
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
