package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class PatchDataRepository {

    private static final int MAGIC = 0x4C504154;
    private static final int VERSION = 3;

    public PatchMetadata writePayload(
            ProjectLayout layout,
            String patchId,
            String projectId,
            String versionId,
            List<StoredBlockChange> changes
    ) throws IOException {
        Map<String, List<StoredBlockChange>> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            String key = chunkKey(change);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(change);
        }

        List<Map.Entry<String, List<StoredBlockChange>>> sortedChunks = new ArrayList<>(grouped.entrySet());
        sortedChunks.sort(Comparator.comparing(Map.Entry<String, List<StoredBlockChange>>::getKey));
        LumaMod.LOGGER.info(
                "Writing patch payload {} with {} changes across {} chunks",
                patchId,
                changes.size(),
                sortedChunks.size()
        );

        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
        List<PatchChunkSlice> slices = new ArrayList<>();
        try (DataOutputStream payload = new DataOutputStream(payloadBuffer)) {
            payload.writeInt(MAGIC);
            payload.writeInt(VERSION);
            payload.writeInt(sortedChunks.size());

            int chunkIndex = 0;
            for (Map.Entry<String, List<StoredBlockChange>> entry : sortedChunks) {
                String[] split = entry.getKey().split(":", 2);
                int chunkX = Integer.parseInt(split[0]);
                int chunkZ = Integer.parseInt(split[1]);

                List<StoredBlockChange> chunkChanges = new ArrayList<>(entry.getValue());
                chunkChanges.sort(Comparator.comparingInt(change -> packLocalPosition(change.pos())));

                byte[] chunkBytes = this.writeChunk(chunkX, chunkZ, chunkChanges);
                long offset = payloadBuffer.size();
                payload.write(chunkBytes);
                slices.add(new PatchChunkSlice(chunkX, chunkZ, chunkChanges.size(), offset, chunkBytes.length));
                chunkIndex += 1;
                BackgroundThrottle.pauseEvery(chunkIndex, 8, 250_000L);
            }
        }

        StorageIo.writeAtomically(layout.patchDataFile(patchId), output -> this.writeCompressed(output, payloadBuffer.toByteArray()));
        return new PatchMetadata(
                patchId,
                projectId,
                versionId,
                layout.patchDataFile(patchId).getFileName().toString(),
                List.copyOf(slices),
                new PatchStats(changes.size(), slices.size())
        );
    }

    public List<StoredBlockChange> loadChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        if (metadata == null) {
            return List.of();
        }

        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(layout.patchDataFile(metadata.id())))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported patch payload format for " + metadata.id());
            }

            int chunkCount = input.readInt();
            List<StoredBlockChange> changes = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                changes.addAll(this.readChunk(input));
                BackgroundThrottle.pauseEvery(index + 1, 8, 250_000L);
            }
            return changes;
        }
    }

    public List<PreparedChunkBatch> decodeBatches(ProjectLayout layout, PatchMetadata metadata, ServerLevel level) throws IOException {
        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : this.loadChanges(layout, metadata)) {
            ChunkPoint chunk = new ChunkPoint(change.pos().x() >> 4, change.pos().z() >> 4);
            grouped.computeIfAbsent(chunk, ignored -> new ArrayList<>())
                    .add(new PreparedBlockPlacement(
                            new BlockPos(change.pos().x(), change.pos().y(), change.pos().z()),
                            io.github.luma.minecraft.world.BlockStateNbtCodec.deserializeBlockState(level, change.newValue().stateTag()),
                            change.newValue().blockEntityTag() == null ? null : change.newValue().blockEntityTag().copy()
                    ));
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (Map.Entry<ChunkPoint, List<PreparedBlockPlacement>> entry : grouped.entrySet()) {
            batches.add(new PreparedChunkBatch(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return batches;
    }

    private byte[] writeChunk(int chunkX, int chunkZ, List<StoredBlockChange> changes) throws IOException {
        ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(chunkBuffer)) {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            output.writeInt(changes.size());

            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> statePalette = new LinkedHashMap<>();
            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> blockEntityPalette = new LinkedHashMap<>();
            for (StoredBlockChange change : changes) {
                this.paletteId(statePalette, change.oldValue().stateTag());
                this.paletteId(statePalette, change.newValue().stateTag());
                this.paletteId(blockEntityPalette, change.oldValue().blockEntityTag());
                this.paletteId(blockEntityPalette, change.newValue().blockEntityTag());
            }

            output.writeInt(statePalette.size());
            for (net.minecraft.nbt.CompoundTag tag : statePalette.keySet()) {
                StorageIo.writeCompound(output, tag);
            }
            output.writeInt(blockEntityPalette.size());
            for (net.minecraft.nbt.CompoundTag tag : blockEntityPalette.keySet()) {
                StorageIo.writeCompound(output, tag);
            }

            for (StoredBlockChange change : changes) {
                output.writeInt(packLocalPosition(change.pos()));
                output.writeInt(statePalette.get(change.oldValue().stateTag()));
                output.writeInt(statePalette.get(change.newValue().stateTag()));
                output.writeInt(blockEntityPaletteId(blockEntityPalette, change.oldValue().blockEntityTag()));
                output.writeInt(blockEntityPaletteId(blockEntityPalette, change.newValue().blockEntityTag()));
            }
        }
        return chunkBuffer.toByteArray();
    }

    private List<StoredBlockChange> readChunk(DataInputStream input) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int changeCount = input.readInt();

        List<net.minecraft.nbt.CompoundTag> statePalette = new ArrayList<>();
        int statePaletteCount = input.readInt();
        for (int index = 0; index < statePaletteCount; index++) {
            statePalette.add(StorageIo.readCompound(input));
        }

        List<net.minecraft.nbt.CompoundTag> blockEntityPalette = new ArrayList<>();
        int blockEntityPaletteCount = input.readInt();
        for (int index = 0; index < blockEntityPaletteCount; index++) {
            blockEntityPalette.add(StorageIo.readCompound(input));
        }

        List<StoredBlockChange> changes = new ArrayList<>();
        for (int index = 0; index < changeCount; index++) {
            int packed = input.readInt();
            int oldStateId = input.readInt();
            int newStateId = input.readInt();
            int oldBlockEntityId = input.readInt();
            int newBlockEntityId = input.readInt();
            BlockPoint pos = unpackPosition(chunkX, chunkZ, packed);
            changes.add(new StoredBlockChange(
                    pos,
                    new StatePayload(statePalette.get(oldStateId).copy(), blockEntityAt(blockEntityPalette, oldBlockEntityId)),
                    new StatePayload(statePalette.get(newStateId).copy(), blockEntityAt(blockEntityPalette, newBlockEntityId))
            ));
        }
        return changes;
    }

    private void writeCompressed(OutputStream output, byte[] bytes) throws IOException {
        try (LZ4FrameOutputStream compressed = new LZ4FrameOutputStream(new BufferedOutputStream(output))) {
            compressed.write(bytes);
        }
    }

    private int paletteId(LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette, net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) {
            return -1;
        }
        return palette.computeIfAbsent(tag.copy(), ignored -> palette.size());
    }

    private int blockEntityPaletteId(LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette, net.minecraft.nbt.CompoundTag tag) {
        return tag == null ? -1 : palette.get(tag);
    }

    private net.minecraft.nbt.CompoundTag blockEntityAt(List<net.minecraft.nbt.CompoundTag> palette, int id) {
        return id < 0 ? null : palette.get(id).copy();
    }

    private static String chunkKey(StoredBlockChange change) {
        return (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
    }

    private static int packLocalPosition(BlockPoint pos) {
        int normalizedY = pos.y() - Short.MIN_VALUE;
        return (normalizedY << 8) | ((pos.z() & 15) << 4) | (pos.x() & 15);
    }

    private static BlockPoint unpackPosition(int chunkX, int chunkZ, int packed) {
        int normalizedY = packed >>> 8;
        int y = normalizedY + Short.MIN_VALUE;
        int localX = packed & 15;
        int localZ = (packed >>> 4) & 15;
        return new BlockPoint((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }
}
