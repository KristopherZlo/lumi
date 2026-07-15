package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockEntityBaselineStoreTest {
    @Test
    void reconstructsOnePreMutationSectionFromCurrentBlocksAndOldBlockEntityNbt() {
        BlockEntityBaselineStore baselines = new BlockEntityBaselineStore();
        SectionKey key = new SectionKey(1, 2, 3);
        CanonicalNbt oldNbt = new CanonicalNbt(new byte[] {1});
        CanonicalNbt newNbt = new CanonicalNbt(new byte[] {2});
        baselines.remember(key, Map.of(17, oldNbt));
        SectionBlob current = new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:stone")),
                Map.of(17, newNbt));

        SectionBlob origin = baselines.takeOrigin(key, current).orElseThrow();

        assertEquals(current.blockStates(), origin.blockStates());
        assertEquals(Map.of(17, oldNbt), origin.blockEntities());
        assertTrue(baselines.takeOrigin(key, current).isEmpty());
    }
}
