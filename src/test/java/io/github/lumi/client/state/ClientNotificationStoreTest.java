package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class ClientNotificationStoreTest {
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
