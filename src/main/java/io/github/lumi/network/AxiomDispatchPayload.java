package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import io.github.lumi.domain.model.AxiomEntryIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** FIFO begin/end markers around every packet of one native Axiom dispatch. */
public record AxiomDispatchPayload(UUID dispatch, String dimension, boolean complete,
        Optional<AxiomEntryIdentity> entry, boolean entity) implements CustomPacketPayload {
    public static final Type<AxiomDispatchPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "axiom_dispatch_v2"));
    public static final StreamCodec<FriendlyByteBuf, AxiomDispatchPayload> CODEC =
            CustomPacketPayload.codec(AxiomDispatchPayload::write, AxiomDispatchPayload::read);

    public AxiomDispatchPayload {
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(entry, "entry");
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("Invalid dispatch dimension");
        }
    }

    public AxiomDispatchPayload(UUID dispatch, String dimension, boolean complete) {
        this(dispatch, dimension, complete, Optional.empty(), false);
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(dispatch);
        buffer.writeUtf(dimension, 256);
        buffer.writeBoolean(complete);
        buffer.writeBoolean(entry.isPresent());
        entry.ifPresent(value -> { buffer.writeUUID(value.epoch()); buffer.writeUUID(value.entry()); });
        buffer.writeBoolean(entity);
    }

    private static AxiomDispatchPayload read(FriendlyByteBuf buffer) {
        UUID dispatch = buffer.readUUID(); String dimension = buffer.readUtf(256); boolean complete = buffer.readBoolean();
        var entry = buffer.readBoolean() ? Optional.of(new AxiomEntryIdentity(buffer.readUUID(), buffer.readUUID())) : Optional.<AxiomEntryIdentity>empty();
        return new AxiomDispatchPayload(dispatch, dimension, complete, entry, buffer.readBoolean());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
