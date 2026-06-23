package io.github.luma.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneBlockEntityMixinCoverageTest {

    @Test
    void comparatorOutputMixinIsRegistered() throws IOException {
        assertMixin("ComparatorBlockEntityMixin", "setOutputSignal");
    }

    @Test
    void sculkSensorFrequencyMixinIsRegistered() throws IOException {
        assertMixin("SculkSensorBlockEntityMixin", "setLastVibrationFrequency");
    }

    private static void assertMixin(String mixinName, String methodName) throws IOException {
        Path sourcePath = Path.of("src/main/java/io/github/luma/mixin/" + mixinName + ".java");
        String mixinConfig = Files.readString(Path.of("src/main/resources/lumi.mixins.json"), StandardCharsets.UTF_8);

        assertTrue(Files.exists(sourcePath), mixinName + " must exist for redstone block entity payload capture");
        assertTrue(mixinConfig.contains("\"" + mixinName + "\""), mixinName + " must be registered");
        assertTrue(Files.readString(sourcePath, StandardCharsets.UTF_8).contains(methodName),
                mixinName + " must wrap " + methodName);
    }
}
