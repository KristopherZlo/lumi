package io.github.luma.ui.screen;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.MaterialDeltaEntry;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectCleanupReport;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraftSummary;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CleanupScreenController;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.CreateProjectScreenController;
import io.github.luma.ui.controller.DashboardScreenController;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.RecoveryScreenController;
import io.github.luma.ui.controller.SettingsScreenController;
import io.github.luma.ui.controller.ShareScreenController;
import io.github.luma.ui.controller.VariantsScreenController;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.state.CompareViewState;
import io.github.luma.ui.state.DashboardProjectItem;
import io.github.luma.ui.state.DashboardViewState;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.state.SaveDetailsViewState;
import io.github.luma.ui.state.SaveViewState;
import io.github.luma.ui.state.ShareViewState;
import io.github.luma.ui.state.VariantsViewState;
import io.github.luma.ui.toolkit.LdLib2ReflectiveUi;
import io.github.luma.ui.toolkit.LdLib2ReflectiveUi.TextTone;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class LdLib2Screens {

    private static final int RECENT_SAVE_LIMIT = 10;

    private LdLib2Screens() {
    }

    public static Screen dashboard(Screen parent) {
        return dashboard(parent, "luma.status.dashboard_ready");
    }

    public static Screen dashboard(Screen parent, String statusKey) {
        DashboardScreenController controller = new DashboardScreenController();
        DashboardViewState state = controller.loadState(statusKey);
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];

        Object root = root(ui, "lumi-dashboard-root");
        Object frame = frame(ui, "lumi-dashboard-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-dashboard-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-dashboard-title", Component.translatable("luma.screen.dashboard.title")));
        ui.addChild(frame, status(ui, "lumi-dashboard-status", Component.translatable(state.status())));

        Object body = body(ui, frame, "lumi-dashboard-body");
        DashboardProjectItem primary = primaryWorkspace(state.projects());
        if (primary == null) {
            ui.addScrollChild(body, empty(ui, "lumi-dashboard-empty", "luma.dashboard.empty_title", "luma.dashboard.empty"));
        } else {
            ui.addScrollChild(body, dashboardWorkspace(ui, self, primary, true));
            state.projects().stream()
                    .filter(item -> !item.archived())
                    .filter(DashboardProjectItem::worldWorkspace)
                    .filter(item -> !item.name().equals(primary.name()))
                    .forEach(item -> ui.addScrollChild(body, dashboardWorkspace(ui, self, item, false)));
        }

        self[0] = ui.screen(root, Component.translatable("luma.screen.dashboard.title"));
        return self[0];
    }

    public static Screen createProject(Screen parent) {
        CreateProjectScreenController controller = new CreateProjectScreenController();
        BlockPos center = controller.suggestedCenter();
        return createProject(
                parent,
                "luma.status.create_ready",
                "",
                Integer.toString(center.getX() - 8),
                Integer.toString(center.getY() - 8),
                Integer.toString(center.getZ() - 8),
                Integer.toString(center.getX() + 8),
                Integer.toString(center.getY() + 8),
                Integer.toString(center.getZ() + 8)
        );
    }

    private static Screen createProject(
            Screen parent,
            String statusKey,
            String name,
            String minX,
            String minY,
            String minZ,
            String maxX,
            String maxY,
            String maxZ
    ) {
        CreateProjectScreenController controller = new CreateProjectScreenController();
        LdLib2ReflectiveUi ui = runtime();
        String[] values = {name, minX, minY, minZ, maxX, maxY, maxZ};
        Screen[] self = new Screen[1];

        Object root = root(ui, "lumi-create-root");
        Object frame = frame(ui, "lumi-create-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-create-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-create-title", Component.translatable("luma.screen.create_project.title")));
        ui.addChild(frame, status(ui, "lumi-create-status", Component.translatable(statusKey)));
        ui.addChild(frame, field(ui, "lumi-create-name", Component.translatable("luma.create_project.name"),
                ui.textField("lumi-create-name-input", values[0], Component.translatable("luma.create_project.name"), value -> values[0] = value)));
        ui.addChild(frame, coordinateRow(ui, "lumi-create-min", "luma.create_project.min", values, 1));
        ui.addChild(frame, coordinateRow(ui, "lumi-create-max", "luma.create_project.max", values, 4));
        ui.addChild(frame, button(ui, "lumi-create-submit", Component.translatable("luma.action.create_project"), () -> {
            String result = controller.createProject(
                    values[0],
                    new BlockPos(parseInt(values[1]), parseInt(values[2]), parseInt(values[3])),
                    new BlockPos(parseInt(values[4]), parseInt(values[5]), parseInt(values[6]))
            );
            if ("luma.status.project_created".equals(result)) {
                CLIENT().setScreen(project(parent, values[0], "", result));
            } else {
                CLIENT().setScreen(createProject(parent, result, values[0], values[1], values[2], values[3], values[4], values[5], values[6]));
            }
        }));

        self[0] = ui.screen(root, Component.translatable("luma.screen.create_project.title"));
        return self[0];
    }

    public static Screen project(Screen parent, String projectName) {
        return project(parent, projectName, "", "luma.status.project_ready");
    }

    public static Screen project(Screen parent, String projectName, String statusKey) {
        return project(parent, projectName, "", statusKey);
    }

    public static Screen project(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        return LdLib2ProjectHomeScreenFactory.create(parent, projectName, selectedVariantId, statusKey)
                .orElseThrow(() -> new IllegalStateException("LDLib2 project home screen is required."));
    }

    public static Screen more(Screen parent, String projectName) {
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-more-root");
        Object frame = frame(ui, "lumi-more-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-more-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-more-title", Component.translatable("luma.screen.more.title")));
        ui.addChild(frame, caption(ui, "lumi-more-help", Component.translatable("luma.more.help")));
        Object body = body(ui, frame, "lumi-more-body");
        navigationCard(ui, body, "projects", "luma.more.projects_title", "luma.more.projects_help", "luma.action.open_projects",
                () -> CLIENT().setScreen(dashboard(self[0])));
        navigationCard(ui, body, "share", "luma.more.import_export_title", "luma.more.import_export_help", "luma.action.open_import_export",
                () -> CLIENT().setScreen(share(self[0], projectName)));
        navigationCard(ui, body, "settings", "luma.more.settings_title", "luma.more.settings_help", "luma.action.settings",
                () -> CLIENT().setScreen(settings(self[0], projectName)));
        navigationCard(ui, body, "cleanup", "luma.more.cleanup_title", "luma.more.cleanup_help", "luma.action.open_cleanup",
                () -> CLIENT().setScreen(cleanup(self[0], projectName)));
        navigationCard(ui, body, "diagnostics", "luma.more.diagnostics_title", "luma.more.diagnostics_help", "luma.action.open_diagnostics",
                () -> CLIENT().setScreen(diagnostics(self[0], projectName)));
        navigationCard(ui, body, "advanced", "luma.more.advanced_title", "luma.more.advanced_help", "luma.action.open_advanced",
                () -> CLIENT().setScreen(advanced(self[0], projectName)));
        self[0] = ui.screen(root, Component.translatable("luma.screen.more.title"));
        return self[0];
    }

    public static Screen save(Screen parent, String projectName) {
        return save(parent, projectName, "", false);
    }

    public static Screen save(Screen parent, String projectName, String initialMessage, boolean showMoreOptions) {
        ProjectScreenController controller = new ProjectScreenController();
        SaveViewState state = controller.loadSaveState(projectName, "luma.status.project_ready");
        String[] message = {initialMessage == null ? "" : initialMessage};
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];

        Object root = root(ui, "lumi-save-root");
        Object frame = frame(ui, "lumi-save-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-save-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-save-title", Component.translatable("luma.screen.save.title")));
        ui.addChild(frame, status(ui, "lumi-save-status", Component.translatable(state.status())));
        Object body = body(ui, frame, "lumi-save-body");

        if (state.project() == null) {
            ui.addScrollChild(body, empty(ui, "lumi-save-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            PendingChangeSummary pending = controller.summarizePending(state.recoveryDraft());
            Object summary = section(ui, "lumi-save-summary", Component.translatable("luma.save.summary_title"), Component.translatable("luma.save.summary_help"));
            stats(ui, summary, "lumi-save-stats",
                    statLabel("luma.dashboard.pending_added", pending.addedBlocks()),
                    statLabel("luma.dashboard.pending_changed", pending.changedBlocks()),
                    statLabel("luma.dashboard.pending_removed", pending.removedBlocks()));
            ui.addScrollChild(body, summary);
            ui.addScrollChild(body, field(ui, "lumi-save-message", Component.translatable("luma.save.name_help"),
                    ui.textField("lumi-save-message-input", message[0], Component.translatable("luma.save.name_help"), value -> message[0] = value)));
            Object actions = section(ui, "lumi-save-actions", Component.translatable("luma.save.actions_title"), Component.translatable("luma.save.summary_help"));
            ui.addChild(actions, row(ui, "lumi-save-action-row",
                    button(ui, "lumi-save-submit", Component.translatable("luma.action.save"), () -> {
                        String result = controller.saveVersion(projectName, message[0]);
                        CLIENT().setScreen(project(parent, projectName, result));
                    }),
                    button(ui, "lumi-save-amend", Component.translatable("luma.action.amend_version"), () -> {
                        String result = controller.amendVersion(projectName, message[0]);
                        CLIENT().setScreen(project(parent, projectName, result));
                    })));
            ui.addScrollChild(body, actions);
        }

        self[0] = ui.screen(root, Component.translatable("luma.screen.save.title"));
        return self[0];
    }

    public static Screen saveDetails(Screen parent, String projectName, String versionId) {
        ProjectScreenController controller = new ProjectScreenController();
        SaveDetailsViewState state = controller.loadSaveDetailsState(projectName, versionId, "luma.status.project_ready");
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-save-details-root");
        Object frame = frame(ui, "lumi-save-details-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-save-details-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-save-details-title", Component.translatable("luma.screen.save_details.title")));
        ui.addChild(frame, status(ui, "lumi-save-details-status", Component.translatable(state.status())));
        Object body = body(ui, frame, "lumi-save-details-body");

        ProjectVersion version = state.selectedVersion();
        if (state.project() == null || version == null) {
            ui.addScrollChild(body, empty(ui, "lumi-save-details-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            Object summary = section(ui, "lumi-save-details-summary", Component.literal(ProjectUiSupport.displayMessage(version)),
                    Component.translatable("luma.history.version_meta", ProjectUiSupport.safeText(version.author()), ProjectUiSupport.formatTimestamp(version.createdAt())));
            ui.addChild(summary, caption(ui, "lumi-save-details-kind", Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))));
            if (version.stats() != null) {
                stats(ui, summary, "lumi-save-details-stats",
                        statLabel("luma.simple.blocks_changed", version.stats().changedBlocks()),
                        statLabel("luma.history.commit_chunks", version.stats().changedChunks()),
                        statLabel("luma.history.commit_blocks", version.stats().distinctBlockTypes()));
            }
            ui.addScrollChild(body, summary);
            ui.addScrollChild(body, diffSection(ui, state.selectedVersionDiff(), state.materialDelta()));
            Object actions = section(ui, "lumi-save-details-actions", Component.translatable("luma.save_details.actions_title"), Component.translatable("luma.save_details.actions_help"));
            ui.addChild(actions, row(ui, "lumi-save-details-action-row",
                    button(ui, "lumi-save-details-restore", Component.translatable("luma.action.restore_this_save"), () -> {
                        String result = controller.restoreVersion(projectName, version.id());
                        CLIENT().setScreen(project(parent, projectName, result));
                    }),
                    button(ui, "lumi-save-details-compare", Component.translatable("luma.action.see_changes"), () ->
                            CLIENT().setScreen(compare(self[0], projectName, version.parentVersionId(), version.id(), version.id()))),
                    button(ui, "lumi-save-details-preview", Component.translatable("luma.action.refresh_preview"), () -> {
                        String result = controller.refreshPreview(projectName, version.id());
                        CLIENT().setScreen(saveDetails(parent, projectName, version.id(), result));
                    })));
            ui.addScrollChild(body, actions);
        }

        self[0] = ui.screen(root, Component.translatable("luma.screen.save_details.title"));
        return self[0];
    }

    private static Screen saveDetails(Screen parent, String projectName, String versionId, String status) {
        ProjectScreenController controller = new ProjectScreenController();
        SaveDetailsViewState state = controller.loadSaveDetailsState(projectName, versionId, status);
        return saveDetailsFromState(parent, projectName, state);
    }

    private static Screen saveDetailsFromState(Screen parent, String projectName, SaveDetailsViewState state) {
        return saveDetails(parent, projectName, state.selectedVersion() == null ? "" : state.selectedVersion().id());
    }

    public static Screen recovery(Screen parent, String projectName) {
        return recovery(parent, projectName, "luma.status.recovery_ready", "");
    }

    private static Screen recovery(Screen parent, String projectName, String statusKey, String message) {
        RecoveryScreenController controller = new RecoveryScreenController();
        RecoveryDraftSummary summary = controller.loadSummary(projectName).orElse(null);
        String[] saveMessage = {message == null ? "" : message};
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-recovery-root");
        Object frame = frame(ui, "lumi-recovery-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-recovery-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-recovery-title", Component.translatable("luma.screen.recovery.title")));
        ui.addChild(frame, status(ui, "lumi-recovery-status", Component.translatable(statusKey)));
        Object body = body(ui, frame, "lumi-recovery-body");
        if (summary == null || summary.changeCount() <= 0) {
            ui.addScrollChild(body, empty(ui, "lumi-recovery-empty", "luma.recovery.empty_title", "luma.recovery.empty"));
        } else {
            Object summarySection = section(ui, "lumi-recovery-summary", Component.translatable("luma.recovery.summary_title"), Component.translatable("luma.recovery.summary_behavior"));
            stats(ui, summarySection, "lumi-recovery-stats",
                    statLabel("luma.recovery.changed_blocks", summary.changeCount()),
                    statLabel("luma.recovery.touched_chunks", summary.touchedChunkCount()));
            ui.addScrollChild(body, summarySection);
            ui.addScrollChild(body, field(ui, "lumi-recovery-message", Component.translatable("luma.recovery.message_help"),
                    ui.textField("lumi-recovery-message-input", saveMessage[0], Component.translatable("luma.recovery.message_help"), value -> saveMessage[0] = value)));
            Object actions = section(ui, "lumi-recovery-actions", Component.translatable("luma.recovery.actions_title"), Component.translatable("luma.recovery.actions_help"));
            ui.addChild(actions, row(ui, "lumi-recovery-action-row",
                    button(ui, "lumi-recovery-restore", Component.translatable("luma.action.recovery_restore"), () -> {
                        String result = controller.restoreDraft(projectName);
                        CLIENT().setScreen(project(parent, projectName, result));
                    }),
                    button(ui, "lumi-recovery-save", Component.translatable("luma.action.recovery_save"), () -> {
                        String result = controller.saveDraftVersion(projectName, saveMessage[0]);
                        CLIENT().setScreen(project(parent, projectName, result));
                    }),
                    button(ui, "lumi-recovery-discard", Component.translatable("luma.action.recovery_discard"), () -> {
                        String result = controller.discardDraft(projectName);
                        CLIENT().setScreen(project(parent, projectName, result));
                    })));
            ui.addScrollChild(body, actions);
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.recovery.title"));
        return self[0];
    }

    public static Screen settings(Screen parent, String projectName) {
        SettingsScreenController controller = new SettingsScreenController();
        var project = controller.loadProject(projectName);
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-settings-root");
        Object frame = frame(ui, "lumi-settings-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-settings-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-settings-title", Component.translatable("luma.screen.settings.title", projectName)));
        Object body = body(ui, frame, "lumi-settings-body");
        if (project == null) {
            ui.addScrollChild(body, empty(ui, "lumi-settings-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            ProjectSettings settings = project.settings();
            boolean[] toggles = {settings.safetySnapshotBeforeRestore(), settings.previewGenerationEnabled(), settings.debugLoggingEnabled()};
            String[] values = {
                    Integer.toString(settings.sessionIdleSeconds()),
                    Integer.toString(settings.snapshotEveryVersions()),
                    Double.toString(settings.snapshotVolumeThreshold())
            };
            ui.addScrollChild(body, toggleField(ui, "lumi-settings-safety", "luma.settings.safety_snapshot", toggles, 0));
            ui.addScrollChild(body, toggleField(ui, "lumi-settings-preview", "luma.settings.preview_generation", toggles, 1));
            ui.addScrollChild(body, toggleField(ui, "lumi-settings-debug", "luma.settings.debug_logging", toggles, 2));
            ui.addScrollChild(body, field(ui, "lumi-settings-idle", Component.translatable("luma.settings.idle_seconds"),
                    ui.textField("lumi-settings-idle-input", values[0], null, value -> values[0] = value)));
            ui.addScrollChild(body, field(ui, "lumi-settings-snapshot-every", Component.translatable("luma.settings.snapshot_every"),
                    ui.textField("lumi-settings-snapshot-every-input", values[1], null, value -> values[1] = value)));
            ui.addScrollChild(body, field(ui, "lumi-settings-threshold", Component.translatable("luma.settings.snapshot_volume"),
                    ui.textField("lumi-settings-threshold-input", values[2], null, value -> values[2] = value)));
            Object actions = section(ui, "lumi-settings-actions", Component.translatable("luma.settings.save_title"), Component.translatable("luma.settings.save_help"));
            ui.addChild(actions, button(ui, "lumi-settings-save", Component.translatable("luma.action.save_settings"), () -> {
                ProjectSettings updated = new ProjectSettings(
                        settings.autoVersionsEnabled(),
                        settings.autoVersionMinutes(),
                        parsePositiveInt(values[0], settings.sessionIdleSeconds()),
                        parsePositiveInt(values[1], settings.snapshotEveryVersions()),
                        parsePositiveDouble(values[2], settings.snapshotVolumeThreshold()),
                        toggles[0],
                        toggles[1],
                        toggles[2]
                );
                String result = controller.saveAll(projectName, updated, project.archived());
                CLIENT().setScreen(project(parent, projectName, result));
            }));
            ui.addScrollChild(body, actions);
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.settings.title", projectName));
        return self[0];
    }

    public static Screen variants(Screen parent, String projectName) {
        return variants(parent, projectName, "");
    }

    public static Screen variants(Screen parent, String projectName, String baseVersionId) {
        VariantsScreenController stateController = new VariantsScreenController();
        ProjectScreenController actionController = new ProjectScreenController();
        VariantsViewState state = stateController.loadState(projectName, "luma.status.project_ready");
        String[] variantName = {""};
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-variants-root");
        Object frame = frame(ui, "lumi-variants-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-variants-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-variants-title", Component.translatable("luma.screen.ideas.title", projectName)));
        ui.addChild(frame, status(ui, "lumi-variants-status", Component.translatable(state.status())));
        Object body = body(ui, frame, "lumi-variants-body");
        if (state.project() == null) {
            ui.addScrollChild(body, empty(ui, "lumi-variants-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            ui.addScrollChild(body, field(ui, "lumi-variants-create", Component.translatable("luma.idea.create_title"),
                    ui.textField("lumi-variants-name-input", variantName[0], Component.translatable("luma.idea.name_input"), value -> variantName[0] = value)));
            ui.addScrollChild(body, button(ui, "lumi-variants-create-button", Component.translatable("luma.action.create_idea"), () -> {
                String result = actionController.createVariant(projectName, variantName[0], baseVersionId);
                CLIENT().setScreen(variants(parent, projectName, baseVersionId, result));
            }));
            for (ProjectVariant variant : state.variants()) {
                ProjectVersion head = ProjectUiSupport.versionFor(state.versions(), variant.headVersionId());
                Object card = section(ui, id("lumi-variant", variant.id()), Component.literal(ProjectUiSupport.displayVariantName(variant)),
                        head == null ? Component.translatable("luma.ideas.no_saves") : Component.translatable("luma.variant.entry_head", head.id()));
                ui.addChild(card, row(ui, id("lumi-variant-actions", variant.id()),
                        button(ui, id("lumi-variant-switch", variant.id()), Component.translatable("luma.action.switch_idea"), () -> {
                            String result = actionController.switchVariant(projectName, variant.id());
                            CLIENT().setScreen(project(parent, projectName, variant.id(), result));
                        }),
                        button(ui, id("lumi-variant-compare", variant.id()), Component.translatable("luma.action.see_changes"), () -> {
                            String right = head == null ? "" : head.id();
                            CLIENT().setScreen(compare(self[0], projectName, "", right, right));
                        })));
                ui.addScrollChild(body, card);
            }
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.ideas.title", projectName));
        return self[0];
    }

    private static Screen variants(Screen parent, String projectName, String baseVersionId, String status) {
        return variants(parent, projectName, baseVersionId);
    }

    public static Screen compare(Screen parent, String projectName, String leftReference, String rightReference) {
        return compare(parent, projectName, leftReference, rightReference, "");
    }

    public static Screen compare(Screen parent, String projectName, String leftReference, String rightReference, String contextVersionId) {
        CompareScreenController controller = new CompareScreenController();
        CompareViewState state = controller.loadState(projectName, leftReference, rightReference, "luma.status.compare_ready");
        String[] references = {leftReference == null ? "" : leftReference, rightReference == null ? "" : rightReference};
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-compare-root");
        Object frame = frame(ui, "lumi-compare-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-compare-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-compare-title", Component.translatable("luma.screen.compare.title")));
        ui.addChild(frame, status(ui, "lumi-compare-status", Component.translatable(state.status())));
        Object body = body(ui, frame, "lumi-compare-body");
        ui.addScrollChild(body, field(ui, "lumi-compare-left", Component.translatable("luma.compare.left"),
                ui.textField("lumi-compare-left-input", references[0], null, value -> references[0] = value)));
        ui.addScrollChild(body, field(ui, "lumi-compare-right", Component.translatable("luma.compare.right"),
                ui.textField("lumi-compare-right-input", references[1], null, value -> references[1] = value)));
        ui.addScrollChild(body, button(ui, "lumi-compare-submit", Component.translatable("luma.action.compare"), () ->
                CLIENT().setScreen(compare(parent, projectName, references[0], references[1], contextVersionId))));
        if (state.diff() != null) {
            ui.addScrollChild(body, diffSection(ui, state.diff(), state.materialDelta()));
            ui.addScrollChild(body, row(ui, "lumi-compare-actions",
                    button(ui, "lumi-compare-overlay", Component.translatable("luma.action.show_highlight"), () -> {
                        String result = controller.showOverlay(projectName, state);
                        CLIENT().setScreen(compare(parent, projectName, references[0], references[1], contextVersionId, result));
                    }),
                    button(ui, "lumi-compare-clear-overlay", Component.translatable("luma.action.hide_highlight"), () -> {
                        controller.clearOverlay();
                        CLIENT().setScreen(compare(parent, projectName, references[0], references[1], contextVersionId));
                    })));
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.compare.title"));
        return self[0];
    }

    private static Screen compare(Screen parent, String projectName, String leftReference, String rightReference, String contextVersionId, String status) {
        CompareScreenController controller = new CompareScreenController();
        CompareViewState state = controller.loadState(projectName, leftReference, rightReference, status);
        return compare(parent, projectName, state.leftReference(), state.rightReference(), contextVersionId);
    }

    public static Screen cleanup(Screen parent, String projectName) {
        return cleanup(parent, projectName, null, "luma.status.cleanup_ready");
    }

    private static Screen cleanup(Screen parent, String projectName, ProjectCleanupReport report, String statusKey) {
        CleanupScreenController controller = new CleanupScreenController();
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-cleanup-root");
        Object frame = frame(ui, "lumi-cleanup-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-cleanup-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-cleanup-title", Component.translatable("luma.screen.cleanup.title")));
        ui.addChild(frame, status(ui, "lumi-cleanup-status", Component.translatable(statusKey)));
        Object body = body(ui, frame, "lumi-cleanup-body");
        Object actions = section(ui, "lumi-cleanup-actions", Component.translatable("luma.cleanup.actions_title"), Component.translatable("luma.cleanup.actions_help"));
        ui.addChild(actions, row(ui, "lumi-cleanup-action-row",
                button(ui, "lumi-cleanup-inspect", Component.translatable("luma.action.inspect_unused_files"), () -> {
                    ProjectCleanupReport inspected = controller.inspect(projectName);
                    CLIENT().setScreen(cleanup(parent, projectName, inspected, inspected == null ? "luma.status.cleanup_failed" : "luma.status.cleanup_inspected"));
                }),
                button(ui, "lumi-cleanup-apply", Component.translatable("luma.action.clean_up"), () -> {
                    ProjectCleanupReport applied = controller.apply(projectName);
                    CLIENT().setScreen(cleanup(parent, projectName, applied, applied == null ? "luma.status.cleanup_failed" : "luma.status.cleanup_applied"));
                })));
        ui.addScrollChild(body, actions);
        if (report != null) {
            Object results = section(ui, "lumi-cleanup-results",
                    Component.translatable(report.dryRun() ? "luma.cleanup.results_title" : "luma.cleanup.applied_title"),
                    Component.translatable("luma.cleanup.results_help", report.candidates().size(), formatBytes(report.reclaimedBytes())));
            if (report.candidates().isEmpty()) {
                ui.addChild(results, caption(ui, "lumi-cleanup-empty", Component.translatable("luma.cleanup.empty")));
            }
            report.candidates().stream().limit(10).forEach(candidate -> ui.addChild(results, caption(ui, id("lumi-cleanup-candidate", candidate.relativePath()),
                    Component.translatable("luma.cleanup.candidate", candidate.relativePath(), candidate.reason(), formatBytes(candidate.sizeBytes())))));
            ui.addScrollChild(body, results);
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.cleanup.title"));
        return self[0];
    }

    public static Screen diagnostics(Screen parent, String projectName) {
        ProjectHomeScreenController controller = new ProjectHomeScreenController();
        ProjectHomeViewState state = controller.loadState(projectName, "luma.status.project_ready", true);
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-diagnostics-root");
        Object frame = frame(ui, "lumi-diagnostics-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-diagnostics-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-diagnostics-title", Component.translatable("luma.screen.diagnostics.title")));
        Object body = body(ui, frame, "lumi-diagnostics-body");
        if (state.project() == null || state.advanced() == null) {
            ui.addScrollChild(body, empty(ui, "lumi-diagnostics-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            var report = state.advanced().integrityReport();
            Object integrity = section(ui, "lumi-diagnostics-integrity", Component.translatable("luma.project.integrity_title"),
                    Component.translatable(report.valid() ? "luma.integrity.valid" : "luma.integrity.invalid"));
            report.errors().forEach(error -> ui.addChild(integrity, danger(ui, id("lumi-diagnostics-error", error), Component.translatable("luma.integrity.error", error))));
            report.warnings().forEach(warning -> ui.addChild(integrity, caption(ui, id("lumi-diagnostics-warning", warning), Component.translatable("luma.integrity.warning", warning))));
            ui.addScrollChild(body, integrity);
            Object toolkit = section(ui, "lumi-diagnostics-toolkit", Component.translatable("luma.ui_toolkit.title"), Component.translatable("luma.ui_toolkit.ldlib2_active"));
            ui.addChild(toolkit, caption(ui, "lumi-diagnostics-toolkit-gdp", Component.literal("LDLib2 GDP")));
            ui.addScrollChild(body, toolkit);
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.diagnostics.title"));
        return self[0];
    }

    public static Screen advanced(Screen parent, String projectName) {
        ProjectHomeScreenController controller = new ProjectHomeScreenController();
        ProjectHomeViewState state = controller.loadState(projectName, "luma.status.project_ready", false);
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-advanced-root");
        Object frame = frame(ui, "lumi-advanced-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-advanced-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-advanced-title", Component.translatable("luma.screen.advanced.title")));
        Object body = body(ui, frame, "lumi-advanced-body");
        if (state.project() == null) {
            ui.addScrollChild(body, empty(ui, "lumi-advanced-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            Object actions = section(ui, "lumi-advanced-actions", Component.translatable("luma.advanced.actions_title"), Component.translatable("luma.advanced.actions_help"));
            ui.addChild(actions, row(ui, "lumi-advanced-action-row",
                    button(ui, "lumi-advanced-compare", Component.translatable("luma.action.manual_compare"), () -> CLIENT().setScreen(compare(self[0], projectName, "", ""))),
                    button(ui, "lumi-advanced-create", Component.translatable("luma.action.legacy_limited_project"), () -> CLIENT().setScreen(createProject(self[0])))));
            ui.addScrollChild(body, actions);
            Object graph = section(ui, "lumi-advanced-graph", Component.translatable("luma.advanced.history_graph_title"), Component.translatable("luma.advanced.history_graph_help"));
            CommitGraphLayout.build(state.versions(), state.variants(), state.project().activeVariantId()).forEach(node -> ui.addChild(graph,
                    caption(ui, id("lumi-advanced-node", node.version().id()), Component.literal(graphPrefix(node) + " " + ProjectUiSupport.displayMessage(node.version())))));
            ui.addScrollChild(body, graph);
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.advanced.title"));
        return self[0];
    }

    public static Screen share(Screen parent, String projectName) {
        return share(parent, projectName, "", "", true);
    }

    private static Screen share(Screen parent, String projectName, String importPath, String selectedVariantId, boolean includePreviews) {
        ShareScreenController controller = new ShareScreenController();
        ShareViewState state = controller.loadState(projectName, "luma.status.share_ready");
        String[] importValue = {importPath == null ? "" : importPath};
        String[] selected = {selectedVariantId == null || selectedVariantId.isBlank()
                ? (state.project() == null ? "" : state.project().activeVariantId())
                : selectedVariantId};
        boolean[] include = {includePreviews};
        LdLib2ReflectiveUi ui = runtime();
        Screen[] self = new Screen[1];
        Object root = root(ui, "lumi-share-root");
        Object frame = frame(ui, "lumi-share-frame");
        ui.addChild(root, frame);
        header(ui, frame, "lumi-share-header", Component.translatable("luma.action.back"), () -> CLIENT().setScreen(parent));
        ui.addChild(frame, title(ui, "lumi-share-title", Component.translatable("luma.screen.import_export.title")));
        ui.addChild(frame, status(ui, "lumi-share-status", Component.translatable(state.status())));
        Object body = body(ui, frame, "lumi-share-body");
        if (state.project() == null) {
            ui.addScrollChild(body, empty(ui, "lumi-share-empty", "luma.project.unavailable", "luma.status.project_failed"));
        } else {
            Object importSection = section(ui, "lumi-share-import", Component.translatable("luma.share.import_title"), Component.translatable("luma.share.import_help"));
            ui.addChild(importSection, ui.textField("lumi-share-import-input", importValue[0], Component.translatable("luma.share.import_path"), value -> importValue[0] = value));
            ui.addChild(importSection, button(ui, "lumi-share-import-button", Component.translatable("luma.action.import_package"), () -> {
                HistoryPackageImportResult result = controller.importVariantPackage(projectName, importValue[0]);
                CLIENT().setScreen(share(parent, projectName, importValue[0], selected[0], include[0]));
            }));
            ui.addScrollChild(body, importSection);
            Object exportSection = section(ui, "lumi-share-export", Component.translatable("luma.share.export_title"), Component.translatable("luma.share.export_help"));
            for (ProjectVariant variant : state.variants()) {
                ui.addChild(exportSection, button(ui, id("lumi-share-variant", variant.id()), Component.literal(ProjectUiSupport.displayVariantName(variant)), () ->
                        CLIENT().setScreen(share(parent, projectName, importValue[0], variant.id(), include[0]))));
            }
            ui.addChild(exportSection, ui.toggle("lumi-share-preview-toggle", Component.translatable("luma.share.include_previews"), include[0], value -> include[0] = value));
            ui.addChild(exportSection, button(ui, "lumi-share-export-button", Component.translatable("luma.action.export_package"), () -> {
                ProjectArchiveExportResult result = controller.exportVariantPackage(projectName, selected[0], include[0]);
                CLIENT().setScreen(share(parent, projectName, importValue[0], selected[0], include[0]));
            }));
            ui.addScrollChild(body, exportSection);
            Object imports = section(ui, "lumi-share-imported", Component.translatable("luma.share.packages_title"), Component.translatable("luma.share.packages_help"));
            if (state.importedProjects().isEmpty()) {
                ui.addChild(imports, caption(ui, "lumi-share-imported-empty", Component.translatable("luma.share.imported_empty")));
            }
            state.importedProjects().forEach(imported -> ui.addChild(imports, caption(ui, id("lumi-share-imported", imported.projectName()),
                    Component.literal(imported.projectName() + " / " + imported.variantName()))));
            ui.addScrollChild(body, imports);
        }
        self[0] = ui.screen(root, Component.translatable("luma.screen.import_export.title"));
        return self[0];
    }

    private static Object dashboardWorkspace(LdLib2ReflectiveUi ui, Screen[] self, DashboardProjectItem item, boolean primary) {
        Object card = section(ui, id("lumi-dashboard-project", item.name()),
                Component.literal(item.name()),
                Component.translatable(primary ? "luma.dashboard.workspace_help" : "luma.dashboard.project_entry",
                        item.activeVariantId(), item.versionCount(), item.draftChangeCount()));
        stats(ui, card, id("lumi-dashboard-project-stats", item.name()),
                statLabel("luma.dashboard.metric_versions", item.versionCount()),
                statLabel("luma.dashboard.metric_pending", item.draftChangeCount()));
        ui.addChild(card, row(ui, id("lumi-dashboard-project-actions", item.name()),
                button(ui, id("lumi-dashboard-open", item.name()), Component.translatable("luma.action.open_workspace"), () -> CLIENT().setScreen(project(self[0], item.name()))),
                button(ui, id("lumi-dashboard-settings", item.name()), Component.translatable("luma.action.settings"), () -> CLIENT().setScreen(settings(self[0], item.name())))));
        if (item.hasDraft()) {
            ui.addChild(card, button(ui, id("lumi-dashboard-recovery", item.name()), Component.translatable("luma.action.recovery"), () -> CLIENT().setScreen(recovery(self[0], item.name()))));
        }
        return card;
    }

    private static Object diffSection(LdLib2ReflectiveUi ui, VersionDiff diff, List<MaterialDeltaEntry> materials) {
        Object section = section(ui, "lumi-diff-summary", Component.translatable("luma.compare.summary_title"),
                diff == null ? Component.translatable("luma.changes.empty") : Component.translatable("luma.compare.raw_chunks", diff.changedChunks()));
        if (diff != null) {
            stats(ui, section, "lumi-diff-stats",
                    statLabel("luma.dashboard.pending_added", count(diff, ChangeType.ADDED)),
                    statLabel("luma.dashboard.pending_changed", count(diff, ChangeType.CHANGED)),
                    statLabel("luma.dashboard.pending_removed", count(diff, ChangeType.REMOVED)));
        }
        if (materials != null) {
            materials.stream().limit(8).forEach(entry -> ui.addChild(section, caption(ui, id("lumi-material", entry.blockId()),
                    Component.literal(entry.blockId() + " " + signed(entry.delta())))));
        }
        return section;
    }

    private static Object field(LdLib2ReflectiveUi ui, String id, Component label, Object control) {
        Object field = section(ui, id, label, null);
        ui.addChild(field, control);
        return field;
    }

    private static Object toggleField(LdLib2ReflectiveUi ui, String id, String labelKey, boolean[] values, int index) {
        Object field = section(ui, id, Component.translatable(labelKey), null);
        ui.addChild(field, ui.toggle(id + "-toggle", Component.literal(""), values[index], value -> values[index] = value));
        return field;
    }

    private static Object coordinateRow(LdLib2ReflectiveUi ui, String id, String labelKey, String[] values, int offset) {
        Object row = row(ui, id);
        ui.addChild(row, caption(ui, id + "-label", Component.translatable(labelKey)));
        ui.addChild(row, ui.fixedTextField(id + "-x", values[offset], 52, value -> values[offset] = value));
        ui.addChild(row, ui.fixedTextField(id + "-y", values[offset + 1], 52, value -> values[offset + 1] = value));
        ui.addChild(row, ui.fixedTextField(id + "-z", values[offset + 2], 52, value -> values[offset + 2] = value));
        return row;
    }

    private static void navigationCard(
            LdLib2ReflectiveUi ui,
            Object body,
            String id,
            String titleKey,
            String helpKey,
            String buttonKey,
            Runnable action
    ) {
        Object card = section(ui, "lumi-more-" + id, Component.translatable(titleKey), Component.translatable(helpKey));
        ui.addChild(card, button(ui, "lumi-more-" + id + "-button", Component.translatable(buttonKey), action));
        ui.addScrollChild(body, card);
    }

    private static Object root(LdLib2ReflectiveUi ui, String id) {
        Object root = ui.element(id, "panel_bg");
        ui.layout(root, layout -> layout.widthPercent(100).heightPercent(100).paddingAll(8).gapAll(6));
        return root;
    }

    private static Object frame(LdLib2ReflectiveUi ui, String id) {
        Object frame = ui.panel(id);
        ui.layout(frame, layout -> layout.widthPercent(100).heightPercent(100).paddingAll(6).gapAll(6));
        return frame;
    }

    private static void header(LdLib2ReflectiveUi ui, Object frame, String id, Component backText, Runnable onBack) {
        ui.addChild(frame, row(ui, id, button(ui, id + "-back", backText, onBack)));
    }

    private static Object body(LdLib2ReflectiveUi ui, Object frame, String id) {
        Object body = ui.scroller(id);
        ui.addChild(frame, body);
        return body;
    }

    private static Object section(LdLib2ReflectiveUi ui, String id, Component title, Component subtitle) {
        Object section = ui.panel(id);
        ui.layout(section, layout -> layout.widthPercent(100).paddingAll(5).gapAll(4).flexShrink(0));
        if (title != null) {
            ui.addChild(section, value(ui, id + "-title", title));
        }
        if (subtitle != null) {
            ui.addChild(section, caption(ui, id + "-subtitle", subtitle));
        }
        return section;
    }

    private static Object empty(LdLib2ReflectiveUi ui, String id, String titleKey, String helpKey) {
        return section(ui, id, Component.translatable(titleKey), Component.translatable(helpKey));
    }

    private static Object row(LdLib2ReflectiveUi ui, String id, Object... children) {
        Object row = ui.element(id);
        ui.layout(row, layout -> layout.widthPercent(100).row().gapAll(4).minHeight(18).flexShrink(0));
        for (Object child : children) {
            ui.addChild(row, child);
        }
        return row;
    }

    private static Object button(LdLib2ReflectiveUi ui, String id, Component text, Runnable action) {
        return ui.button(id, text, action);
    }

    private static Object title(LdLib2ReflectiveUi ui, String id, Component text) {
        return ui.label(id, text, TextTone.TITLE);
    }

    private static Object value(LdLib2ReflectiveUi ui, String id, Component text) {
        return ui.label(id, text, TextTone.VALUE);
    }

    private static Object caption(LdLib2ReflectiveUi ui, String id, Component text) {
        return ui.label(id, text, TextTone.MUTED);
    }

    private static Object danger(LdLib2ReflectiveUi ui, String id, Component text) {
        return ui.label(id, text, TextTone.DANGER);
    }

    private static Object status(LdLib2ReflectiveUi ui, String id, Component text) {
        Object section = section(ui, id, null, null);
        ui.addChild(section, ui.label(id + "-text", text, TextTone.ACCENT));
        return section;
    }

    private static void stats(LdLib2ReflectiveUi ui, Object parent, String id, StatLabel... labels) {
        Object row = row(ui, id);
        for (StatLabel label : labels) {
            Object chip = ui.panel(id + "-" + label.key.replace('.', '-'));
            ui.layout(chip, layout -> layout.paddingAll(3).gapAll(2).flexShrink(0));
            ui.addChild(chip, value(ui, id + "-" + label.key + "-value", Component.literal(Integer.toString(label.value))));
            ui.addChild(chip, caption(ui, id + "-" + label.key + "-label", Component.translatable(label.key)));
            ui.addChild(row, chip);
        }
        ui.addChild(parent, row);
    }

    private static StatLabel statLabel(String key, int value) {
        return new StatLabel(key, value);
    }

    private static DashboardProjectItem primaryWorkspace(List<DashboardProjectItem> items) {
        String currentDimensionId = CLIENT().level == null
                ? "minecraft:overworld"
                : CLIENT().level.dimension().identifier().toString();
        return items.stream()
                .filter(item -> !item.archived())
                .filter(DashboardProjectItem::worldWorkspace)
                .filter(item -> item.dimensionId().equals(currentDimensionId))
                .findFirst()
                .orElseGet(() -> items.stream()
                        .filter(item -> !item.archived())
                        .filter(DashboardProjectItem::worldWorkspace)
                        .findFirst()
                        .orElse(null));
    }

    private static int count(VersionDiff diff, ChangeType type) {
        return (int) diff.changedBlocks().stream().filter(entry -> entry.changeType() == type).count();
    }

    private static String graphPrefix(io.github.luma.ui.graph.CommitGraphNode node) {
        StringBuilder builder = new StringBuilder(node.laneCount() * 2);
        for (int lane = 0; lane < node.laneCount(); lane++) {
            builder.append(node.lane() == lane ? (node.activeHead() ? '*' : 'o') : node.activeLanes().contains(lane) ? '|' : ' ');
            if (lane + 1 < node.laneCount()) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int parsePositiveInt(String value, int fallback) {
        int parsed = parseInt(value);
        return parsed <= 0 ? fallback : parsed;
    }

    private static double parsePositiveDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return parsed <= 0 ? fallback : parsed;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return (bytes / (1024L * 1024L)) + " MB";
    }

    private static String id(String prefix, String value) {
        return prefix + "-" + (value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-"));
    }

    private static LdLib2ReflectiveUi runtime() {
        return LdLib2ReflectiveUi.required(LdLib2Screens.class.getClassLoader());
    }

    private static Minecraft CLIENT() {
        return Minecraft.getInstance();
    }

    private record StatLabel(String key, int value) {
    }
}
