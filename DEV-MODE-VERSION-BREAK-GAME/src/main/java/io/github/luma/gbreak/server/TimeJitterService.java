package io.github.luma.gbreak.server;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

final class TimeJitterService {

    private static final int MIN_DELAY_TICKS = 20;
    private static final int MAX_DELAY_TICKS = 60;
    private static final long MIN_TIME_DELTA_TICKS = -2_000L;
    private static final long MAX_TIME_DELTA_TICKS = 2_000L;

    private int nextJitterAt;

    void tick(MinecraftServer server) {
        int tick = server.getTicks();
        if (tick < this.nextJitterAt) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        long timeDelta = random.nextLong(MIN_TIME_DELTA_TICKS, MAX_TIME_DELTA_TICKS + 1L);
        for (ServerWorld world : server.getWorlds()) {
            world.setTimeOfDay(world.getTimeOfDay() + timeDelta);
        }
        this.spawnTimeFlash(server, random);
        this.nextJitterAt = tick + random.nextInt(MIN_DELAY_TICKS, MAX_DELAY_TICKS + 1);
    }

    void reset() {
        this.nextJitterAt = 0;
    }

    private void spawnTimeFlash(MinecraftServer server, ThreadLocalRandom random) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getEntityWorld();
            world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2D, player.getZ(), 18, 1.8D, 1.1D, 1.8D, 0.04D);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0D, player.getZ(), 28, 2.4D, 1.4D, 2.4D, 0.06D);
            if (random.nextBoolean()) {
                world.spawnParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 1.4D, player.getZ(), 12, 1.2D, 0.8D, 1.2D, 0.03D);
            }
        }
    }
}
