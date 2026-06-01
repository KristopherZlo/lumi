package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.ui.ContextualHelpPresenter;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.RecoveryScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.screen.section.ConfirmationDialogView;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.StackLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RecoveryScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final RecoveryScreenController controller = new RecoveryScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final ClientContextualHelpService contextualHelpService = new ClientContextualHelpService();
    private final ConfirmationDialogView restoreDialogView = new ConfirmationDialogView(new RestoreDialogActions());
    private final ConfirmationDialogView deleteDialogView = new ConfirmationDialogView(new DeleteDialogActions());
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private String status = "luma.status.recovery_ready";
    private boolean showDetails = false;
    private boolean confirmRestore = false;
    private boolean confirmDelete = false;
    private String saveMessage = "";
    private TextBoxComponent saveMessageInput;

    public RecoveryScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.recovery.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        Optional<io.github.luma.domain.model.RecoveryDraftSummary> draftSummary = this.controller.loadSummary(this.projectName);

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        StackLayout stack = UIContainers.stack(Sizing.fill(100), Sizing.fill(100));
        root.child(stack);

        FlowLayout frame = LumaUi.screenFrame();
        stack.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(LumaUi.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName
        )));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.recovery.title")));
        frame.child(LumaUi.statusBanner(Component.translatable(this.status)));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        if (draftSummary.isEmpty()) {
            FlowLayout empty = LumaUi.emptyState(
                    Component.translatable("luma.recovery.empty_title"),
                    Component.translatable("luma.recovery.no_draft")
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

        var summaryState = draftSummary.get();
        new ContextualHelpPresenter(this.contextualHelpService, this::rebuild)
                .addHint(body, ClientContextualHelpHint.RECOVERY);
        body.child(this.summarySection(summaryState));
        body.child(this.saveSection(summaryState));
        body.child(this.actionSection());
        body.child(this.detailsSection(summaryState));
        body.child(LumaUi.bottomSpacer());

        if (this.confirmRestore) {
            stack.child(this.restoreDialogView.overlay(this.restoreDialogModel()));
        } else if (this.confirmDelete) {
            stack.child(this.deleteDialogView.overlay(this.deleteDialogModel()));
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout summarySection(io.github.luma.domain.model.RecoveryDraftSummary summaryState) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.recovery.summary_title"),
                Component.translatable("luma.recovery.summary_help")
        );
        section.child(LumaUi.caption(Component.translatable("luma.recovery.summary_behavior")));

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_blocks"),
                Component.literal(Integer.toString(summaryState.changeCount()))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_chunks"),
                Component.literal(Integer.toString(summaryState.touchedChunkCount()))
        ));
        section.child(stats);
        return section;
    }

    private FlowLayout saveSection(io.github.luma.domain.model.RecoveryDraftSummary summaryState) {
        this.saveMessageInput = UIComponents.textBox(Sizing.fill(100), this.saveMessage);
        this.saveMessageInput.setHint(Component.translatable("luma.recovery.message_help"));
        this.saveMessageInput.onChanged().subscribe(value -> this.saveMessage = value);

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.recovery.save_title"),
                Component.translatable(
                        "luma.recovery.save_help",
                        this.safeText(summaryState.variantId())
                )
        );
        section.child(LumaUi.formField(
                Component.translatable("luma.recovery.message_title"),
                Component.translatable("luma.recovery.message_help"),
                this.saveMessageInput
        ));
        return section;
    }

    private FlowLayout actionSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.recovery.actions_title"),
                Component.translatable("luma.recovery.actions_help")
        );

        FlowLayout primary = LumaUi.actionRow();
        primary.child(LumaUi.primaryButton(Component.translatable("luma.action.recovery_restore"), button -> {
            this.confirmRestore = true;
            this.confirmDelete = false;
            this.rebuild();
        }));
        primary.child(LumaUi.primaryButton(Component.translatable("luma.action.recovery_save"), button -> {
            this.status = this.controller.saveDraftVersion(this.projectName, this.saveMessage);
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        primary.child(LumaUi.button(Component.translatable("luma.action.recovery_discard"), button -> {
            this.confirmDelete = true;
            this.confirmRestore = false;
            this.rebuild();
        }));
        section.child(primary);
        return section;
    }

    private ConfirmationDialogView.Model restoreDialogModel() {
        return new ConfirmationDialogView.Model(
                this.width,
                Component.translatable("luma.recovery.restore_confirm_title"),
                Component.translatable("luma.recovery.restore_confirm_help"),
                null,
                Component.translatable("luma.action.recovery_restore"),
                false
        );
    }

    private ConfirmationDialogView.Model deleteDialogModel() {
        return new ConfirmationDialogView.Model(
                this.width,
                Component.translatable("luma.recovery.delete_confirm_title"),
                Component.translatable("luma.recovery.delete_confirm_help"),
                Component.translatable("luma.recovery.delete_confirm_warning"),
                Component.translatable("luma.action.delete"),
                false
        );
    }

    private FlowLayout detailsSection(io.github.luma.domain.model.RecoveryDraftSummary summaryState) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.more.title"),
                Component.translatable("luma.recovery.details_help")
        );

        FlowLayout toggle = LumaUi.actionRow();
        toggle.child(LumaUi.button(Component.translatable(
                this.showDetails ? "luma.action.hide_tools" : "luma.action.more_tools"
        ), button -> {
            this.showDetails = !this.showDetails;
            this.rebuild();
        }));
        section.child(toggle);

        if (!this.showDetails) {
            return section;
        }

        FlowLayout expanded = LumaUi.revealGroup();
        expanded.child(LumaUi.caption(Component.translatable("luma.variant.entry_base", this.safeText(summaryState.baseVersionId()))));
        expanded.child(LumaUi.caption(Component.translatable("luma.variant.entry_head", this.safeText(summaryState.headVersionId()))));
        expanded.child(LumaUi.caption(Component.translatable("luma.log.entry_variant", this.safeText(summaryState.variantId()))));
        section.child(expanded);
        return section;
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void rebuild() {
        this.rebuildPreservingScroll(() -> this.bodyScroll);
    }

    private final class RestoreDialogActions implements ConfirmationDialogView.Actions {

        @Override
        public void confirm() {
            status = controller.restoreDraft(projectName);
            router.openProjectIgnoringRecovery(parent, projectName, status);
        }

        @Override
        public void cancel() {
            confirmRestore = false;
            rebuild();
        }
    }

    private final class DeleteDialogActions implements ConfirmationDialogView.Actions {

        @Override
        public void confirm() {
            status = controller.discardDraft(projectName);
            router.openProjectIgnoringRecovery(parent, projectName, status);
        }

        @Override
        public void cancel() {
            confirmDelete = false;
            rebuild();
        }
    }
}
