package io.github.luma.minecraft.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumaCommandsTest {

    @Test
    void mainCommandTreeDoesNotExposeRuntimeTestingCommands() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/command/LumaCommands.java"));

        assertFalse(source.contains("Commands.literal(\"testing\")"));
        assertFalse(source.contains("SingleplayerTestingService"));
        assertFalse(source.contains("RuntimeTestingHooks"));
    }

    @Test
    void lumiCommandsRequireOperatorPermission() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/command/LumaCommands.java"));

        assertTrue(source.contains(".requires(this.accessControl::canUse)"));
        assertTrue(source.contains("LumaAccessControl.getInstance()"));
    }
}
