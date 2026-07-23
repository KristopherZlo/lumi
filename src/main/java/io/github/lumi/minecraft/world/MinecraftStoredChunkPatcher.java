package io.github.lumi.minecraft.world;

import com.mojang.serialization.Codec;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

/** Patches only Restore-owned fields in one vanilla serialized chunk. */
final class MinecraftStoredChunkPatcher {
    private final Codec<PalettedContainer<BlockState>> blockStates;
    private final int minY;
    private final int height;

    MinecraftStoredChunkPatcher(
            Codec<PalettedContainer<BlockState>> blockStates,
            int minY,
            int height) {
        this.blockStates = Objects.requireNonNull(blockStates, "blockStates");
        this.minY = minY;
        this.height = height;
    }

    Patch patch(
            ChunkPos position,
            CompoundTag source,
            Map<SectionKey, DecodedSection> target)
            throws IOException, UnsupportedChunk {
        validatePosition(position, source);
        ListTag serializedSections = source.getList("sections")
                .orElseThrow(UnsupportedChunk::new);
        Map<Integer, SectionSlot> sections = sectionSlots(serializedSections);
        Map<Integer, DecodedSection> targetByY = targetSections(position, target);
        Map<Integer, CompoundTag> replacements = new LinkedHashMap<>();
        boolean lightChanged = false;
        for (var entry : targetByY.entrySet()) {
            SectionSlot stored = sections.get(entry.getKey());
            if (stored == null || !stored.tag().contains("block_states")) {
                throw new UnsupportedChunk();
            }
            DecodedSection decoded = entry.getValue();
            CompoundTag replacement = stored.tag().copy();
            replacement.store(
                    "block_states", blockStates, decoded.copyBlockStates());
            boolean changedLight = decoded.preparedDelta().lightChanged();
            if (changedLight) {
                replacement.remove("BlockLight");
                replacement.remove("SkyLight");
            }
            lightChanged |= changedLight;
            replacements.put(entry.getKey(), replacement);
        }

        Map<Heightmap.Types, long[]> heightmaps =
                recalculateHeightmaps(source, sections, targetByY);
        ListTag blockEntities = replaceBlockEntities(
                source.getListOrEmpty("block_entities"), target);

        replacements.forEach((sectionY, replacement) ->
                serializedSections.set(sections.get(sectionY).index(), replacement));
        if (lightChanged) {
            source.remove("isLightOn");
        }
        CompoundTag serializedHeightmaps = source.getCompoundOrEmpty("Heightmaps");
        heightmaps.forEach((type, raw) ->
                serializedHeightmaps.putLongArray(type.getSerializationKey(), raw));
        if (!heightmaps.isEmpty() || source.contains("Heightmaps")) {
            source.put("Heightmaps", serializedHeightmaps);
        }
        source.put("block_entities", blockEntities);
        NbtUtils.addCurrentDataVersion(source);
        return new Patch(source, heightmaps);
    }

    private Map<Heightmap.Types, long[]> recalculateHeightmaps(
            CompoundTag source,
            Map<Integer, SectionSlot> sections,
            Map<Integer, DecodedSection> target) throws UnsupportedChunk {
        int[] highestChangedY = new int[256];
        Arrays.fill(highestChangedY, Integer.MIN_VALUE);
        target.forEach((sectionY, decoded) -> {
            for (int index : decoded.preparedDelta().heightmapIndexes()) {
                int column = (index & 15) | (((index >>> 4) & 15) << 4);
                int worldY = sectionY * 16 + ((index >>> 8) & 15);
                highestChangedY[column] = Math.max(highestChangedY[column], worldY);
            }
        });

        int bits = Mth.ceillog2(height + 1);
        CompoundTag serialized = source.getCompoundOrEmpty("Heightmaps");
        Set<Heightmap.Types> persisted = SerializableChunkData
                .getChunkStatusFromTag(source).heightmapsAfter();
        Map<Heightmap.Types, SimpleBitStorage> storages = new LinkedHashMap<>();
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (!persisted.contains(type)) {
                continue;
            }
            long[] raw = serialized.getLongArray(
                    type.getSerializationKey()).orElse(null);
            if (raw != null) {
                storages.put(type, new SimpleBitStorage(bits, 256, raw.clone()));
            }
        }

        Map<Integer, PalettedContainer<BlockState>> decodedStored = new HashMap<>();
        for (int column = 0; column < highestChangedY.length; column++) {
            int startY = highestChangedY[column];
            if (startY == Integer.MIN_VALUE) {
                continue;
            }
            List<Heightmap.Types> pending = new ArrayList<>();
            for (var entry : storages.entrySet()) {
                int currentTopY = entry.getValue().get(column) + minY - 1;
                if (currentTopY <= startY) {
                    pending.add(entry.getKey());
                }
            }
            int x = column & 15;
            int z = (column >>> 4) & 15;
            for (int y = startY; y >= minY && !pending.isEmpty(); y--) {
                BlockState state = stateAt(
                        sections, target, decodedStored, x, y, z);
                var iterator = pending.iterator();
                while (iterator.hasNext()) {
                    Heightmap.Types type = iterator.next();
                    if (type.isOpaque().test(state)) {
                        storages.get(type).set(column, y - minY + 1);
                        iterator.remove();
                    }
                }
            }
            for (Heightmap.Types type : pending) {
                storages.get(type).set(column, 0);
            }
        }

        Map<Heightmap.Types, long[]> recalculated = new LinkedHashMap<>();
        storages.forEach((type, storage) ->
                recalculated.put(type, storage.getRaw()));
        return Map.copyOf(recalculated);
    }

    private BlockState stateAt(
            Map<Integer, SectionSlot> sections,
            Map<Integer, DecodedSection> target,
            Map<Integer, PalettedContainer<BlockState>> decodedStored,
            int x,
            int worldY,
            int z) throws UnsupportedChunk {
        int sectionY = Math.floorDiv(worldY, 16);
        int index = (Math.floorMod(worldY, 16) << 8) | (z << 4) | x;
        DecodedSection replacement = target.get(sectionY);
        if (replacement != null) {
            return replacement.blockStates().get(index);
        }
        SectionSlot stored = sections.get(sectionY);
        if (stored == null || !stored.tag().contains("block_states")) {
            return Blocks.AIR.defaultBlockState();
        }
        PalettedContainer<BlockState> states = decodedStored.get(sectionY);
        if (states == null) {
            states = stored.tag().read("block_states", blockStates)
                    .orElseThrow(UnsupportedChunk::new);
            decodedStored.put(sectionY, states);
        }
        return states.get(x, Math.floorMod(worldY, 16), z);
    }

    private static Map<Integer, SectionSlot> sectionSlots(ListTag serialized)
            throws UnsupportedChunk {
        Map<Integer, SectionSlot> sections = new HashMap<>();
        for (int index = 0; index < serialized.size(); index++) {
            CompoundTag section = serialized.getCompound(index)
                    .orElseThrow(UnsupportedChunk::new);
            Byte sectionY = section.getByte("Y").orElse(null);
            if (sectionY == null
                    || sections.put(sectionY.intValue(),
                            new SectionSlot(index, section)) != null) {
                throw new UnsupportedChunk();
            }
        }
        return sections;
    }

    private static Map<Integer, DecodedSection> targetSections(
            ChunkPos position, Map<SectionKey, DecodedSection> target)
            throws IOException {
        Map<Integer, DecodedSection> sections = new HashMap<>();
        for (var entry : target.entrySet()) {
            SectionKey key = entry.getKey();
            if (key.chunkX() != position.x || key.chunkZ() != position.z
                    || sections.put(key.sectionY(), entry.getValue()) != null) {
                throw new IOException("Stored Restore target does not match " + position);
            }
        }
        return sections;
    }

    private static void validatePosition(ChunkPos position, CompoundTag source)
            throws IOException {
        if (source.getInt("xPos").orElse(Integer.MIN_VALUE) != position.x
                || source.getInt("zPos").orElse(Integer.MIN_VALUE) != position.z) {
            throw new IOException("Stored chunk position mismatch: " + position);
        }
    }

    private static ListTag replaceBlockEntities(
            ListTag stored, Map<SectionKey, DecodedSection> target) {
        Set<Integer> targetSections = new HashSet<>();
        target.keySet().forEach(key -> targetSections.add(key.sectionY()));
        ListTag replaced = new ListTag();
        for (int index = 0; index < stored.size(); index++) {
            stored.getCompound(index).filter(tag -> !targetSections.contains(
                    Math.floorDiv(tag.getIntOr("y", 0), 16)))
                    .map(CompoundTag::copy).ifPresent(replaced::add);
        }
        for (var section : target.entrySet()) {
            SectionKey key = section.getKey();
            section.getValue().blockEntities().forEach((index, nbt) -> {
                CompoundTag full = nbt.copy();
                full.putInt("x", key.chunkX() * 16 + (index & 15));
                full.putInt("y", key.sectionY() * 16 + ((index >>> 8) & 15));
                full.putInt("z", key.chunkZ() * 16 + ((index >>> 4) & 15));
                replaced.add(full);
            });
        }
        return replaced;
    }

    record Patch(
            CompoundTag tag,
            Map<Heightmap.Types, long[]> heightmaps) { }

    private record SectionSlot(int index, CompoundTag tag) { }

    static final class UnsupportedChunk extends Exception { }
}
