package io.github.luma.minecraft.animal;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

final class AnimalMoveSession {

    private static final double RUN_SPEED = 1.45D;
    private static final double ARRIVAL_HORIZONTAL_DISTANCE_SQR = 2.25D;
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

    AnimalMoveKey key() {
        return this.key;
    }

    boolean tick(MinecraftServer server, int serverTick) {
        ServerLevel level = server.getLevel(this.key.levelKey());
        if (level == null) {
            return false;
        }
        Entity entity = level.getEntity(this.key.animalId());
        if (!(entity instanceof Animal animal) || !animal.isAlive()) {
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
        ServerLevel level = server.getLevel(this.key.levelKey());
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(this.key.animalId());
        if (entity instanceof Animal animal) {
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

    private void drive(Animal animal, int serverTick) {
        Vec3 target = this.currentTarget();
        animal.setSprinting(true);
        animal.getNavigation().setSpeedModifier(RUN_SPEED);
        if (serverTick - this.lastPathTick >= REPATH_INTERVAL_TICKS || animal.getNavigation().isDone()) {
            boolean pathAccepted = animal.getNavigation().moveTo(target.x, target.y, target.z, RUN_SPEED);
            if (!pathAccepted) {
                animal.getMoveControl().setWantedPosition(target.x, target.y, target.z, RUN_SPEED);
            }
            this.lastPathTick = serverTick;
        }
        animal.hurtMarked = true;
    }

    private void release(Animal animal) {
        animal.setSprinting(false);
        animal.getNavigation().stop();
        animal.stopInPlace();
    }

    private boolean hasArrived(Animal animal) {
        Vec3 target = this.currentTarget();
        double dx = animal.getX() - target.x;
        double dz = animal.getZ() - target.z;
        return dx * dx + dz * dz <= ARRIVAL_HORIZONTAL_DISTANCE_SQR
                && Math.abs(animal.getY() - target.y) <= ARRIVAL_VERTICAL_DISTANCE;
    }

    private Vec3 currentTarget() {
        return this.returning ? this.plan.returnPosition().orElseThrow() : this.plan.destination();
    }
}
