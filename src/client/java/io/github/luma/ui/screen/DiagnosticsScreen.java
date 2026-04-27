package io.github.luma.ui.screen;

import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.toolkit.UiToolkitRegistry;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DiagnosticsScreen extends LumaScreen {

    private static final UiToolkitRegistry UI_TOOLKITS = UiToolkitRegistry.defaultRegistry();

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController controller = new ProjectHomeScreenController();
    private ProjectHomeViewState state;

    public DiagnosticsScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.diagnostics.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, "luma.status.project_ready", true);

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.diagnostics.title")));
        frame.child(LumaUi.caption(Component.translatable("luma.diagnostics.help")));

        FlowLayout body = LumaUi.screenBody();
        frame.child(LumaUi.screenScroll(body));

        if (this.state.project() == null || this.state.advanced() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        body.child(this.integritySection());
        body.child(this.integrationSection());
        body.child(this.uiToolkitSection());
        body.child(this.logSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout integritySection() {
        var report = this.state.advanced().integrityReport();
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.integrity_title"),
                Component.translatable(report.valid() ? "luma.integrity.valid" : "luma.integrity.invalid")
        );
        for (String error : report.errors()) {
            section.child(LumaUi.danger(Component.translatable("luma.integrity.error", error)));
        }
        for (String warning : report.warnings()) {
            section.child(LumaUi.caption(Component.translatable("luma.integrity.warning", warning)));
        }
        if (report.errors().isEmpty() && report.warnings().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.project.integrity_clean")));
        }
        return section;
    }

    private FlowLayout integrationSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.integrations_title"),
                Component.translatable("luma.project.integrations_help")
        );
        if (this.state.advanced().integrations().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.integrations.empty")));
            return section;
        }
        for (var integration : this.state.advanced().integrations()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.integrations.entry",
                    integration.toolId(),
                    integration.available() ? Component.translatable("luma.common.available") : Component.translatable("luma.common.unavailable"),
                    integration.modeLabel(),
                    String.join(", ", integration.capabilityLabels())
            )));
        }
        return section;
    }

    private FlowLayout uiToolkitSection() {
        var status = UI_TOOLKITS.status();
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.ui_toolkit.title"),
                Component.translatable(
                        status.targetActive() ? "luma.ui_toolkit.ldlib2_active" : "luma.ui_toolkit.fallback_active",
                        status.activeBackend().displayName()
                )
        );
        for (var backend : status.backends()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.ui_toolkit.backend",
                    backend.displayName(),
                    backend.available() ? Component.translatable("luma.common.available") : Component.translatable("luma.common.unavailable"),
                    String.join(" ", backend.notes())
            )));
        }
        return section;
    }

    private FlowLayout logSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.diagnostics.logs_title"),
                Component.translatable("luma.diagnostics.logs_help")
        );
        if (this.state.advanced().journal().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.log.empty")));
            return section;
        }
        for (RecoveryJournalEntry entry : this.state.advanced().journal().stream().limit(8).toList()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.log.entry_header",
                    entry.type(),
                    entry.timestamp().toString()
            )));
        }
        return section;
    }
}
