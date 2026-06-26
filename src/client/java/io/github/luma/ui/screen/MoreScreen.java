package io.github.luma.ui.screen;

import io.github.luma.LumaMod;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.client.update.ManualUpdateCheckController;
import io.github.luma.client.update.UpdateCheckService;
import io.github.luma.client.update.UpdateProjectNotice;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.ContextualHelpPresenter;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.screen.section.InfoDialogView;
import io.github.luma.ui.screen.section.UpdateNoticeDialogView;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.StackLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.net.URI;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class MoreScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectHomeScreenController controller = new ProjectHomeScreenController();
    private final ProjectScreenController projectController = new ProjectScreenController();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ClientContextualHelpService contextualHelpService = new ClientContextualHelpService();
    private final UpdateCheckService updateCheckService = UpdateCheckService.getInstance();
    private final ManualUpdateCheckController updateCheckController = new ManualUpdateCheckController(this.updateCheckService);
    private final UpdateNoticeDialogView updateNoticeDialogView = new UpdateNoticeDialogView(new UpdateDialogActions());
    private final InfoDialogView infoDialogView = new InfoDialogView(new InfoDialogActions());
    private ProjectHomeViewState state;
    private List<ProjectVersion> deletedVersions = List.of();
    private MoreTab activeTab = MoreTab.PROJECT_TOOLS;
    private String status = "luma.status.project_ready";
    private boolean updateCheckInProgress = false;
    private ManualUpdateCheckController.Result updateCheckResult;

    public MoreScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.more.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, this.status, false);
        this.deletedVersions = this.controller.loadDeletedVersions(this.projectName);

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        if (this.state.project() == null) {
            FlowLayout frame = LumaUi.screenFrame();
            root.child(frame);
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        StackLayout stack = UIContainers.stack(Sizing.fill(100), Sizing.fill(100));
        root.child(stack);

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.more.title"),
                this.state.project(),
                this.state.variants()
        );
        stack.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.MORE);
        if (!"luma.status.project_ready".equals(this.status)) {
            window.content().child(LumaUi.statusBanner(Component.translatable(this.status)));
        }
        window.content().child(LumaUi.caption(Component.translatable("luma.more.help")));
        FlowLayout body = LumaUi.screenBody();
        window.content().child(LumaUi.screenScroll(body));

        new ContextualHelpPresenter(this.contextualHelpService, this::rebuild)
                .addHint(body, ClientContextualHelpHint.MORE);
        body.child(this.tabRow());
        if (this.activeTab == MoreTab.PROJECT_TOOLS) {
            body.child(this.onboardingSection());
            body.child(this.specialThanksSection());
            body.child(this.navigationCard(
                    "luma.more.cleanup_title",
                    "luma.more.cleanup_help",
                    "luma.action.open_cleanup",
                    button -> this.router.openCleanup(this, this.projectName)
            ));
            body.child(this.navigationCard(
                    "luma.advanced.actions_title",
                    "luma.compare.manual_help",
                    "luma.action.manual_compare",
                    button -> this.router.openCompare(this, this.projectName, "", "")
            ));
            body.child(this.updateCheckSection());
        } else {
            body.child(this.deletedSavesSection());
        }
        body.child(LumaUi.bottomSpacer());

        this.updateOverlay().ifPresent(stack::child);
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout onboardingSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.more.onboarding_title"),
                Component.translatable("luma.more.onboarding_help")
        );
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.show_onboarding"), button -> this.router.openOnboarding(
                this,
                this.projectName
        )));
        actions.child(LumaUi.button(Component.translatable("luma.action.reset_contextual_hints"), button -> {
            this.contextualHelpService.resetHints();
            this.rebuild();
        }));
        section.child(actions);
        return section;
    }

    private FlowLayout specialThanksSection() {
        return this.navigationCard(
                "luma.more.special_thanks_title",
                "luma.more.special_thanks_help",
                "luma.action.open_special_thanks",
                button -> this.router.openSpecialThanks(this, this.projectName)
        );
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    private FlowLayout navigationCard(
            String titleKey,
            String helpKey,
            String buttonKey,
            java.util.function.Consumer<io.wispforest.owo.ui.component.ButtonComponent> action
    ) {
        FlowLayout card = LumaUi.sectionCard(
                Component.translatable(titleKey),
                Component.translatable(helpKey)
        );
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable(buttonKey), action));
        card.child(actions);
        return card;
    }

    private FlowLayout tabRow() {
        FlowLayout row = LumaUi.actionRow();
        ButtonComponent tools = LumaUi.button(Component.translatable("luma.more.tab_project_tools"), button -> {
            this.activeTab = MoreTab.PROJECT_TOOLS;
            this.rebuild();
        });
        tools.active(this.activeTab != MoreTab.PROJECT_TOOLS);
        row.child(tools);

        ButtonComponent deleted = LumaUi.button(Component.translatable("luma.more.deleted_saves_title"), button -> {
            this.activeTab = MoreTab.DELETED_SAVES;
            this.rebuild();
        });
        deleted.active(this.activeTab != MoreTab.DELETED_SAVES);
        row.child(deleted);
        return row;
    }

    private FlowLayout deletedSavesSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.more.deleted_saves_title"),
                Component.translatable("luma.more.deleted_saves_help")
        );
        if (this.deletedVersions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.more.deleted_saves_empty")));
            return section;
        }

        for (ProjectVersion version : this.deletedVersions) {
            FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
            card.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
            card.child(LumaUi.caption(Component.translatable(
                    "luma.history.version_meta",
                    ProjectUiSupport.safeText(version.author()),
                    ProjectUiSupport.formatTimestamp(version.createdAt())
            )));
            card.child(LumaUi.caption(Component.translatable(
                    "luma.save_details.raw_info_type",
                    Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))
            )));
            card.child(LumaUi.caption(Component.translatable("luma.advanced.raw_save_id", version.id())));
            FlowLayout actions = LumaUi.actionRow();
            actions.child(LumaUi.primaryButton(Component.translatable("luma.action.restore_deleted_save"), button ->
                    this.restoreDeletedSave(version.id())));
            card.child(actions);
            section.child(card);
        }
        return section;
    }

    private void restoreDeletedSave(String versionId) {
        this.status = this.projectController.restoreDeletedVersion(this.projectName, versionId);
        this.rebuild();
    }

    private FlowLayout updateCheckSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.more.updates_title"),
                Component.translatable("luma.more.updates_help")
        );
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent check = LumaUi.button(
                Component.translatable(this.updateCheckInProgress
                        ? "luma.action.checking_updates"
                        : "luma.action.check_updates"),
                button -> this.requestManualUpdateCheck()
        );
        check.active(!this.updateCheckInProgress);
        actions.child(check);
        section.child(actions);
        return section;
    }

    private java.util.Optional<FlowLayout> updateOverlay() {
        if (this.updateCheckResult == null) {
            return java.util.Optional.empty();
        }
        if (this.updateCheckResult.status() == ManualUpdateCheckController.Status.UPDATE_AVAILABLE
                && this.updateCheckResult.notice().isPresent()) {
            return java.util.Optional.of(this.updateNoticeDialogView.overlay(new UpdateNoticeDialogView.Model(
                    this.width,
                    this.updateCheckResult.notice().orElseThrow()
            )));
        }
        if (this.updateCheckResult.status() == ManualUpdateCheckController.Status.UP_TO_DATE) {
            return java.util.Optional.of(this.infoDialogView.overlay(new InfoDialogView.Model(
                    this.width,
                    Component.translatable("luma.update.up_to_date_title"),
                    Component.translatable("luma.update.up_to_date_body"),
                    Component.translatable("luma.action.ok")
            )));
        }
        return java.util.Optional.of(this.infoDialogView.overlay(new InfoDialogView.Model(
                this.width,
                Component.translatable("luma.update.check_failed_title"),
                Component.translatable("luma.update.check_failed_body"),
                Component.translatable("luma.action.ok")
        )));
    }

    private void requestManualUpdateCheck() {
        if (this.updateCheckInProgress) {
            return;
        }
        this.updateCheckInProgress = true;
        this.updateCheckResult = null;
        this.rebuild();
        this.updateCheckController.checkNow().thenAccept(result -> this.client.execute(() -> {
            if (this.client.screen != this) {
                return;
            }
            this.updateCheckInProgress = false;
            this.updateCheckResult = result;
            this.rebuild();
        }));
    }

    private java.util.Optional<UpdateProjectNotice> currentUpdateNotice() {
        return this.updateCheckResult == null ? java.util.Optional.empty() : this.updateCheckResult.notice();
    }

    private void skipUpdate(UpdateProjectNotice notice) {
        this.updateCheckService.dismissVersion(notice.version());
        this.updateCheckResult = null;
        this.rebuild();
    }

    private void downloadUpdate(UpdateProjectNotice notice) {
        try {
            Util.getPlatform().openUri(URI.create(notice.downloadUrl()));
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Failed to open Lumi update download URL {}", notice.downloadUrl(), exception);
        } finally {
            this.updateCheckService.snoozeVersion(notice.version());
            this.updateCheckResult = null;
            this.rebuild();
        }
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private final class UpdateDialogActions implements UpdateNoticeDialogView.Actions {

        @Override
        public void skip() {
            currentUpdateNotice().ifPresent(MoreScreen.this::skipUpdate);
        }

        @Override
        public void download() {
            currentUpdateNotice().ifPresent(MoreScreen.this::downloadUpdate);
        }
    }

    private final class InfoDialogActions implements InfoDialogView.Actions {

        @Override
        public void close() {
            updateCheckResult = null;
            rebuild();
        }
    }

    private enum MoreTab {
        PROJECT_TOOLS,
        DELETED_SAVES
    }
}
