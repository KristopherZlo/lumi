package io.github.luma.minecraft.debug;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartialRestoreDiagnosticsLogTest {

    private final PartialRestoreDiagnosticsLog diagnostics = new PartialRestoreDiagnosticsLog();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void selectedAreaReportPrioritizesMechanismSamples() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(1, 64, 0));

        PartialRestoreDiagnosticsLog.SelectedAreaReport report = this.diagnostics.selectedAreaReport(
                bounds,
                point -> point.x() == 0
                        ? Blocks.STONE.defaultBlockState()
                        : Blocks.REPEATER.defaultBlockState(),
                16
        );

        assertEquals(2, report.scannedCells());
        assertEquals(2, report.nonAirBlocks());
        assertEquals(1, report.mechanismBlocks());
        assertTrue(report.samples().getFirst().contains("minecraft:repeater"));
    }

    @Test
    void planReportCountsOutOfBoundsTargets() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(0, 64, 0));

        PartialRestoreDiagnosticsLog.PlanReport report = this.diagnostics.planReport(
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 0),
                        StatePayload.capture(Blocks.REPEATER.defaultBlockState(), null),
                        StatePayload.air()
                )),
                bounds
        );

        assertEquals(1, report.blockChanges());
        assertEquals(1, report.outOfBoundsChanges());
        assertEquals(1, report.deleteTargets());
        assertTrue(report.samples().getFirst().contains("inSelection=false"));
    }

    @Test
    void postApplyReportShowsRedstoneLeftWhereTargetIsAir() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(1, 64, 1), new BlockPoint(1, 64, 1));
        StoredBlockChange change = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                StatePayload.capture(Blocks.REPEATER.defaultBlockState(), null),
                StatePayload.air()
        );

        PartialRestoreDiagnosticsLog.PostApplyReport report = this.diagnostics.postApplyReport(
                List.of(change),
                point -> Blocks.REDSTONE_WIRE.defaultBlockState(),
                null,
                bounds,
                16
        );

        assertEquals(1, report.checkedTargets());
        assertEquals(1, report.mismatchedTargets());
        assertEquals(1, report.expectedAirButLiveNonAir());
        assertTrue(report.samples().getFirst().contains("expected=minecraft:air"));
        assertTrue(report.samples().getFirst().contains("live=minecraft:redstone_wire"));
    }
}
