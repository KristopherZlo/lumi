package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiShortcutCatalogTest {

    @Test
    void catalogIncludesHotkeyInformationChord() {
        assertTrue(LumiShortcutCatalog.entries().stream().anyMatch(entry ->
                entry.roles().equals(java.util.List.of(
                        LumiClientKeyBindings.Role.ACTION,
                        LumiClientKeyBindings.Role.INFO
                ))));
    }
}
