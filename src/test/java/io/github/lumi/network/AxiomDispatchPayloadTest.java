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
        var entry = new io.github.lumi.domain.model.WorldActionView.Entry(UUID.randomUUID(),
                io.github.lumi.domain.model.WorldActionView.Kind.BRANCH_SWITCH,
                java.util.Optional.of(new io.github.lumi.domain.model.BlockPosition(-16, 4, 27)));
        var payload = new WorldActionViewPayload("minecraft:overworld", UUID.randomUUID(),
                new io.github.lumi.domain.model.WorldActionView(7, List.of(entry), List.of()));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            WorldActionViewPayload.CODEC.encode(buffer, payload);
            assertEquals(payload, WorldActionViewPayload.CODEC.decode(buffer));
            buffer.clear();
            buffer.writeUtf("minecraft:overworld"); buffer.writeUUID(payload.epoch()); buffer.writeLong(7);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> WorldActionViewPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void rejectsMissingOrUnboundedDispatchIdentity() {
        for (String dimension : List.of("", " ", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> new AxiomDispatchPayload(UUID.randomUUID(), dimension, false));
        }
        assertThrows(NullPointerException.class, () -> new AxiomDispatchPayload(null, "minecraft:overworld", false));
    }
}
