package io.github.luma.minecraft.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumaCommandsTest {

    @Test
    void mainCommandTreeDelegatesTestingRegistrationToRuntimeHook() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/command/LumaCommands.java"));

        assertTrue(source.contains("runtimeTestingHooks.registerCommands(root)"));
        assertFalse(source.contains("Commands.literal(\"testing\")"));
    }

    @Test
    void testingCommandTreeIsOwnedByDedicatedRuntimeTestingCommands() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/command/LumaTestingCommands.java"
        ));

        assertTrue(source.contains("Commands.literal(\"testing\")"));
        assertTrue(source.contains("SingleplayerTestingService"));
    }
}
