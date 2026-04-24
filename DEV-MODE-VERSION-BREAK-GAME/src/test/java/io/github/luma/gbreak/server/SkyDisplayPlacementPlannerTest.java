package io.github.luma.gbreak.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.luma.gbreak.corruption.CorruptionMaskSampler;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.HashSet;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class SkyDisplayPlacementPlannerTest {

    private static final int WORLD_BOTTOM_Y = -64;
    private static final int WORLD_TOP_Y = 320;

    private final CorruptionSettings settings = CorruptionSettings.getInstance();
    private final SkyDisplayPlacementPlanner planner = new SkyDisplayPlacementPlanner(
            this.settings,
            new CorruptionMaskSampler()
    );

    private double originalNoiseDensityPercent;
    private double originalNoiseScale;
    private double originalDetailNoiseScale;

    @BeforeEach
    void rememberSettings() {
        this.originalNoiseDensityPercent = this.settings.noiseDensityPercent();
        this.originalNoiseScale = this.settings.noiseScale();
        this.originalDetailNoiseScale = this.settings.detailNoiseScale();
        this.settings.setNoiseDensityPercent(65.0D);
        this.settings.setNoiseScale(0.055D);
        this.settings.setDetailNoiseScale(0.18D);
    }

    @AfterEach
    void restoreSettings() {
        this.settings.setNoiseDensityPercent(this.originalNoiseDensityPercent);
        this.settings.setNoiseScale(this.originalNoiseScale);
        this.settings.setDetailNoiseScale(this.originalDetailNoiseScale);
    }

    @Test
    void returnsStablePositionsForSameOrigin() {
        BlockPos origin = new BlockPos(12, 74, -20);

        List<BlockPos> firstPlan = this.planner.plan(origin, WORLD_BOTTOM_Y, WORLD_TOP_Y, 24);
        List<BlockPos> secondPlan = this.planner.plan(origin, WORLD_BOTTOM_Y, WORLD_TOP_Y, 24);

        assertFalse(firstPlan.isEmpty());
        assertEquals(firstPlan, secondPlan);
        assertEquals(new HashSet<>(firstPlan).size(), firstPlan.size());
        assertTrue(firstPlan.size() <= 24);
    }

    @Test
    void keepsPositionsInsideSkyHeightBand() {
        BlockPos origin = new BlockPos(0, 315, 0);

        List<BlockPos> positions = this.planner.plan(origin, WORLD_BOTTOM_Y, WORLD_TOP_Y, 24);

        assertFalse(positions.isEmpty());
        assertTrue(positions.stream().allMatch(pos -> pos.getY() == WORLD_TOP_Y - 4));
    }
}
