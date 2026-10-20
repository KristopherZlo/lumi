package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.AxiomHistoryMode;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Effective startup policy and the administrator's durable next-start selection. */
public record AxiomHistorySettingsPayload(AxiomHistoryMode effective, AxiomHistoryMode selected,
        long revision, boolean configurable) implements CustomPacketPayload {
    public static final Type<AxiomHistorySettingsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "axiom_history_settings"));
    public static final StreamCodec<FriendlyByteBuf, AxiomHistorySettingsPayload> CODEC =
            CustomPacketPayload.codec(AxiomHistorySettingsPayload::write, AxiomHistorySettingsPayload::read);
    public AxiomHistorySettingsPayload {
        Objects.requireNonNull(effective); Objects.requireNonNull(selected);
        if (revision < 0) throw new IllegalArgumentException("Invalid Axiom settings revision");
    }
    private void write(FriendlyByteBuf output) {
        output.writeEnum(effective); output.writeEnum(selected);
        output.writeLong(revision); output.writeBoolean(configurable);
    }
    private static AxiomHistorySettingsPayload read(FriendlyByteBuf input) {
        return new AxiomHistorySettingsPayload(input.readEnum(AxiomHistoryMode.class),
                input.readEnum(AxiomHistoryMode.class), input.readLong(), input.readBoolean());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
