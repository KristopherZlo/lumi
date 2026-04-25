package io.github.luma.gbreak.server.animal;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

final class AnimalMoveSession {

    private static final double RUN_SPEED = 1.45D;
    private static final double ARRIVAL_HORIZONTAL_DISTANCE_SQUARED = 2.25D;
    private static final double ARRIVAL_VERTICAL_DISTANCE = 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 10;

    private final AnimalMoveKey key;
    private final AnimalMovePlan plan;
    private boolean returning;
    private int lastPathTick = Integer.MIN_VALUE;

    AnimalMoveSession(AnimalMoveKey key, AnimalMovePlan plan) {
        this.key = key;
        this.plan = plan;
    }

    boolean tick(MinecraftServer server, int serverTick) {
        ServerWorld world = server.getWorld(this.key.worldKey());
        if (world == null) {
            return false;
        }

        Entity entity = world.getEntity(this.key.animalId());
        if (!(entity instanceof AnimalEntity animal) || !animal.isAlive()) {
            return false;
        }

        if (this.hasArrived(animal) && !this.advanceTarget()) {
            this.release(animal);
            return false;
        }

        this.drive(animal, serverTick);
        return true;
    }

    void stop(MinecraftServer server) {
        ServerWorld world = server.getWorld(this.key.worldKey());
        if (world == null) {
            return;
        }

        Entity entity = world.getEntity(this.key.animalId());
        if (entity instanceof AnimalEntity animal) {
            this.release(animal);
        }
    }

    private boolean advanceTarget() {
        if (this.returning) {
            if (!this.plan.loop()) {
                return false;
            }
            this.returning = false;
        } else if (this.plan.returnPosition().isPresent()) {
            this.returning = true;
        } else {
            return false;
        }

        this.lastPathTick = Integer.MIN_VALUE;
        return true;
    }

    private void drive(AnimalEntity animal, int serverTick) {
        Vec3d target = this.currentTarget();
        animal.setSprinting(true);
        animal.getNavigation().setSpeed(RUN_SPEED);
        if (serverTick - this.lastPathTick >= REPATH_INTERVAL_TICKS || animal.getNavigation().isIdle()) {
            boolean pathAccepted = animal.getNavigation().startMovingTo(target.x, target.y, target.z, RUN_SPEED);
            if (!pathAccepted) {
                animal.getMoveControl().moveTo(target.x, target.y, target.z, RUN_SPEED);
            }
            this.lastPathTick = serverTick;
        }
    }

    private void release(AnimalEntity animal) {
        animal.setSprinting(false);
        animal.getNavigation().stop();
        animal.stopMovement();
    }

    private boolean hasArrived(AnimalEntity animal) {
        Vec3d target = this.currentTarget();
        double dx = animal.getX() - target.x;
        double dz = animal.getZ() - target.z;
        return dx * dx + dz * dz <= ARRIVAL_HORIZONTAL_DISTANCE_SQUARED
                && Math.abs(animal.getY() - target.y) <= ARRIVAL_VERTICAL_DISTANCE;
    }

    private Vec3d currentTarget() {
        return this.returning ? this.plan.returnPosition().orElseThrow() : this.plan.destination();
    }
}
