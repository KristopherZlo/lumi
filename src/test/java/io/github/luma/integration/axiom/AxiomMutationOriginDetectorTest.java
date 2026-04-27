package io.github.luma.integration.axiom;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomMutationOriginDetectorTest {

    @Test
    void ignoresStackWhenAxiomIsUnavailable() {
        AxiomMutationOriginDetector detector = new AxiomMutationOriginDetector(
                () -> false,
                () -> List.of("com.moulberry.axiom.tools.FillTool"),
                System::nanoTime
        );

        assertTrue(detector.detectOperation().isEmpty());
    }

    @Test
    void ignoresLumiAxiomIntegrationFrames() {
        AxiomMutationOriginDetector detector = new AxiomMutationOriginDetector(
                () -> true,
                () -> List.of("io.github.luma.integration.axiom.AxiomMutationOriginDetector"),
                System::nanoTime
        );

        assertTrue(detector.detectOperation().isEmpty());
        assertFalse(AxiomMutationOriginDetector.isExternalAxiomClassName(
                "io.github.luma.integration.axiom.AxiomMutationOriginDetector"
        ));
    }

    @Test
    void detectsExternalAxiomFrames() {
        AxiomMutationOriginDetector detector = new AxiomMutationOriginDetector(
                () -> true,
                () -> List.of(
                        "net.minecraft.world.level.chunk.LevelChunk",
                        "com.moulberry.axiom.tools.FillTool"
                ),
                System::nanoTime
        );

        var operation = detector.detectOperation();

        assertTrue(operation.isPresent());
        assertEquals("axiom", operation.get().actor());
        assertTrue(operation.get().actionId().startsWith("axiom-"));
        assertTrue(AxiomMutationOriginDetector.isExternalAxiomClassName("com.moulberry.axiom.tools.FillTool"));
    }

    @Test
    void reusesActionIdWhileObservedMutationsAreCloseTogether() {
        AtomicLong now = new AtomicLong(1_000L);
        AxiomMutationOriginDetector detector = new AxiomMutationOriginDetector(
                () -> true,
                () -> List.of("com.moulberry.axiom.tools.BrushTool"),
                now::get
        );

        String firstActionId = detector.detectOperation().orElseThrow().actionId();
        now.addAndGet(100_000_000L);
        String secondActionId = detector.detectOperation().orElseThrow().actionId();

        assertEquals(firstActionId, secondActionId);
    }

    @Test
    void startsNewActionAfterIdleWindow() {
        AtomicLong now = new AtomicLong(1_000L);
        AxiomMutationOriginDetector detector = new AxiomMutationOriginDetector(
                () -> true,
                () -> List.of("com.moulberry.axiom.tools.BrushTool"),
                now::get
        );

        String firstActionId = detector.detectOperation().orElseThrow().actionId();
        now.addAndGet(800_000_000L);
        String secondActionId = detector.detectOperation().orElseThrow().actionId();

        assertNotEquals(firstActionId, secondActionId);
    }
}
