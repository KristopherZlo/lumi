package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.SectionChangeMask;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * Encodes and decodes section-framed block changes inside patch chunk payloads.
 */
final class PatchSectionFrameCodec {

    void writeSectionFrames(DataOutputStream output, List<StoredBlockChange> changes) throws IOException {
        Map<Integer, List<StoredBlockChange>> bySection = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            bySection.computeIfAbsent(Math.floorDiv(change.pos().y(), 16), ignored -> new ArrayList<>()).add(change);
        }

        output.writeInt(bySection.size());
        for (Map.Entry<Integer, List<StoredBlockChange>> entry : bySection.entrySet()) {
            List<StoredBlockChange> sectionChanges = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(change -> sectionLocalIndex(change.pos())))
                    .toList();
            output.writeInt(entry.getKey());
            long[] mask = this.sectionMask(sectionChanges);
            for (long word : mask) {
                output.writeLong(word);
            }

            LinkedHashMap<CompoundTag, Integer> oldStatePalette = new LinkedHashMap<>();
            LinkedHashMap<CompoundTag, Integer> newStatePalette = new LinkedHashMap<>();
            LinkedHashMap<CompoundTag, Integer> oldBlockEntityPalette = new LinkedHashMap<>();
            LinkedHashMap<CompoundTag, Integer> newBlockEntityPalette = new LinkedHashMap<>();
            for (StoredBlockChange change : sectionChanges) {
                this.paletteId(oldStatePalette, change.oldValue().stateTag());
                this.paletteId(newStatePalette, change.newValue().stateTag());
                this.paletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag());
                this.paletteId(newBlockEntityPalette, change.newValue().blockEntityTag());
            }

            this.writePalette(output, oldStatePalette);
            this.writePalette(output, newStatePalette);
            this.writePalette(output, oldBlockEntityPalette);
            this.writePalette(output, newBlockEntityPalette);
            for (StoredBlockChange change : sectionChanges) {
                output.writeInt(oldStatePalette.get(change.oldValue().stateTag()));
                output.writeInt(newStatePalette.get(change.newValue().stateTag()));
                output.writeInt(blockEntityPaletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag()));
                output.writeInt(blockEntityPaletteId(newBlockEntityPalette, change.newValue().blockEntityTag()));
            }
            for (long word : this.hiddenMask(sectionChanges)) {
                output.writeLong(word);
            }
        }
    }

    PatchSectionFrame readSectionFrame(int chunkX, int chunkZ, DataInputStream input, int version) throws IOException {
        int sectionY = input.readInt();
        long[] mask = new long[SectionChangeMask.WORD_COUNT];
        for (int index = 0; index < mask.length; index++) {
            mask[index] = input.readLong();
        }
        List<CompoundTag> oldStatePalette = this.readPalette(input);
        List<CompoundTag> newStatePalette = this.readPalette(input);
        List<CompoundTag> oldBlockEntityPalette = this.readPalette(input);
        List<CompoundTag> newBlockEntityPalette = this.readPalette(input);
        int changedCount = new SectionChangeMask(mask).cardinality();
        int[] oldStateIds = new int[changedCount];
        int[] newStateIds = new int[changedCount];
        int[] oldBlockEntityIds = new int[changedCount];
        int[] newBlockEntityIds = new int[changedCount];
        for (int index = 0; index < changedCount; index++) {
            oldStateIds[index] = input.readInt();
            newStateIds[index] = input.readInt();
            oldBlockEntityIds[index] = input.readInt();
            newBlockEntityIds[index] = input.readInt();
        }
        long[] hiddenMask = new long[SectionChangeMask.WORD_COUNT];
        if (version >= PatchDataRepository.HIDDEN_MASK_PAYLOAD_VERSION) {
            for (int index = 0; index < hiddenMask.length; index++) {
                hiddenMask[index] = input.readLong();
            }
        }
        return new PatchSectionFrame(
                chunkX,
                chunkZ,
                sectionY,
                mask,
                oldStatePalette,
                newStatePalette,
                oldStateIds,
                newStateIds,
                oldBlockEntityPalette,
                newBlockEntityPalette,
                oldBlockEntityIds,
                newBlockEntityIds,
                hiddenMask
        );
    }

    List<StoredBlockChange> toStoredChanges(PatchSectionFrame frame) throws IOException {
        List<Integer> localIndexes = new ArrayList<>();
        new SectionChangeMask(frame.changedMask()).forEachSetCell(localIndexes::add);
        int[] oldStateIds = frame.oldStateIds();
        int[] newStateIds = frame.newStateIds();
        int[] oldBlockEntityIds = frame.oldBlockEntityIds();
        int[] newBlockEntityIds = frame.newBlockEntityIds();
        long[] hiddenMask = frame.hiddenMask();
        this.requireArrayLength("old state ids", oldStateIds, localIndexes.size());
        this.requireArrayLength("new state ids", newStateIds, localIndexes.size());
        this.requireArrayLength("old block entity ids", oldBlockEntityIds, localIndexes.size());
        this.requireArrayLength("new block entity ids", newBlockEntityIds, localIndexes.size());
        List<StoredBlockChange> changes = new ArrayList<>(localIndexes.size());
        for (int index = 0; index < localIndexes.size(); index++) {
            int localIndex = localIndexes.get(index);
            BlockPoint pos = new BlockPoint(
                    (frame.chunkX() << 4) + SectionChangeMask.localX(localIndex),
                    (frame.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                    (frame.chunkZ() << 4) + SectionChangeMask.localZ(localIndex)
            );
            changes.add(new StoredBlockChange(
                    pos,
                    new StatePayload(
                            stateAt("old state palette", frame.oldStatePalette(), oldStateIds[index]).copy(),
                            blockEntityAt("old block entity palette", frame.oldBlockEntityPalette(), oldBlockEntityIds[index])
                    ),
                    new StatePayload(
                            stateAt("new state palette", frame.newStatePalette(), newStateIds[index]).copy(),
                            blockEntityAt("new block entity palette", frame.newBlockEntityPalette(), newBlockEntityIds[index])
                    ),
                    isSet(hiddenMask, localIndex)
            ));
        }
        return changes;
    }

    PatchSectionWorldChanges toSectionWorldChanges(PatchWorldChanges worldChanges) {
        Map<String, List<StoredBlockChange>> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : worldChanges.blockChanges()) {
            String key = chunkKey(change) + ":" + Math.floorDiv(change.pos().y(), 16);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(change);
        }
        List<PatchSectionFrame> frames = new ArrayList<>();
        for (List<StoredBlockChange> changes : grouped.values()) {
            List<StoredBlockChange> sorted = changes.stream()
                    .sorted(Comparator.comparingInt(change -> sectionLocalIndex(change.pos())))
                    .toList();
            StoredBlockChange first = sorted.getFirst();
            frames.add(this.toSectionFrame(
                    first.pos().x() >> 4,
                    first.pos().z() >> 4,
                    Math.floorDiv(first.pos().y(), 16),
                    sorted
            ));
        }
        return new PatchSectionWorldChanges(frames, worldChanges.entityChanges());
    }

    PatchSectionFrame toSectionFrame(
            int chunkX,
            int chunkZ,
            int sectionY,
            List<StoredBlockChange> sectionChanges
    ) {
        LinkedHashMap<CompoundTag, Integer> oldStatePalette = new LinkedHashMap<>();
        LinkedHashMap<CompoundTag, Integer> newStatePalette = new LinkedHashMap<>();
        LinkedHashMap<CompoundTag, Integer> oldBlockEntityPalette = new LinkedHashMap<>();
        LinkedHashMap<CompoundTag, Integer> newBlockEntityPalette = new LinkedHashMap<>();
        int[] oldStateIds = new int[sectionChanges.size()];
        int[] newStateIds = new int[sectionChanges.size()];
        int[] oldBlockEntityIds = new int[sectionChanges.size()];
        int[] newBlockEntityIds = new int[sectionChanges.size()];
        for (int index = 0; index < sectionChanges.size(); index++) {
            StoredBlockChange change = sectionChanges.get(index);
            oldStateIds[index] = this.paletteId(oldStatePalette, change.oldValue().stateTag());
            newStateIds[index] = this.paletteId(newStatePalette, change.newValue().stateTag());
            this.paletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag());
            this.paletteId(newBlockEntityPalette, change.newValue().blockEntityTag());
            oldBlockEntityIds[index] = blockEntityPaletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag());
            newBlockEntityIds[index] = blockEntityPaletteId(newBlockEntityPalette, change.newValue().blockEntityTag());
        }
        return new PatchSectionFrame(
                chunkX,
                chunkZ,
                sectionY,
                this.sectionMask(sectionChanges),
                new ArrayList<>(oldStatePalette.keySet()),
                new ArrayList<>(newStatePalette.keySet()),
                oldStateIds,
                newStateIds,
                new ArrayList<>(oldBlockEntityPalette.keySet()),
                new ArrayList<>(newBlockEntityPalette.keySet()),
                oldBlockEntityIds,
                newBlockEntityIds,
                this.hiddenMask(sectionChanges)
        );
    }

    private void writePalette(DataOutputStream output, LinkedHashMap<CompoundTag, Integer> palette) throws IOException {
        output.writeInt(palette.size());
        for (CompoundTag tag : palette.keySet()) {
            StorageIo.writeCompound(output, tag);
        }
    }

    private List<CompoundTag> readPalette(DataInputStream input) throws IOException {
        int count = StorageLimits.requireLength(
                "patch palette count",
                input.readInt(),
                StorageLimits.MAX_PALETTE_ENTRIES
        );
        List<CompoundTag> palette = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            palette.add(StorageIo.readCompound(input));
        }
        return palette;
    }

    private int paletteId(LinkedHashMap<CompoundTag, Integer> palette, CompoundTag tag) {
        if (tag == null) {
            return -1;
        }
        return palette.computeIfAbsent(tag.copy(), ignored -> palette.size());
    }

    private int blockEntityPaletteId(LinkedHashMap<CompoundTag, Integer> palette, CompoundTag tag) {
        return tag == null ? -1 : palette.get(tag);
    }

    private CompoundTag stateAt(String label, List<CompoundTag> palette, int id) throws IOException {
        if (id < 0 || id >= palette.size()) {
            throw new IOException("Invalid " + label + " id " + id);
        }
        return palette.get(id);
    }

    private CompoundTag blockEntityAt(String label, List<CompoundTag> palette, int id) throws IOException {
        if (id < 0) {
            return null;
        }
        if (id >= palette.size()) {
            throw new IOException("Invalid " + label + " id " + id);
        }
        return palette.get(id).copy();
    }

    private void requireArrayLength(String label, int[] values, int expectedLength) throws IOException {
        if (values.length != expectedLength) {
            throw new IOException("Patch section " + label + " length mismatch");
        }
    }

    private long[] sectionMask(List<StoredBlockChange> sectionChanges) {
        SectionChangeMask.Builder builder = SectionChangeMask.builder();
        for (StoredBlockChange change : sectionChanges) {
            builder.set(sectionLocalIndex(change.pos()));
        }
        return builder.build().words();
    }

    private long[] hiddenMask(List<StoredBlockChange> sectionChanges) {
        SectionChangeMask.Builder builder = SectionChangeMask.builder();
        for (StoredBlockChange change : sectionChanges) {
            if (change.hidden()) {
                builder.set(sectionLocalIndex(change.pos()));
            }
        }
        return builder.build().words();
    }

    private static boolean isSet(long[] mask, int localIndex) {
        if (mask == null || localIndex < 0) {
            return false;
        }
        int wordIndex = localIndex >>> 6;
        if (wordIndex >= mask.length) {
            return false;
        }
        return (mask[wordIndex] & (1L << (localIndex & 63))) != 0L;
    }

    private static int sectionLocalIndex(BlockPoint pos) {
        return SectionChangeMask.localIndex(pos.x() & 15, pos.y() & 15, pos.z() & 15);
    }

    private static String chunkKey(StoredBlockChange change) {
        return (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
    }
}
