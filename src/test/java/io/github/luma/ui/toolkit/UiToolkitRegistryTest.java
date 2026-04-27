package io.github.luma.ui.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class UiToolkitRegistryTest {

    @Test
    void fallsBackToMinecraftUiWhenLdLib2IsUnavailable() {
        UiToolkitRegistry registry = new UiToolkitRegistry(List.of(
                new StaticBackend(UiToolkit.LDLIB2, false, true),
                new StaticBackend(UiToolkit.MINECRAFT, true, false)
        ));

        UiToolkitStatus status = registry.status();

        assertEquals(UiToolkit.LDLIB2, status.targetToolkit());
        assertEquals(UiToolkit.MINECRAFT, status.activeToolkit());
        assertFalse(status.targetActive());
    }

    @Test
    void activatesLdLib2WhenItIsAvailable() {
        UiToolkitRegistry registry = new UiToolkitRegistry(List.of(
                new StaticBackend(UiToolkit.LDLIB2, true, true),
                new StaticBackend(UiToolkit.MINECRAFT, true, false)
        ));

        UiToolkitStatus status = registry.status();

        assertEquals(UiToolkit.LDLIB2, status.activeToolkit());
        assertTrue(status.targetActive());
    }

    @Test
    void ldLib2BlueprintKeepsPrimaryHomeActionsSimple() {
        LdLib2InterfaceBlueprint blueprint = LdLib2InterfaceBlueprint.childFriendlyProjectHome();

        assertTrue(blueprint.elementTypes().contains("UIElement"));
        assertTrue(blueprint.elementTypes().contains("Button"));
        assertTrue(blueprint.elementTypes().contains("ScrollerView"));
        assertTrue(blueprint.projectHome().stream().anyMatch(role -> "window".equals(role.id())));
        assertTrue(blueprint.projectHome().stream().anyMatch(role -> "sidebar".equals(role.id())));
        assertEquals(LdLib2InterfaceBlueprint.GDP_STYLESHEET, blueprint.stylesheetLocation());
        assertTrue(blueprint.projectHome().stream()
                .filter(role -> "window".equals(role.id()))
                .anyMatch(role -> role.hasClass("panel_bg")));
        assertEquals(5, blueprint.projectHome().stream()
                .filter(role -> role.id().endsWith("-action"))
                .count());
        assertTrue(blueprint.projectHome().stream()
                .filter(role -> role.id().endsWith("-action"))
                .allMatch(role -> role.hasLayoutRule("height", "18")));
    }

    private record StaticBackend(UiToolkit toolkit, boolean available, boolean primaryTarget)
            implements UiToolkitBackend {

        @Override
        public List<String> notes() {
            return List.of();
        }
    }
}
