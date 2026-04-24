package io.github.luma.gbreak.server;

import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;

final class CorruptionParticleService {

    private static final int HORIZONTAL_RADIUS = 42;
    private static final int VERTICAL_RADIUS = 18;

    private final CorruptionSettings settings = CorruptionSettings.getInstance();

    void tick(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < this.settings.particleBurstsPerTick(); index++) {
            double x = player.getX() + random.nextDouble(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS);
            double z = player.getZ() + random.nextDouble(-HORIZONTAL_RADIUS, HORIZONTAL_RADIUS);
            if (index % 3 == 0) {
                this.spawnUpwardAsh(world, x, z, random);
            } else {
                double y = player.getY() + random.nextDouble(-4.0D, VERTICAL_RADIUS);
                world.spawnParticles(ParticleTypes.WHITE_ASH, x, y, z, 6, 2.4D, 1.4D, 2.4D, 0.012D);
            }
        }
    }

    private void spawnUpwardAsh(ServerWorld world, double x, double z, ThreadLocalRandom random) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        for (int particle = 0; particle < 3; particle++) {
            world.spawnParticles(
                    ParticleTypes.ASH,
                    blockX + random.nextDouble(0.15D, 0.85D),
                    y + random.nextDouble(0.04D, 0.28D),
                    blockZ + random.nextDouble(0.15D, 0.85D),
                    0,
                    random.nextDouble(-0.015D, 0.015D),
                    random.nextDouble(0.045D, 0.095D),
                    random.nextDouble(-0.015D, 0.015D),
                    1.0D
            );
        }
    }
}
