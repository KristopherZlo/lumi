package io.github.luma.gbreak.server;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

final class TimeJitterService {

    private static final int MIN_DELAY_TICKS = 20;
    private static final int MAX_DELAY_TICKS = 60;
    private static final long DAY_LENGTH_TICKS = 24_000L;

    private int nextJitterAt;

    void tick(MinecraftServer server) {
        int tick = server.getTicks();
        if (tick < this.nextJitterAt) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        long timeOfDay = random.nextLong(DAY_LENGTH_TICKS);
        for (ServerWorld world : server.getWorlds()) {
            world.setTimeOfDay(timeOfDay);
        }
        this.nextJitterAt = tick + random.nextInt(MIN_DELAY_TICKS, MAX_DELAY_TICKS + 1);
    }

    void reset() {
        this.nextJitterAt = 0;
    }
}
