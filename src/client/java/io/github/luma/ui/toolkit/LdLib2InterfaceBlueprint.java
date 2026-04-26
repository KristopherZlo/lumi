package io.github.luma.ui.toolkit;

import java.util.List;

/**
 * Describes Lumi's target LDLib2 element structure without linking Fabric code
 * against a NeoForge-only LDLib2 artifact.
 */
public final class LdLib2InterfaceBlueprint {

    private final List<LdLib2ElementRole> projectHome;

    public LdLib2InterfaceBlueprint(List<LdLib2ElementRole> projectHome) {
        if (projectHome.isEmpty()) {
            throw new IllegalArgumentException("A LDLib2 UI blueprint needs at least one element role.");
        }
        this.projectHome = List.copyOf(projectHome);
    }

    public static LdLib2InterfaceBlueprint childFriendlyProjectHome() {
        return new LdLib2InterfaceBlueprint(List.of(
                new LdLib2ElementRole("root", "UIElement", "Dimmed screen root with full-screen sizing."),
                new LdLib2ElementRole("window", "UIElement", "Bordered application window."),
                new LdLib2ElementRole("sidebar", "UIElement", "Project navigation and context."),
                new LdLib2ElementRole("title-bar", "UIElement", "Project title and short guidance."),
                new LdLib2ElementRole("status", "Label", "Short, persistent operation status."),
                new LdLib2ElementRole("primary-actions", "UIElement", "Column of the main builder decisions."),
                new LdLib2ElementRole("save-action", "Button", "Keep this moment."),
                new LdLib2ElementRole("restore-action", "Button", "Go back safely."),
                new LdLib2ElementRole("saved-moments-action", "Button", "Browse saved moments."),
                new LdLib2ElementRole("ideas-action", "Button", "Open variants without Git wording."),
                new LdLib2ElementRole("share-action", "Button", "Share or combine a build."),
                new LdLib2ElementRole("history", "ScrollerView", "Scrollable saved-moment list."),
                new LdLib2ElementRole("more-tools", "TabView", "Advanced tools and diagnostics stay out of the default path.")
        ));
    }

    public List<LdLib2ElementRole> projectHome() {
        return this.projectHome;
    }

    public List<String> elementTypes() {
        return this.projectHome.stream()
                .map(LdLib2ElementRole::ldLib2Type)
                .distinct()
                .toList();
    }
}
