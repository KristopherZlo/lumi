package io.github.luma.ui.screen;

import io.github.luma.client.update.UpdateCheckService;
import io.github.luma.client.update.UpdateRelease;
import io.github.luma.ui.LumaUi;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class UpdateAvailableScreen extends LumaScreen {

    private static final int MIN_DIALOG_WIDTH = 260;
    private static final int MAX_DIALOG_WIDTH = 380;

    private final Minecraft client = Minecraft.getInstance();
    private final Screen parent;
    private final UpdateRelease release;
    private final UpdateCheckService updateCheckService;

    public UpdateAvailableScreen(Screen parent, UpdateRelease release, UpdateCheckService updateCheckService) {
        super(Component.translatable("luma.screen.update_available.title"));
        this.parent = parent;
        this.release = release;
        this.updateCheckService = updateCheckService;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(this.dialogWidth());
        root.child(frame);

        frame.child(LumaUi.value(Component.translatable("luma.update.available_title", this.release.version())));
        if (!this.release.title().isBlank()) {
            frame.child(LumaUi.accent(Component.literal(this.release.title())));
        }
        frame.child(LumaUi.caption(Component.translatable(
                "luma.update.available_body",
                this.release.minecraftVersions().isEmpty() ? "" : this.release.minecraftVersions().getFirst()
        )));
        if (!this.release.summary().isBlank()) {
            frame.child(LumaUi.statusBanner(Component.literal(this.release.summary())));
        }
        frame.child(this.actions());
    }

    @Override
    public void onClose() {
        this.later();
    }

    private FlowLayout actions() {
        FlowLayout actions = LumaUi.actionRow();
        if (!this.release.downloadUrl().isBlank()) {
            actions.child(LumaUi.primaryButton(
                    Component.translatable("luma.action.download_update"),
                    button -> this.openUrl(this.release.downloadUrl())
            ));
        }
        if (!this.release.changelogUrl().isBlank()) {
            actions.child(LumaUi.button(
                    Component.translatable("luma.action.open_changelog"),
                    button -> this.openUrl(this.release.changelogUrl())
            ));
        }
        actions.child(LumaUi.button(Component.translatable("luma.action.later"), button -> this.later()));
        actions.child(LumaUi.button(
                Component.translatable("luma.action.dont_show_version"),
                button -> this.dismissVersion()
        ));
        return actions;
    }

    private void openUrl(String url) {
        try {
            Util.getPlatform().openUri(URI.create(url));
        } finally {
            this.later();
        }
    }

    private void later() {
        if (this.updateCheckService != null) {
            this.updateCheckService.snoozeVersion(this.release.version());
        }
        this.client.setScreen(this.parent);
    }

    private void dismissVersion() {
        if (this.updateCheckService != null) {
            this.updateCheckService.dismissVersion(this.release.version());
        }
        this.client.setScreen(this.parent);
    }

    private int dialogWidth() {
        return Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, this.width - 20));
    }
}
