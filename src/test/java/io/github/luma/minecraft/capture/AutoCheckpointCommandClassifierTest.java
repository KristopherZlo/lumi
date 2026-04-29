package io.github.luma.minecraft.capture;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCheckpointCommandClassifierTest {

    private final AutoCheckpointCommandClassifier classifier = new AutoCheckpointCommandClassifier(512);

    @Test
    void largeFillCommandsTriggerCheckpoint() {
        assertTrue(this.classifier.shouldCheckpoint("fill 0 64 0 7 71 7 minecraft:stone", BlockPos.ZERO));
    }

    @Test
    void smallFillCommandsAreIgnored() {
        assertFalse(this.classifier.shouldCheckpoint("fill 0 64 0 3 67 3 minecraft:stone", BlockPos.ZERO));
    }

    @Test
    void cloneUsesSourceVolumeOnly() {
        assertTrue(this.classifier.shouldCheckpoint("clone 0 64 0 7 71 7 20 64 20", BlockPos.ZERO));
    }

    @Test
    void relativeCoordinatesUsePlayerOrigin() {
        assertTrue(this.classifier.shouldCheckpoint("fill ~ ~ ~ ~7 ~7 ~7 minecraft:stone", new BlockPos(10, 64, 10)));
    }
}
