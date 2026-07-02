package io.github.luma.client.selection;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiRegionSelectionTeachingControllerTest {

    @Test
    void usesMouseIconsOnlyForMouseButtons() {
        assertTrue(LumiRegionSelectionTeachingController.usesMouseIcon("LMB"));
        assertTrue(LumiRegionSelectionTeachingController.usesMouseIcon("MMB"));
        assertTrue(LumiRegionSelectionTeachingController.usesMouseIcon("RMB"));
        assertFalse(LumiRegionSelectionTeachingController.usesMouseIcon("Ctrl"));
        assertFalse(LumiRegionSelectionTeachingController.usesMouseIcon("Wheel"));
        assertFalse(LumiRegionSelectionTeachingController.usesMouseIcon("Clear"));
    }

    @Test
    void nonMouseKeysUseBracketLabels() {
        assertEquals("[ACTION]", LumiRegionSelectionTeachingController.keyLabel("ACTION"));
        assertEquals("[CTRL]", LumiRegionSelectionTeachingController.keyLabel("Ctrl"));
        assertEquals("[WHEEL]", LumiRegionSelectionTeachingController.keyLabel("Wheel"));
    }

    @Test
    void hintScaleKeepsGuiScaleTwoAsTheReferenceSize() {
        assertEquals(1.0F, LumiRegionSelectionTeachingController.hintScale(2));
        assertEquals(2.0F, LumiRegionSelectionTeachingController.hintScale(1));
        assertEquals(2.0F / 3.0F, LumiRegionSelectionTeachingController.hintScale(3));
    }

    @Test
    void teachingHudTextUsesTranslations() throws Exception {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/selection/LumiRegionSelectionTeachingController.java"
        ));

        assertTrue(source.contains("Component.translatable(\"luma.selection.hud_zone_edit"));
        assertTrue(source.contains("Component.translatable(\"luma.selection.hud_adjust"));
        assertFalse(source.contains("\"Selection adjust\""));
        assertFalse(source.contains("\"First corner\""));
        assertFalse(source.contains("\"Hold: edit zone\""));
    }
}
