package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import java.util.List;
import java.util.Map;

/** Payloads captured by this game's adapters; legacy conversion fixtures stay explicit. */
final class NativeWorldTestData {
    private NativeWorldTestData() { }

    static WorldDataVersion version() { return MinecraftWorldDataVersion.current(); }

    static SectionBlob section(List<String> states, Map<Integer, CanonicalNbt> blockEntities) {
        return new SectionBlob(states, blockEntities, version());
    }

    static SectionBlob palette(List<String> palette, short[] indexes, Map<Integer, CanonicalNbt> blockEntities) {
        return SectionBlob.fromPalette(palette, indexes, blockEntities, version());
    }

    static EntityChunkBlob entities(List<EntityState> entities) {
        return new EntityChunkBlob(entities, version());
    }
}
