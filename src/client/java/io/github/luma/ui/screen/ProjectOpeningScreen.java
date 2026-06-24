package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProjectOpeningScreen extends LumaScreen {

    private final Screen parent;
    private final Minecraft client = Minecraft.getInstance();

    public ProjectOpeningScreen(Screen parent) {
        super(Component.translatable("luma.screen.opening_workspace.title"));
        this.parent = parent;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        frame.child(LumaUi.closeHeader(Component.translatable("luma.workspace.opening_title"), button -> this.onClose()));
        frame.child(LumaUi.statusBanner(Component.translatable("luma.status.opening_workspace")));
        frame.child(LumaUi.caption(Component.translatable("luma.workspace.opening_help")));
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }
}
