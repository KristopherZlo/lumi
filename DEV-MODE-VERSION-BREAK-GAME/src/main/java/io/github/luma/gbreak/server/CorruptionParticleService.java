package io.github.luma.gbreak.server;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

final class CorruptionParticleService {

    private static final int HORIZONTAL_RADIUS = 42;
    private static final int VERTICAL_RADIUS = 18;
    private static final int BURSTS_PER_TICK = 14;

    void tick(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < BURSTS_PER_TICK; index++) {
            double x = player.getX() + random.nextDouble(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS);
            double y = player.getY() + random.nextDouble(-4.0D, VERTICAL_RADIUS);
            double z = player.getZ() + random.nextDouble(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS);
            if (index % 3 == 0) {
                world.spawnParticles(ParticleTypes.ASH, x, y, z, 4, 1.6D, 1.0D, 1.6D, 0.01D);
            } else {
                world.spawnParticles(ParticleTypes.WHITE_ASH, x, y, z, 6, 2.4D, 1.4D, 2.4D, 0.012D);
            }
        }
    }
}
