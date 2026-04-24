package io.github.luma.gbreak.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

final class RisingEntityService {

    private static final int HORIZONTAL_RADIUS = 28;
    private static final int MIN_HEIGHT_OFFSET = 3;
    private static final int MAX_HEIGHT_OFFSET = 18;
    private static final int MAX_ACTIVE = 42;
    private static final int MIN_SPAWN_DELAY_TICKS = 18;
    private static final int MAX_SPAWN_DELAY_TICKS = 44;
    private static final int MIN_BATCH_SIZE = 2;
    private static final int MAX_BATCH_SIZE = 6;
    private static final List<EntityType<? extends Entity>> MOB_TYPES = List.of(
            EntityType.BAT,
            EntityType.ALLAY,
            EntityType.BEE,
            EntityType.PARROT,
            EntityType.PHANTOM,
            EntityType.BLAZE,
            EntityType.GHAST
    );
    private static final List<Item> FLOATING_ITEMS = List.of(
            Items.AMETHYST_SHARD,
            Items.CLOCK,
            Items.COMPASS,
            Items.DIAMOND,
            Items.ENDER_PEARL,
            Items.FEATHER,
            Items.GLOWSTONE_DUST,
            Items.MAGMA_CREAM
    );

    private final List<RisingEntity> activeEntities = new ArrayList<>();

    private long nextSpawnAt;

    void tick(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        this.tickExisting(random);
        if (this.activeEntities.size() >= MAX_ACTIVE || world.getTime() < this.nextSpawnAt) {
            return;
        }

        this.nextSpawnAt = world.getTime() + random.nextInt(MIN_SPAWN_DELAY_TICKS, MAX_SPAWN_DELAY_TICKS + 1);
        int batchSize = random.nextInt(MIN_BATCH_SIZE, MAX_BATCH_SIZE + 1);
        for (int index = 0; index < batchSize && this.activeEntities.size() < MAX_ACTIVE; index++) {
            this.spawn(world, this.randomPosition(player, random), random);
        }
    }

    int clear() {
        int removed = 0;
        for (RisingEntity risingEntity : this.activeEntities) {
            Entity entity = risingEntity.world().getEntity(risingEntity.entityId());
            if (entity != null) {
                entity.discard();
                removed++;
            }
        }
        this.activeEntities.clear();
        this.nextSpawnAt = 0L;
        return removed;
    }

    private void tickExisting(ThreadLocalRandom random) {
        Iterator<RisingEntity> iterator = this.activeEntities.iterator();
        while (iterator.hasNext()) {
            RisingEntity risingEntity = iterator.next();
            Entity entity = risingEntity.world().getEntity(risingEntity.entityId());
            if (entity == null) {
                iterator.remove();
                continue;
            }
            if (risingEntity.world().getTime() >= risingEntity.expiresAt()) {
                entity.discard();
                iterator.remove();
                continue;
            }

            entity.setVelocity(
                    risingEntity.driftX() + random.nextDouble(-0.006D, 0.006D),
                    risingEntity.verticalSpeed(),
                    risingEntity.driftZ() + random.nextDouble(-0.006D, 0.006D)
            );
        }
    }

    private void spawn(ServerWorld world, Vec3d pos, ThreadLocalRandom random) {
        Entity entity = random.nextInt(100) < 72
                ? this.createMob(world, random)
                : this.createNonMob(world, pos, random);
        if (entity == null) {
            return;
        }

        entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, random.nextFloat() * 360.0F, random.nextFloat() * 30.0F);
        entity.setNoGravity(true);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setGlowing(random.nextInt(100) < 35);
        world.spawnEntity(entity);
        this.activeEntities.add(new RisingEntity(
                world,
                entity.getUuid(),
                world.getTime() + random.nextInt(120, 241),
                random.nextDouble(0.035D, 0.085D),
                random.nextDouble(-0.012D, 0.012D),
                random.nextDouble(-0.012D, 0.012D)
        ));
    }

    private Entity createMob(ServerWorld world, ThreadLocalRandom random) {
        Entity entity = MOB_TYPES.get(random.nextInt(MOB_TYPES.size())).create(world, SpawnReason.COMMAND);
        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true);
            mob.setPersistent();
        }
        return entity;
    }

    private Entity createNonMob(ServerWorld world, Vec3d pos, ThreadLocalRandom random) {
        if (random.nextBoolean()) {
            Item item = FLOATING_ITEMS.get(random.nextInt(FLOATING_ITEMS.size()));
            ItemEntity entity = new ItemEntity(world, pos.x, pos.y, pos.z, new ItemStack(item));
            entity.setPickupDelayInfinite();
            return entity;
        }
        return new ExperienceOrbEntity(world, pos.x, pos.y, pos.z, random.nextInt(1, 8));
    }

    private Vec3d randomPosition(ServerPlayerEntity player, ThreadLocalRandom random) {
        double x = player.getX() + random.nextDouble(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS);
        double y = player.getY() + random.nextInt(MIN_HEIGHT_OFFSET, MAX_HEIGHT_OFFSET + 1);
        double z = player.getZ() + random.nextDouble(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS);
        return new Vec3d(x, y, z);
    }

    private record RisingEntity(
            ServerWorld world,
            UUID entityId,
            long expiresAt,
            double verticalSpeed,
            double driftX,
            double driftZ
    ) {
    }
}
