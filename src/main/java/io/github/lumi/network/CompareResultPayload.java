package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded immutable result for one asynchronous Compare request. */
public record CompareResultPayload(
        UUID requestId,
        String dimensionId,
        CommitId before,
        CommitId after,
        int changedSections,
        int changedEntityChunks,
        List<ChangedSection> sectionPreview,
        List<Material> materials,
        String error) implements CustomPacketPayload {
    private static final int MAX_TEXT_BYTES = 1024;
    private static final int MAX_MATERIALS = 128;
    private static final int MAX_PREVIEW_SECTIONS = 512;
    public static final Type<CompareResultPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "compare_result"));
    public static final StreamCodec<FriendlyByteBuf, CompareResultPayload> CODEC =
            CustomPacketPayload.codec(CompareResultPayload::write, CompareResultPayload::read);

    public CompareResultPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        sectionPreview = List.copyOf(
                Objects.requireNonNull(sectionPreview, "sectionPreview"));
        materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        Objects.requireNonNull(error, "error");
        if (dimensionId.isBlank() || changedSections < 0 || changedEntityChunks < 0
                || sectionPreview.size() > MAX_PREVIEW_SECTIONS
                || sectionPreview.size() > changedSections
                || materials.size() > MAX_MATERIALS) {
            throw new IllegalArgumentException("Invalid Compare result");
        }
    }

    public CompareResultPayload(
            UUID requestId,
            String dimensionId,
            CommitId before,
            CommitId after,
            int changedSections,
            int changedEntityChunks,
            List<Material> materials,
            String error) {
        this(requestId, dimensionId, before, after, changedSections,
                changedEntityChunks, List.of(), materials, error);
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_TEXT_BYTES);
        buffer.writeUtf(before.hex(), ObjectId.HEX_LENGTH);
        buffer.writeUtf(after.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarInt(changedSections);
        buffer.writeVarInt(changedEntityChunks);
        buffer.writeVarInt(sectionPreview.size());
        sectionPreview.forEach(section -> section.write(buffer));
        buffer.writeVarInt(materials.size());
        materials.forEach(material -> material.write(buffer));
        buffer.writeUtf(error, MAX_TEXT_BYTES);
    }

    private static CompareResultPayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_TEXT_BYTES);
        CommitId before = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        CommitId after = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        int sections = buffer.readVarInt();
        int entities = buffer.readVarInt();
        int previewCount = buffer.readVarInt();
        if (previewCount < 0 || previewCount > MAX_PREVIEW_SECTIONS) {
            throw new IllegalArgumentException("Invalid Compare preview count");
        }
        ArrayList<ChangedSection> sectionPreview = new ArrayList<>(previewCount);
        for (int index = 0; index < previewCount; index++) {
            sectionPreview.add(ChangedSection.read(buffer));
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_MATERIALS) {
            throw new IllegalArgumentException("Invalid Compare material count");
        }
        ArrayList<Material> materials = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            materials.add(Material.read(buffer));
        }
        return new CompareResultPayload(
                request, dimension, before, after, sections, entities,
                sectionPreview, materials, buffer.readUtf(MAX_TEXT_BYTES));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record ChangedSection(int chunkX, int sectionY, int chunkZ) {
        private void write(FriendlyByteBuf buffer) {
            buffer.writeInt(chunkX);
            buffer.writeInt(sectionY);
            buffer.writeInt(chunkZ);
        }

        private static ChangedSection read(FriendlyByteBuf buffer) {
            return new ChangedSection(
                    buffer.readInt(), buffer.readInt(), buffer.readInt());
        }
    }

    public record Material(String id, long before, long after) {
        public Material {
            Objects.requireNonNull(id, "id");
            if (id.isBlank() || before < 0 || after < 0) {
                throw new IllegalArgumentException("Invalid Compare material");
            }
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUtf(id, MAX_TEXT_BYTES);
            buffer.writeVarLong(before);
            buffer.writeVarLong(after);
        }

        private static Material read(FriendlyByteBuf buffer) {
            return new Material(
                    buffer.readUtf(MAX_TEXT_BYTES),
                    buffer.readVarLong(),
                    buffer.readVarLong());
        }
    }
}
