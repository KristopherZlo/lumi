package io.github.luma.integration.common;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalToolMutationOriginDetectorTest {

    @Test
    void ignoresLumiIntegrationFrames() {
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of("io.github.luma.integration.common.ExternalToolMutationOriginDetector"),
                System::nanoTime
        );

        assertTrue(detector.detectOperation().isEmpty());
        assertFalse(ExternalToolMutationOriginDetector.isExternalToolClassName(
                "io.github.luma.integration.axiom.AxiomBlockBufferCaptureService"
        ));
    }

    @Test
    void skipsStackInspectionWhenNoExternalToolsAreAvailable() {
        AtomicBoolean inspectedStack = new AtomicBoolean(false);
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> {
                    inspectedStack.set(true);
                    return List.of("com.sk89q.worldedit.EditSession");
                },
                System::nanoTime,
                () -> false
        );

        assertTrue(detector.detectOperation().isEmpty());
        assertFalse(inspectedStack.get());
    }

    @Test
    void detectsWorldEditFrames() {
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of(
                        "net.minecraft.world.level.Level",
                        "com.sk89q.worldedit.EditSession"
                ),
                System::nanoTime
        );

        ObservedExternalToolOperation operation = detector.detectOperation().orElseThrow();

        assertEquals(WorldMutationSource.WORLDEDIT, operation.source());
        assertEquals("worldedit", operation.actor());
        assertTrue(operation.actionId().startsWith("worldedit-"));
    }

    @Test
    void detectsFaweFramesBeforeWorldEditFrames() {
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of(
                        "com.sk89q.worldedit.EditSession",
                        "com.fastasyncworldedit.core.queue.IQueueExtent"
                ),
                System::nanoTime
        );

        ObservedExternalToolOperation operation = detector.detectOperation().orElseThrow();

        assertEquals(WorldMutationSource.FAWE, operation.source());
        assertEquals("fawe", operation.actor());
        assertTrue(operation.actionId().startsWith("fawe-"));
    }

    @Test
    void detectsLegacyFaweFrames() {
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of("com.boydti.fawe.object.FaweQueue"),
                System::nanoTime
        );

        assertEquals(WorldMutationSource.FAWE, detector.detectOperation().orElseThrow().source());
    }

    @Test
    void detectsAxiomFrames() {
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of("com.moulberry.axiom.tools.BrushTool"),
                System::nanoTime
        );

        ObservedExternalToolOperation operation = detector.detectOperation().orElseThrow();

        assertEquals(WorldMutationSource.AXIOM, operation.source());
        assertEquals("axiom", operation.actor());
        assertTrue(operation.actionId().startsWith("axiom-"));
    }

    @Test
    void detectsKnownBuilderToolFramesAsExternalTools() {
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of("fi.dy.masa.litematica.world.SchematicWorldHandler"),
                System::nanoTime
        );

        ObservedExternalToolOperation operation = detector.detectOperation().orElseThrow();

        assertEquals(WorldMutationSource.EXTERNAL_TOOL, operation.source());
        assertEquals("litematica", operation.actor());
        assertTrue(operation.actionId().startsWith("litematica-"));
    }

    @Test
    void reusesActionIdWhileObservedMutationsAreCloseTogether() {
        AtomicLong now = new AtomicLong(1_000L);
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of("com.fastasyncworldedit.core.queue.IQueueExtent"),
                now::get
        );

        String firstActionId = detector.detectOperation().orElseThrow().actionId();
        now.addAndGet(100_000_000L);
        String secondActionId = detector.detectOperation().orElseThrow().actionId();

        assertEquals(firstActionId, secondActionId);
    }

    @Test
    void separatesExternalToolActorsWhenSourcesAreShared() {
        AtomicLong now = new AtomicLong(1_000L);
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of(now.get() == 1_000L
                        ? "fi.dy.masa.litematica.world.SchematicWorldHandler"
                        : "fi.dy.masa.tweakeroo.tweaks.PlacementTweaks"),
                now::get
        );

        String firstActionId = detector.detectOperation().orElseThrow().actionId();
        now.incrementAndGet();
        String secondActionId = detector.detectOperation().orElseThrow().actionId();

        assertNotEquals(firstActionId, secondActionId);
    }

    @Test
    void startsNewActionAfterIdleWindow() {
        AtomicLong now = new AtomicLong(1_000L);
        ExternalToolMutationOriginDetector detector = new ExternalToolMutationOriginDetector(
                () -> List.of("com.moulberry.axiom.tools.BrushTool"),
                now::get
        );

        String firstActionId = detector.detectOperation().orElseThrow().actionId();
        now.addAndGet(800_000_000L);
        String secondActionId = detector.detectOperation().orElseThrow().actionId();

        assertNotEquals(firstActionId, secondActionId);
    }
}
