package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.TextureComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class SpecialThanksScreen extends LumaScreen {

    private static final Identifier DEFAULT_SKIN = Identifier.fromNamespaceAndPath(
            "minecraft",
            "textures/entity/player/wide/steve.png"
    );
    private static final List<SpecialThanksEntry> ENTRIES = List.of(
            new SpecialThanksEntry("Zlo", "luma.special_thanks.zlo_role", DEFAULT_SKIN)
    );

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController controller = new ProjectHomeScreenController();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();

    public SpecialThanksScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.special_thanks.title"));
        this.parent = parent;
        this.projectName = projectName;
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
        window.content().child(LumaUi.caption(Component.translatable("luma.special_thanks.help")));

        FlowLayout body = LumaUi.screenBody();
        window.content().child(LumaUi.screenScroll(body));
        body.child(this.thanksSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
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
        for (SpecialThanksEntry entry : ENTRIES) {
            section.child(this.entryCard(entry));
        }
        return section;
    }

    private FlowLayout entryCard(SpecialThanksEntry entry) {
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(7);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(this.skinFace(entry.skin()));

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.value(Component.literal(entry.nickname())));
        text.child(LumaUi.caption(Component.translatable(entry.roleKey())));
        row.child(text);
        card.child(row);
        return card;
    }

    private TextureComponent skinFace(Identifier skin) {
        TextureComponent face = UIComponents.texture(skin, 8, 8, 8, 8, 64, 64);
        face.blend(true);
        face.sizing(Sizing.fixed(32), Sizing.fixed(32));
        return face;
    }

    private record SpecialThanksEntry(String nickname, String roleKey, Identifier skin) {
    }
}
