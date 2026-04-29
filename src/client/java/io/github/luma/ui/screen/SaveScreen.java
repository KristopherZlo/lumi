package io.github.luma.ui.screen;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.SaveViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SaveScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private SaveViewState state = new SaveViewState(
            null,
            List.of(),
            List.of(),
            null,
            null,
            "luma.status.project_ready"
    );
    private String status = "luma.status.project_ready";
    private String saveMessage = "";
    private boolean showMoreOptions;
    private TextBoxComponent saveNameInput;

    public SaveScreen(Screen parent, String projectName) {
        this(parent, projectName, "", false);
    }

    public SaveScreen(Screen parent, String projectName, String initialMessage, boolean showMoreOptions) {
        super(Component.translatable("luma.screen.save.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.saveMessage = initialMessage == null ? "" : initialMessage;
        this.showMoreOptions = showMoreOptions;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadSaveState(this.projectName, this.status);
        PendingChangeSummary pending = this.controller.summarizePending(this.state.recoveryDraft());
        ProjectVersion activeHead = ProjectUiSupport.activeHead(this.state.project(), this.state.variants(), this.state.versions());
        boolean operationActive = this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.save.title")));
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        if (this.state.project() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        if (pending.isEmpty()) {
            FlowLayout empty = LumaUi.emptyState(
                    Component.translatable("luma.save.empty_title"),
                    Component.translatable("luma.save.empty_help")
            );
            FlowLayout actions = LumaUi.actionRow();
            actions.child(LumaUi.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(
                    this.parent,
                    this.projectName
            )));
            empty.child(actions);
            body.child(empty);
            return;
        }

        body.child(this.summarySection(pending));
        body.child(this.messageSection());
        body.child(this.primaryActions(operationActive));
        body.child(this.moreSection(activeHead, operationActive));
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout summarySection(PendingChangeSummary pending) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save.summary_title"),
                Component.translatable("luma.save.summary_help")
        );
        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(
                "luma.project.active_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        )));
        meta.child(LumaUi.chip(Component.translatable(
                "luma.dashboard.current_dimension",
                ProjectUiSupport.dimensionLabel(this.state.project().dimensionId())
        )));
        section.child(meta);

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(Component.translatable("luma.dashboard.pending_added"), Component.literal(Integer.toString(pending.addedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.dashboard.pending_removed"), Component.literal(Integer.toString(pending.removedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.dashboard.pending_changed"), Component.literal(Integer.toString(pending.changedBlocks()))));
        section.child(stats);
        return section;
    }

    private FlowLayout messageSection() {
        this.saveNameInput = UIComponents.textBox(Sizing.fill(100), this.saveMessage);
        this.saveNameInput.setHint(Component.translatable("luma.save.name_help"));
        this.saveNameInput.onChanged().subscribe(value -> this.saveMessage = value);
        this.setInitialFocus(this.saveNameInput);
        this.saveNameInput.setFocused(true);
        return LumaUi.formField(
                Component.translatable("luma.save.name_input"),
                Component.translatable("luma.save.name_help"),
                this.saveNameInput
        );
    }

    private FlowLayout primaryActions(boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save.actions_title"),
                null
        );
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent saveButton = LumaUi.primaryButton(Component.translatable("luma.action.save"), button -> {
            String result = this.controller.saveVersion(this.projectName, this.saveMessage);
            if ("luma.status.save_started".equals(result)) {
                this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result);
                return;
            }
            this.refresh(result);
        });
        saveButton.active(!operationActive);
        actions.child(saveButton);
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.onClose()));
        section.child(actions);
        return section;
    }

    private FlowLayout moreSection(ProjectVersion activeHead, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.more.title"),
                Component.translatable("luma.save.more_help")
        );
        FlowLayout toggle = LumaUi.actionRow();
        toggle.child(LumaUi.button(Component.translatable(
                this.showMoreOptions ? "luma.action.hide_tools" : "luma.action.more_tools"
        ), button -> {
            this.showMoreOptions = !this.showMoreOptions;
            this.rebuild();
        }));
        section.child(toggle);

        if (!this.showMoreOptions) {
            return section;
        }

        FlowLayout expanded = LumaUi.revealGroup();
        ButtonComponent replaceButton = LumaUi.button(Component.translatable("luma.action.amend_version"), button -> {
            String result = this.controller.amendVersion(this.projectName, this.saveMessage);
            if ("luma.status.amend_started".equals(result)) {
                this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result);
                return;
            }
            this.refresh(result);
        });
        replaceButton.active(activeHead != null && !operationActive);
        expanded.child(replaceButton);
        if (activeHead != null) {
            expanded.child(LumaUi.caption(Component.translatable(
                    "luma.save.replace_help",
                    ProjectUiSupport.displayMessage(activeHead)
            )));
        } else {
            expanded.child(LumaUi.caption(Component.translatable("luma.save.replace_unavailable")));
        }
        section.child(expanded);
        return section;
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.rebuild();
    }

    private void rebuild() {
        double scrollProgress = this.currentScrollProgress();
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(scrollProgress);
    }

    private double currentScrollProgress() {
        return this.bodyScroll == null ? 0.0D : this.bodyScroll.progress();
    }

    private void restoreScroll(double scrollProgress) {
        if (this.bodyScroll != null) {
            this.bodyScroll.restoreProgress(scrollProgress);
        }
    }
}
