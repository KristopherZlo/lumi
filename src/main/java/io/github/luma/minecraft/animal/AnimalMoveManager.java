package io.github.luma.minecraft.animal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;

public final class AnimalMoveManager {

    private static final AnimalMoveManager INSTANCE = new AnimalMoveManager();

    private final Map<AnimalMoveKey, AnimalMoveSession> sessions = new HashMap<>();

    private AnimalMoveManager() {
    }

    public static AnimalMoveManager getInstance() {
        return INSTANCE;
    }

    public int start(ServerLevel level, Collection<? extends Animal> animals, AnimalMovePlan plan) {
        int started = 0;
        for (Animal animal : animals) {
            if (!animal.isAlive()) {
                continue;
            }
            AnimalMoveKey key = new AnimalMoveKey(level.dimension(), animal.getUUID());
            this.sessions.put(key, new AnimalMoveSession(key, plan));
            started++;
        }
        return started;
    }

    public void tick(MinecraftServer server) {
        int serverTick = server.getTickCount();
        Iterator<AnimalMoveSession> iterator = this.sessions.values().iterator();
        while (iterator.hasNext()) {
            AnimalMoveSession session = iterator.next();
            if (!session.tick(server, serverTick)) {
                iterator.remove();
            }
        }
    }

    public int stop(ServerLevel level, Set<UUID> animalIds) {
        return this.stopMatching(level, key -> animalIds.contains(key.animalId()));
    }

    public int stopAll(ServerLevel level) {
        return this.stopMatching(level, key -> true);
    }

    public int activeSessionCount(ServerLevel level) {
        int count = 0;
        for (AnimalMoveKey key : this.sessions.keySet()) {
            if (key.levelKey().equals(level.dimension())) {
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

    private int stopMatching(ServerLevel level, Predicate<AnimalMoveKey> predicate) {
        int stopped = 0;
        Iterator<Map.Entry<AnimalMoveKey, AnimalMoveSession>> iterator = this.sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AnimalMoveKey, AnimalMoveSession> entry = iterator.next();
            AnimalMoveKey key = entry.getKey();
            if (key.levelKey().equals(level.dimension()) && predicate.test(key)) {
                entry.getValue().stop(level.getServer());
                iterator.remove();
                stopped++;
            }
        }
        return stopped;
    }
}
