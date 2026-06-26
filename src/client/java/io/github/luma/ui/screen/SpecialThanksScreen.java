package io.github.luma.ui.screen;

import io.github.luma.client.specialthanks.SpecialThanksClientCache;
import io.github.luma.client.specialthanks.SpecialThanksEntry;
import io.github.luma.client.specialthanks.SpecialThanksPlayerShowcaseComponent;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SpecialThanksScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController controller = new ProjectHomeScreenController();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final SpecialThanksClientCache specialThanks = SpecialThanksClientCache.getInstance();
    private final Runnable refreshListener = this::rebuild;
    private LumaScrollContainer<FlowLayout> bodyScroll;

    public SpecialThanksScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.special_thanks.title"));
        this.parent = parent;
        this.projectName = projectName;
        this.specialThanks.addListener(this.refreshListener);
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        ProjectHomeViewState state = this.controller.loadState(this.projectName, "luma.status.project_ready", false);

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        if (state.project() == null) {
            FlowLayout frame = LumaUi.screenFrame();
            root.child(frame);
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.special_thanks.title"),
                state.project(),
                state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.MORE);
        this.specialThanks.preload(this.client);
        window.content().child(LumaUi.caption(Component.translatable("luma.special_thanks.help")));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);
        body.child(this.thanksSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.specialThanks.removeListener(this.refreshListener);
        this.client.setScreen(this.parent);
    }

    @Override
    public void removed() {
        super.removed();
        this.specialThanks.removeListener(this.refreshListener);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    private FlowLayout thanksSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.special_thanks.people_title"),
                Component.translatable("luma.special_thanks.people_help")
        );
        for (SpecialThanksEntry entry : this.specialThanks.entries()) {
            section.child(this.entryCard(entry));
        }
        return section;
    }

    private FlowLayout entryCard(SpecialThanksEntry entry) {
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(10);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(new SpecialThanksPlayerShowcaseComponent(entry.skinName()));

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.value(Component.literal(entry.displayName())));
        text.child(LumaUi.caption(Component.literal(entry.description())));
        row.child(text);
        card.child(row);
        return card;
    }

    private void rebuild() {
        if (this.client.screen != this) {
            return;
        }
        this.rebuildPreservingScroll(() -> this.bodyScroll);
    }
}
