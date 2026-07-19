package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.network.SurvivalSettingsPayload;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientSurvivalSettingsStoreTest {
    @Test
    void acceptsOnlyTheLatestCorrelatedResponse() {
        ClientSurvivalSettingsStore store =
                new ClientSurvivalSettingsStore();
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        store.begin(old);
        store.begin(current);

        assertFalse(store.accept(
                new SurvivalSettingsPayload(old, true, true)));
        assertTrue(store.accept(
                new SurvivalSettingsPayload(current, true, true)));
        assertTrue(store.snapshot().orElseThrow().enabled());
        assertTrue(store.snapshot().orElseThrow().configurable());
    }
}
