package io.github.lumi.performance;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.minecraft.world.MinecraftSectionPaletteCapture;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("performance")
class SectionPalettePerformanceTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 257, 4096})
    void comparesOriginalExpandedCaptureWithDirectPaletteCapture(int distinct) throws Exception {
        var states = new ArrayList<BlockState>();
        Block.BLOCK_STATE_REGISTRY.forEach(states::add);
        var section = new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
            section.setBlockState(cell & 15, cell >>> 8, (cell >>> 4) & 15,
                    states.get(cell % distinct), false);
        }
        var original = new ExpandedCapture();
        var direct = new MinecraftSectionPaletteCapture();
        SectionBlob expected = original.capture(section);
        assertEquals(expected, direct.capture(section, Map.of()));
        var before = new PerformanceSamples("palette-expanded-" + distinct, 128);
        var after = new PerformanceSamples("palette-direct-" + distinct, 128);
        for (int sample = -2; sample < 7; sample++) {
            PerformanceSamples.Work oldWork = () -> captureMany(original::capture, section);
            PerformanceSamples.Work newWork = () -> captureMany(
                    value -> direct.capture(value, Map.of()), section);
            if (sample < 0) { oldWork.run(); newWork.run(); }
            else if (sample % 2 == 0) { before.measure(oldWork); after.measure(newWork); }
            else { after.measure(newWork); before.measure(oldWork); }
        }
        before.write();
        after.write();
        if (before.medianAllocatedBytes() >= 0) {
            assertTrue(after.medianAllocatedBytes() < before.medianAllocatedBytes(),
                    "Direct native capture must allocate less than the original capture");
        }
    }

    private static long captureMany(Capture capture, LevelChunkSection section) {
        long consumed = 0;
        for (int index = 0; index < 128; index++) {
            var result = capture.capture(section);
            consumed += result.palette().palette().size() + result.palette().paletteIndex(index);
        }
        return consumed;
    }

    @FunctionalInterface private interface Capture { SectionBlob capture(LevelChunkSection section); }

    /** Exact former capture algorithm, retained only as a measurement control. */
    private static final class ExpandedCapture {
        private final Map<BlockState, String> encoded = new IdentityHashMap<>();
        SectionBlob capture(LevelChunkSection section) {
            var states = new ArrayList<String>(SectionBlob.BLOCK_COUNT);
            section.acquire();
            try {
                for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
                    states.add(encoded.computeIfAbsent(section.getBlockState(
                            cell & 15, cell >>> 8, (cell >>> 4) & 15), BlockStateParser::serialize));
                }
            } finally { section.release(); }
            return new SectionBlob(states, Map.of());
        }
    }
}
