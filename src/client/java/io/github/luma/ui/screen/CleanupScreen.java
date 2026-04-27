package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectCleanupReport;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.CleanupScreenController;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CleanupScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final CleanupScreenController controller = new CleanupScreenController();
    private ProjectCleanupReport report;
    private String status = "luma.status.cleanup_ready";

    public CleanupScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.cleanup.title"));
        this.parent = parent;
        this.projectName = projectName;
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

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.cleanup.title")));
        frame.child(LumaUi.statusBanner(Component.translatable(this.status)));

        FlowLayout body = LumaUi.screenBody();
        frame.child(LumaUi.screenScroll(body));

        body.child(this.actionsSection());
        if (this.report != null) {
            body.child(this.resultsSection());
        }
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout actionsSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.cleanup.actions_title"),
                Component.translatable("luma.cleanup.actions_help")
        );
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.primaryButton(Component.translatable("luma.action.inspect_unused_files"), button -> {
            this.report = this.controller.inspect(this.projectName);
            this.status = this.report == null ? "luma.status.cleanup_failed" : "luma.status.cleanup_inspected";
            this.rebuild();
        }));
        ButtonComponent cleanButton = LumaUi.button(Component.translatable("luma.action.clean_up"), button -> {
            this.report = this.controller.apply(this.projectName);
            this.status = this.report == null ? "luma.status.cleanup_failed" : "luma.status.cleanup_applied";
            this.rebuild();
        });
        cleanButton.active(this.report != null && this.report.dryRun() && !this.report.candidates().isEmpty());
        actions.child(cleanButton);
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.onClose()));
        section.child(actions);
        return section;
    }

    private FlowLayout resultsSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable(this.report.dryRun() ? "luma.cleanup.results_title" : "luma.cleanup.applied_title"),
                Component.translatable("luma.cleanup.results_help", this.report.candidates().size(), this.formatBytes(this.report.reclaimedBytes()))
        );
        for (String warning : this.report.warnings()) {
            section.child(LumaUi.caption(Component.literal(warning)));
        }
        if (this.report.candidates().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.cleanup.empty")));
            return section;
        }
        for (var candidate : this.report.candidates().stream().limit(10).toList()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.cleanup.candidate",
                    candidate.relativePath(),
                    candidate.reason(),
                    this.formatBytes(candidate.sizeBytes())
            )));
        }
        return section;
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return (bytes / (1024L * 1024L)) + " MB";
    }
}
