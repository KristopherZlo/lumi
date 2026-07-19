package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class PartialRestorePlanProtocolTest {
    @Test
    void planCommandAndCorrelatedResultRoundTrip() {
        var area = new BlockAreaTarget(new BlockBox(1, 2, 3, 4, 5, 6), true);
        var argument = new PartialRestoreArgument(id('b'), area);
        var command = new HistoryCommandPayload(
                UUID.randomUUID(), HistoryCommandPayload.Kind.RESTORE_AREA_PLAN,
                argument.encode(), id('a'), 7);
        UUID previewToken = UUID.randomUUID();
        var result = new PartialRestorePlanPayload(
                command.requestId(), previewToken, "minecraft:overworld",
                id('b'), area, 3, 17, "");

        assertEquals(command, roundTrip(HistoryCommandPayload.CODEC, command));
        assertEquals(result, roundTrip(PartialRestorePlanPayload.CODEC, result));
    }

    @Test
    void serverPlansOffThreadBeforeSendingTheResult() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));
        String networking = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));

        assertTrue(runtime.contains(
                "CompletableFuture<PartialRestorePreview> planPartialRestore"));
        assertTrue(runtime.contains("restores.planPartial("));
        assertTrue(networking.contains("runtime.planPartialRestore("));
        assertTrue(networking.contains("PartialRestorePlanPayload.TYPE"));
    }

    private static <T> T roundTrip(
            net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec,
            T value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
