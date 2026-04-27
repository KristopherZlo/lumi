package io.github.luma.ui.toolkit;

import java.util.List;

/**
 * Describes Lumi's target LDLib2 element structure without linking Fabric code
 * against a NeoForge-only LDLib2 artifact.
 */
public final class LdLib2InterfaceBlueprint {

    public static final String GDP_STYLESHEET = "ldlib2:lss/gdp.lss";
    public static final String GDP_THEME_NAME = "GDP";

    private final String stylesheetLocation;
    private final List<LdLib2ElementRole> projectHome;

    public LdLib2InterfaceBlueprint(List<LdLib2ElementRole> projectHome) {
        this(GDP_STYLESHEET, projectHome);
    }

    public LdLib2InterfaceBlueprint(String stylesheetLocation, List<LdLib2ElementRole> projectHome) {
        if (stylesheetLocation == null || stylesheetLocation.isBlank()) {
            throw new IllegalArgumentException("A LDLib2 UI blueprint needs a stylesheet location.");
        }
        if (projectHome.isEmpty()) {
            throw new IllegalArgumentException("A LDLib2 UI blueprint needs at least one element role.");
        }
        this.stylesheetLocation = stylesheetLocation;
        this.projectHome = List.copyOf(projectHome);
    }

    public static LdLib2InterfaceBlueprint childFriendlyProjectHome() {
        return compactGdpProjectHome();
    }

    public static LdLib2InterfaceBlueprint compactGdpProjectHome() {
        return new LdLib2InterfaceBlueprint(GDP_STYLESHEET, List.of(
                role("root", "UIElement", "Dimmed screen root with full-screen sizing.",
                        List.of("lumi-root"),
                        rule("width-percent", "100"), rule("height-percent", "100"), rule("padding-all", "8")),
                role("window", "UIElement", "GDP themed application window.",
                        List.of("panel_bg", "lumi-window"),
                        rule("width-percent", "100"), rule("height-percent", "100"),
                        rule("flex-direction", "row"), rule("overflow", "hidden")),
                role("sidebar", "UIElement", "Project navigation and context.",
                        List.of("lumi-sidebar"),
                        rule("width", "158"), rule("height-percent", "100"), rule("padding-all", "7"),
                        rule("gap-all", "5"), rule("flex-shrink", "0")),
                role("title-bar", "UIElement", "Project title and short guidance.",
                        List.of("lumi-title-bar"),
                        rule("width-percent", "100"), rule("padding-all", "6"), rule("gap-all", "6"),
                        rule("flex-direction", "row")),
                role("status", "Label", "Short, persistent operation status.",
                        List.of("lumi-status"),
                        rule("width-percent", "100"), rule("height", "14")),
                role("primary-actions", "UIElement", "Column of the main builder decisions.",
                        List.of("lumi-primary-actions"),
                        rule("width-percent", "100"), rule("gap-all", "5"), rule("flex-direction", "column")),
                compactButton("save-action", "Keep this moment."),
                compactButton("restore-action", "Go back safely."),
                compactButton("saved-moments-action", "Browse saved moments."),
                compactButton("ideas-action", "Open variants without Git wording."),
                compactButton("share-action", "Share or combine a build."),
                role("history", "ScrollerView", "Scrollable saved-moment list.",
                        List.of("lumi-history"),
                        rule("width-percent", "100"), rule("flex", "1"), rule("min-height", "0")),
                role("more-tools", "TabView", "Advanced tools and diagnostics stay out of the default path.",
                        List.of("lumi-more-tools"),
                        rule("width-percent", "100"), rule("height", "96"))
        ));
    }

    public String stylesheetLocation() {
        return this.stylesheetLocation;
    }

    public String builtInThemeName() {
        return GDP_THEME_NAME;
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

    private static LdLib2ElementRole compactButton(String id, String purpose) {
        return role(id, "Button", purpose, List.of("lumi-action-button"),
                rule("height", "18"), rule("padding-horizontal", "5"), rule("padding-vertical", "2"),
                rule("flex-shrink", "1"));
    }

    private static LdLib2ElementRole role(
            String id,
            String ldLib2Type,
            String purpose,
            List<String> classes,
            LdLib2LayoutRule... rules
    ) {
        return new LdLib2ElementRole(id, ldLib2Type, purpose, classes, List.of(rules));
    }

    private static LdLib2LayoutRule rule(String property, String value) {
        return LdLib2LayoutRule.of(property, value);
    }
}
