package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class AxiomDispatchPayloadTest {
    @Test void roundTripsTheDispatchDimensionAndBothBoundaryStates() {
        for (boolean complete : new boolean[] {false, true}) {
            var payload = new AxiomDispatchPayload(UUID.randomUUID(), "minecraft:the_nether", complete);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                AxiomDispatchPayload.CODEC.encode(buffer, payload);
                assertEquals(payload, AxiomDispatchPayload.CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test void rejectsMissingOrUnboundedDispatchIdentity() {
        for (String dimension : List.of("", " ", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> new AxiomDispatchPayload(UUID.randomUUID(), dimension, false));
        }
        assertThrows(NullPointerException.class, () -> new AxiomDispatchPayload(null, "minecraft:overworld", false));
    }
}
