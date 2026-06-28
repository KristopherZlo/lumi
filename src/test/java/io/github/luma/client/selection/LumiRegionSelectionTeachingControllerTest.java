package io.github.luma.client.selection;

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
        assertEquals("[Alt]", LumiRegionSelectionTeachingController.keyLabel("ACTION"));
        assertEquals("[Ctrl]", LumiRegionSelectionTeachingController.keyLabel("Ctrl"));
        assertEquals("[Wheel]", LumiRegionSelectionTeachingController.keyLabel("Wheel"));
    }
}
