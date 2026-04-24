package io.github.luma.gbreak.server;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.server.MinecraftServer;
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
        this.nextJitterAt = tick + random.nextInt(MIN_DELAY_TICKS, MAX_DELAY_TICKS + 1);
    }

    void reset() {
        this.nextJitterAt = 0;
    }
}
