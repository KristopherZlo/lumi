package io.github.lumi.client.state;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.Component;

/** Bounded transient feedback rendered by the client HUD. */
public final class ClientNotificationStore {
    private static final int MAX_NOTIFICATIONS = 3;
    private static final long TTL_NANOS = Duration.ofSeconds(5).toNanos();
    private final ArrayDeque<Notification> notifications = new ArrayDeque<>();
    private final LongSupplier clock;

    public ClientNotificationStore() {
        this(System::nanoTime);
    }

    ClientNotificationStore(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void add(Component message, int argb) {
        long now = clock.getAsLong();
        removeExpired(now);
        notifications.addLast(new Notification(
                message, argb, now + TTL_NANOS));
        while (notifications.size() > MAX_NOTIFICATIONS) {
            notifications.removeFirst();
        }
    }

    public synchronized List<Notification> visible() {
        removeExpired(clock.getAsLong());
        return notifications.isEmpty()
                ? List.of() : List.copyOf(notifications);
    }

    public synchronized void clear() {
        notifications.clear();
    }

    private void removeExpired(long now) {
        while (!notifications.isEmpty()
                && notifications.getFirst().expiresAtNanos() <= now) {
            notifications.removeFirst();
        }
    }

    public record Notification(
            Component message, int argb, long expiresAtNanos) {
        public Notification {
            Objects.requireNonNull(message, "message");
        }
    }
}
