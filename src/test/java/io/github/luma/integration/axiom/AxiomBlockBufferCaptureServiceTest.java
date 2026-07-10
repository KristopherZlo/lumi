package io.github.luma.integration.axiom;

import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomBlockBufferCaptureServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sameStateWithoutNewBlockEntityPayloadIsNotCapturedAsBlockEntityDeletion() throws Exception {
        AxiomBlockBufferCaptureService service = new AxiomBlockBufferCaptureService(new AxiomBlockBufferExtractor());
        BlockState chestState = Blocks.CHEST.defaultBlockState();
        AxiomBlockMutation mutation = new AxiomBlockMutation(null, chestState, null);

        Method method = AxiomBlockBufferCaptureService.class.getDeclaredMethod(
                "sameStateWithoutBlockEntityChange",
                BlockState.class,
                CompoundTag.class,
                AxiomBlockMutation.class
        );
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(service, chestState, chestTag(), mutation));
    }

    @Test
    void emptyBulkCaptureDoesNotSuppressDirectSectionFallback() {
        AxiomBlockBufferCaptureService service = new AxiomBlockBufferCaptureService(new AxiomBlockBufferExtractor());

        AxiomBlockBufferCaptureService.PreparedCapture attempt = service.prepareBeforeApply(null, null, null);

        assertFalse(attempt.prepared());
        assertFalse(attempt.hasSourceContext());
    }

    @Test
    void capturedBulkDoesNotSuppressDirectSectionFallback() {
        AxiomBlockBufferCaptureService.CaptureAttempt attempt =
                AxiomBlockBufferCaptureService.CaptureAttempt.captured(1, 1);

        assertTrue(attempt.captured());
        assertTrue(attempt.suppressDirectSectionFallback());
    }

    private static CompoundTag chestTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        return tag;
    }
}
