package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSelectionHudTest {
    @Test
    void teachesModesModifiersAndZoneEditingFromLiveBindings() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiSelectionHud.java"));

        assertTrue(source.contains("key.lumi.action_modifier"));
        assertTrue(source.contains("client.options.keyAttack"));
        assertTrue(source.contains("client.options.keyUse"));
        assertTrue(source.contains("key.lumi.undo"));
        assertTrue(source.contains("key.lumi.redo"));
        assertTrue(source.contains("SelectionMode.EXTEND"));
        assertTrue(source.contains("hud_zone_add"));
        assertTrue(source.contains("hud_zone_erase"));
        assertTrue(source.contains("textures/gui/hints/hint_"));
    }
}
