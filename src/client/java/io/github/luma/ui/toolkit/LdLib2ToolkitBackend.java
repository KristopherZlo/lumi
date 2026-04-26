package io.github.luma.ui.toolkit;

import java.util.List;

public final class LdLib2ToolkitBackend implements UiToolkitBackend {

    private static final List<String> REQUIRED_CLASSES = List.of(
            "com.lowdragmc.lowdraglib2.gui.ui.UI",
            "com.lowdragmc.lowdraglib2.gui.ui.ModularUI"
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
        if (this.available) {
            return List.of("LDLib2 UI classes detected.", targetElements);
        }
        return List.of(
                "LDLib2 is the target UI backend, but no compatible Fabric 1.21.11 runtime classes are present.",
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
