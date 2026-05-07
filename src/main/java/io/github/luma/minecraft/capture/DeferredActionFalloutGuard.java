package io.github.luma.minecraft.capture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/**
 * Drops delayed vanilla callbacks that still belong to a live action after
 * Lumi has started replaying that action from history.
 */
public final class DeferredActionFalloutGuard {

    private static final int SUPPRESSION_TICKS = 100;
    private static final DeferredActionFalloutGuard INSTANCE = new DeferredActionFalloutGuard();

    private final Map<String, Long> suppressedActionExpirations = new HashMap<>();

    private DeferredActionFalloutGuard() {
    }

    public static DeferredActionFalloutGuard getInstance() {
        return INSTANCE;
    }

    public synchronized void suppressAction(String actionId, long gameTime) {
        if (actionId == null || actionId.isBlank()) {
            return;
        }
        this.removeExpired(gameTime);
        this.suppressedActionExpirations.put(actionId, gameTime + SUPPRESSION_TICKS);
    }

    public boolean shouldSuppressCurrent(ServerLevel level) {
        if (level == null) {
            return false;
        }
        return this.shouldSuppress(WorldMutationContext.currentActionId(), level.getGameTime());
    }

    synchronized boolean shouldSuppress(String actionId, long gameTime) {
        if (actionId == null || actionId.isBlank()) {
            return false;
        }
        this.removeExpired(gameTime);
        Long expiresAt = this.suppressedActionExpirations.get(actionId);
        return expiresAt != null && expiresAt >= gameTime;
    }

    synchronized void clear() {
        this.suppressedActionExpirations.clear();
    }

    private void removeExpired(long gameTime) {
        Iterator<Map.Entry<String, Long>> iterator = this.suppressedActionExpirations.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < gameTime) {
                iterator.remove();
            }
        }
    }
}
