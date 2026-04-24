package io.github.luma.gbreak.server;

import io.github.luma.gbreak.mixin.BlockDisplayEntityAccessor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class WhiteFadeRestoreDisplayService {

    private static final int FADE_TICKS = 34;
    private static final float START_SCALE = 1.025F;
    private static final float MIN_SCALE = 0.04F;
    private static final int INTERPOLATION_TICKS = 3;

    private final List<WhiteFadeDisplay> activeDisplays = new ArrayList<>();

    void tickExisting() {
        Iterator<WhiteFadeDisplay> iterator = this.activeDisplays.iterator();
        while (iterator.hasNext()) {
            WhiteFadeDisplay display = iterator.next();
            Entity entity = display.world().getEntity(display.entityId());
            if (!(entity instanceof DisplayEntity.BlockDisplayEntity blockDisplay)) {
                iterator.remove();
                continue;
            }

            long age = display.world().getTime() - display.startedAt();
            if (age >= FADE_TICKS) {
                blockDisplay.discard();
                iterator.remove();
                continue;
            }

            this.applyFadeTransform(blockDisplay, age);
        }
    }

    void spawn(ServerWorld world, BlockPos pos) {
        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world, SpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        ((BlockDisplayEntityAccessor) display).gbreak$setBlockState(Blocks.WHITE_CONCRETE.getDefaultState());
        display.setPosition(pos.getX(), pos.getY(), pos.getZ());
        display.setInterpolationDuration(INTERPOLATION_TICKS);
        display.setTeleportDuration(INTERPOLATION_TICKS);
        display.setNoGravity(true);
        display.setSilent(true);
        display.setInvulnerable(true);
        this.applyFadeTransform(display, 0L);
        world.spawnEntity(display);
        this.activeDisplays.add(new WhiteFadeDisplay(world, display.getUuid(), world.getTime()));
    }

    int clear() {
        int removed = 0;
        for (WhiteFadeDisplay display : this.activeDisplays) {
            Entity entity = display.world().getEntity(display.entityId());
            if (entity != null) {
                entity.discard();
                removed++;
            }
        }
        this.activeDisplays.clear();
        return removed;
    }

    private void applyFadeTransform(DisplayEntity.BlockDisplayEntity display, long age) {
        float progress = Math.min(1.0F, (float) age / (float) FADE_TICKS);
        float remaining = 1.0F - progress;
        float scale = Math.max(MIN_SCALE, START_SCALE * remaining * remaining);
        float offset = (1.0F - scale) * 0.5F;
        display.setTransformation(new AffineTransformation(
                new Vector3f(offset, offset, offset),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));
    }

    private record WhiteFadeDisplay(ServerWorld world, UUID entityId, long startedAt) {}
}
