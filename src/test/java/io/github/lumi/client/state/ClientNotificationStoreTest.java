package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class ClientNotificationStoreTest {
    @Test
    void groupsOnlyConsecutiveSuccessfulStepsOfTheSameKind() {
        AtomicLong clock = new AtomicLong();
        var store = new ClientNotificationStore(clock::get);
        var undo = java.util.Optional.of(new ClientHistoryStore.Request(
                io.github.lumi.network.HistoryCommandPayload.Kind.UNDO, ""));
        store.add(Component.literal("Done"), 0xff00ff00, undo, false);
        clock.set(400_000_000L);
        store.add(Component.literal("Done"), 0xff00ff00, undo, false);
        assertEquals(1, store.visible().size());
        assertEquals(2, store.visible().getFirst().repetitions());
        store.add(Component.literal("Blocked"), 0xffff0000, undo, true);
        store.add(Component.literal("Done"), 0xff00ff00, undo, false);
        assertEquals(3, store.visible().size());
        assertEquals(1, store.visible().getLast().repetitions());
        clock.set(3_000_000_000L);
        store.add(Component.literal("Done"), 0xff00ff00, undo, false);
        assertEquals(1, store.visible().getLast().repetitions());
    }

    @Test
    void boundsExpiresAndClearsFeedback() {
        AtomicLong clock = new AtomicLong();
        ClientNotificationStore store =
                new ClientNotificationStore(clock::get);
        for (int index = 0; index < 4; index++) {
            store.add(Component.literal("message-" + index), index);
        }

        assertEquals(
                java.util.List.of("message-1", "message-2", "message-3"),
                store.visible().stream()
                        .map(value -> value.message().getString()).toList());

        clock.set(Duration.ofSeconds(5).toNanos());
        assertTrue(store.visible().isEmpty());
        store.add(Component.literal("last"), 0xffffffff);
        store.clear();
        assertTrue(store.visible().isEmpty());
    }
}
