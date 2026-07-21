package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.SectionBlob;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuickRollbackScopeTest {
    @Test
    void recordsPreparedRollbackIntoTheLiveAction() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));

        assertTrue(source.contains(
                "createQuickRollback(author, builder, selection, action)"));
        int blocks = source.indexOf("liveWorld.prepareRestore(");
        int entities = source.indexOf("liveEntityWorld.prepareRestore(");
        int record = source.indexOf("liveActions.recordRestore(");
        assertTrue(blocks >= 0 && entities > blocks && record > entities);
    }

    @Test
    void filtersSectionsAndEntityChunksWithNegativeFlooring() {
        Optional<BlockBox> area = Optional.of(
                new BlockBox(-17, -1, -1, -16, 0, 0));

        assertTrue(FabricDimensionRuntime.inside(
                area, new SectionKey(-2, -1, -1)));
        assertFalse(FabricDimensionRuntime.inside(
                area, new EntityChunkKey(-1, 0)));
        assertFalse(FabricDimensionRuntime.inside(
                area, new SectionKey(0, 0, 0)));
        assertTrue(FabricDimensionRuntime.inside(
                Optional.empty(), new SectionKey(100, 100, 100)));
    }

    @Test
    void clearsASectionOnlyWhenEveryActualChangeIsSelected() {
        SectionKey section = new SectionKey(-1, 0, 0);
        SectionBlob saved = section("minecraft:stone", 0, 15);
        SectionBlob head = section("minecraft:air", 0, 15);

        assertTrue(FabricDimensionRuntime.changesOnlyInside(
                new BlockBox(-16, 0, 0, -1, 0, 0), section, saved, head));
        assertFalse(FabricDimensionRuntime.changesOnlyInside(
                new BlockBox(-16, 0, 0, -16, 0, 0), section, saved, head));
    }

    private static SectionBlob section(String changedState, int... indexes) {
        List<String> states = new ArrayList<>(
                java.util.Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int index : indexes) {
            states.set(index, changedState);
        }
        return new SectionBlob(states, Map.of());
    }
}
