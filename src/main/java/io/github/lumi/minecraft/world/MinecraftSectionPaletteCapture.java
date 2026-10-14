package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Copies only used native palette entries into canonical immutable section state. */
public final class MinecraftSectionPaletteCapture {
    private final Map<BlockState, String> encodedStates = new IdentityHashMap<>();
    private final WorldDataVersion dataVersion = MinecraftWorldDataVersion.current();

    public SectionBlob capture(LevelChunkSection section, Map<Integer, CanonicalNbt> entities) {
        section.acquire();
        try {
            var data = section.getStates().data;
            var storage = data.storage();
            var nativePalette = data.palette();
            short[] indexes = new short[SectionBlob.BLOCK_COUNT];
            if (storage.getBits() == 0) {
                return SectionBlob.fromPalette(List.of(encode(nativePalette.valueFor(0))),
                        indexes, entities, dataVersion);
            }
            // Global native IDs need not fit in a short; compact only the used IDs.
            var ordinals = new Int2IntOpenHashMap();
            ordinals.defaultReturnValue(-1);
            var states = new ArrayList<Entry>();
            for (int cell = 0; cell < indexes.length; cell++) {
                int nativeId = storage.get(cell);
                int ordinal = ordinals.get(nativeId);
                if (ordinal == -1) {
                    ordinal = states.size();
                    ordinals.put(nativeId, ordinal);
                    states.add(new Entry(ordinal, encode(nativePalette.valueFor(nativeId))));
                }
                indexes[cell] = (short) ordinal;
            }
            states.sort(Comparator.comparing(Entry::state));
            var palette = new ArrayList<String>(states.size());
            short[] remap = new short[states.size()];
            for (int canonical = 0; canonical < states.size(); canonical++) {
                Entry entry = states.get(canonical);
                palette.add(entry.state());
                remap[entry.ordinal()] = (short) canonical;
            }
            for (int cell = 0; cell < indexes.length; cell++) {
                indexes[cell] = remap[Short.toUnsignedInt(indexes[cell])];
            }
            return SectionBlob.fromPalette(palette, indexes, entities, dataVersion);
        } finally {
            section.release();
        }
    }

    private String encode(BlockState state) {
        return encodedStates.computeIfAbsent(state, BlockStateParser::serialize);
    }

    private record Entry(int ordinal, String state) { }
}
