package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ComparePayloadTest {
    @Test
    void argumentAndResultRoundTrip() {
        CompareArgument argument = new CompareArgument(id('1'), id('2'));
        assertEquals(argument, CompareArgument.parse(argument.encode()));

        CompareResultPayload result = new CompareResultPayload(
                UUID.randomUUID(), "minecraft:overworld",
                argument.before(), argument.after(), 4, 2,
                List.of(new CompareResultPayload.ChangedSection(1, 2, 3)),
                List.of(new CompareResultPayload.Material(
                        "minecraft:stone", 10, 14)), "");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CompareResultPayload.CODEC.encode(buffer, result);
            assertEquals(result, CompareResultPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsSameCommitAndInvalidCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompareArgument(id('1'), id('1')));
        assertThrows(IllegalArgumentException.class,
                () -> new CompareResultPayload(
                        UUID.randomUUID(), "minecraft:overworld",
                        id('1'), id('2'), -1, 0, List.of(), ""));
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
