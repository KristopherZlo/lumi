package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Current player's durable world-wide Survival opt-in state. */
public record SurvivalSettingsPayload(
        UUID requestId,
        boolean enabled,
        boolean configurable) implements CustomPacketPayload {
    public static final Type<SurvivalSettingsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    LumiMod.MOD_ID, "survival_settings"));
    public static final StreamCodec<FriendlyByteBuf, SurvivalSettingsPayload> CODEC =
            CustomPacketPayload.codec(
                    SurvivalSettingsPayload::write,
                    SurvivalSettingsPayload::read);

    public SurvivalSettingsPayload {
        Objects.requireNonNull(requestId, "requestId");
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeBoolean(enabled);
        buffer.writeBoolean(configurable);
    }

    private static SurvivalSettingsPayload read(FriendlyByteBuf buffer) {
        return new SurvivalSettingsPayload(
                buffer.readUUID(), buffer.readBoolean(), buffer.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
