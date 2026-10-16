package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Connection-scoped pause/acknowledgement for external work crossing a world replacement. */
public record BuilderToolBarrierPayload(UUID barrier, String dimension, State state)
        implements CustomPacketPayload {
    public static final Type<BuilderToolBarrierPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "builder_tool_barrier"));
    public static final StreamCodec<FriendlyByteBuf, BuilderToolBarrierPayload> CODEC =
            CustomPacketPayload.codec(BuilderToolBarrierPayload::write, BuilderToolBarrierPayload::read);

    public BuilderToolBarrierPayload {
        Objects.requireNonNull(barrier, "barrier");
        Objects.requireNonNull(state, "state");
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("Invalid barrier dimension");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(barrier);
        buffer.writeUtf(dimension, 256);
        buffer.writeEnum(state);
    }

    private static BuilderToolBarrierPayload read(FriendlyByteBuf buffer) {
        return new BuilderToolBarrierPayload(buffer.readUUID(), buffer.readUtf(256), buffer.readEnum(State.class));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public enum State { PAUSE, READY, REFUSED, RESUME }
}
