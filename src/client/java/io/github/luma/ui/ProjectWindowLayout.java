package io.github.luma.ui;

import io.github.luma.ui.framework.container.FlowLayout;
import io.github.luma.ui.framework.container.UIContainers;
import io.github.luma.ui.framework.core.Sizing;
import net.minecraft.network.chat.Component;

/**
 * Windowed project-home layout modeled after LDLib2's element tree:
 * root window, sidebar, title bar, content, and scrollable body.
 */
public final class ProjectWindowLayout {

    private static final int WIDE_SIDEBAR_WIDTH = 158;
    private static final int NARROW_SIDEBAR_WIDTH = 126;

    private final FlowLayout root;
    private final FlowLayout sidebar;
    private final FlowLayout content;
    private final FlowLayout titleBar;

    public ProjectWindowLayout(int screenWidth, Component title, Component place, Component idea) {
        this.root = LumaUi.windowShell();
        this.sidebar = LumaUi.windowSidebar(screenWidth < 720 ? NARROW_SIDEBAR_WIDTH : WIDE_SIDEBAR_WIDTH);
        this.content = LumaUi.windowContent();
        this.titleBar = LumaUi.titleBar();

        this.sidebar.child(LumaUi.value(Component.literal("Lumi")));
        this.sidebar.child(LumaUi.caption(Component.translatable("luma.window.mode")));
        this.sidebar.child(LumaUi.sidebarChip(place));
        this.sidebar.child(LumaUi.sidebarChip(idea));

        FlowLayout titleColumn = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        titleColumn.gap(2);
        titleColumn.child(LumaUi.title(title));
        titleColumn.child(LumaUi.caption(Component.translatable("luma.window.home_help")));
        this.titleBar.child(titleColumn);

        this.content.child(this.titleBar);
        this.root.child(this.sidebar);
        this.root.child(this.content);
    }

    public FlowLayout root() {
        return this.root;
    }

    public FlowLayout sidebar() {
        return this.sidebar;
    }

    public FlowLayout content() {
        return this.content;
    }
}
