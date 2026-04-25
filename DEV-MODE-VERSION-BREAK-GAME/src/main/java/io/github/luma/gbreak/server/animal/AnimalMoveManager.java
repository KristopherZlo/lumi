package io.github.luma.gbreak.server.animal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

public final class AnimalMoveManager {

    private final Map<AnimalMoveKey, AnimalMoveSession> sessions = new HashMap<>();

    public int start(ServerWorld world, Collection<? extends AnimalEntity> animals, AnimalMovePlan plan) {
        int started = 0;
        for (AnimalEntity animal : animals) {
            if (!animal.isAlive()) {
                continue;
            }

            AnimalMoveKey key = new AnimalMoveKey(world.getRegistryKey(), animal.getUuid());
            this.sessions.put(key, new AnimalMoveSession(key, plan));
            started++;
        }
        return started;
    }

    public void tick(MinecraftServer server) {
        int serverTick = server.getTicks();
        Iterator<AnimalMoveSession> iterator = this.sessions.values().iterator();
        while (iterator.hasNext()) {
            AnimalMoveSession session = iterator.next();
            if (!session.tick(server, serverTick)) {
                iterator.remove();
            }
        }
    }

    public int stop(ServerWorld world, Set<UUID> animalIds) {
        return this.stopMatching(world, key -> animalIds.contains(key.animalId()));
    }

    public int stopAll(ServerWorld world) {
        return this.stopMatching(world, key -> true);
    }

    public int activeSessionCount(ServerWorld world) {
        int count = 0;
        for (AnimalMoveKey key : this.sessions.keySet()) {
            if (key.worldKey().equals(world.getRegistryKey())) {
                count++;
            }
        }
        return count;
    }

    public void shutdown(MinecraftServer server) {
        for (AnimalMoveSession session : this.sessions.values()) {
            session.stop(server);
        }
        this.sessions.clear();
    }

    private int stopMatching(ServerWorld world, Predicate<AnimalMoveKey> predicate) {
        int stopped = 0;
        Iterator<Map.Entry<AnimalMoveKey, AnimalMoveSession>> iterator = this.sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AnimalMoveKey, AnimalMoveSession> entry = iterator.next();
            AnimalMoveKey key = entry.getKey();
            if (key.worldKey().equals(world.getRegistryKey()) && predicate.test(key)) {
                entry.getValue().stop(world.getServer());
                iterator.remove();
                stopped++;
            }
        }
        return stopped;
    }
}
