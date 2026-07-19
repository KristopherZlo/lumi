package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
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
                        "minecraft:stone", 10, 14)), "",
                3, false, List.of(
                        new BlockChange(16, 32, 48, BlockChange.Kind.ADDED),
                        new BlockChange(-1, 0, 9, BlockChange.Kind.REMOVED)),
                2);
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

    @Test
    void handlerMapsOneNonTerminalTypedBatch() {
        UUID request = UUID.randomUUID();
        var blocks = List.of(new BlockChange(
                4, 5, 6, BlockChange.Kind.CHANGED));

        var batch = CompareCommandHandler.blockBatch(
                request, "minecraft:overworld",
                id('1'), id('2'), 7, blocks);

        assertEquals(7, batch.batchIndex());
        assertEquals(false, batch.complete());
        assertEquals(blocks, batch.blockChanges());
    }

    @Test
    void handlerPublishesSummaryAfterTheLastBatch() {
        var summary = new ComparisonSummary(
                id('1'), id('2'), 1, 0, 3,
                List.of(new SectionKey(0, 0, 0)), Map.of());

        var result = CompareCommandHandler.success(
                UUID.randomUUID(), "minecraft:overworld", 2, summary);

        assertEquals(2, result.batchIndex());
        assertEquals(true, result.complete());
        assertEquals(3, result.changedBlocks());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
