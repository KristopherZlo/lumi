package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftRestorePreparationTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preservesCoordinatesWhileReplacingPersistentPayloadTypes() throws Exception {
        SectionKey sectionKey = new SectionKey(-1, 2, 3);
        EntityChunkKey entityKey = new EntityChunkKey(-1, 3);
        var source = new WorldStateApply.State(
                Map.of(sectionKey, new SectionBlob(new ArrayList<>(Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of())),
                Map.of(entityKey, new EntityChunkBlob(List.of())));
        var preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));

        PreparedMinecraftState prepared = preparation.prepare(source);

        assertEquals(source, prepared.source());
        assertEquals(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                prepared.sections().get(sectionKey).blockStates().getFirst());
        assertEquals(List.of(), prepared.entities().get(entityKey).entities());
    }
}
