package io.github.luma.ui.toolkit;

import java.util.List;

public final class LdLib2ToolkitBackend implements UiToolkitBackend {

    private static final List<String> REQUIRED_CLASSES = List.of(
            "com.lowdragmc.lowdraglib2.gui.ui.UI",
            "com.lowdragmc.lowdraglib2.gui.ui.ModularUI",
            "com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen",
            "com.lowdragmc.lowdraglib2.gui.ui.elements.Button",
            "com.lowdragmc.lowdraglib2.gui.ui.elements.Label",
            "com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView",
            "com.lowdragmc.lowdraglib2.gui.ui.elements.TextField",
            "com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle",
            "com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager"
    );

    private final boolean available;
    private final LdLib2InterfaceBlueprint blueprint = LdLib2InterfaceBlueprint.childFriendlyProjectHome();

    public LdLib2ToolkitBackend(ClassLoader classLoader) {
        this.available = REQUIRED_CLASSES.stream().allMatch(className -> this.classPresent(classLoader, className));
    }

    @Override
    public UiToolkit toolkit() {
        return UiToolkit.LDLIB2;
    }

    @Override
    public boolean available() {
        return this.available;
    }

    @Override
    public boolean primaryTarget() {
        return true;
    }

    @Override
    public List<String> notes() {
        String targetElements = "Target elements: " + String.join(", ", this.blueprint.elementTypes()) + ".";
        String theme = "Built-in LDLib2 theme target: " + this.blueprint.builtInThemeName()
                + " (" + this.blueprint.stylesheetLocation() + ").";
        if (this.available) {
            return List.of("LDLib2 GDP UI classes detected.", theme, targetElements);
        }
        return List.of(
                "LDLib2 GDP is required for every Lumi screen, but compatible runtime classes are not present.",
                theme,
                targetElements
        );
    }

    private boolean classPresent(ClassLoader classLoader, String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
