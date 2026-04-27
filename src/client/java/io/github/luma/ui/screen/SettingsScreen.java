package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.SettingsScreenController;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final SettingsScreenController controller = new SettingsScreenController();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private String status = "luma.status.settings_ready";
    private boolean loaded = false;
    private boolean autoVersionsEnabled;
    private boolean safetySnapshotBeforeRestore;
    private boolean previewGenerationEnabled;
    private boolean debugLoggingEnabled;
    private boolean workspaceHudEnabled = true;
    private boolean archived;
    private String autoVersionMinutes = "10";
    private String sessionIdleSeconds = "5";
    private String snapshotEveryVersions = "10";
    private String snapshotVolumeThreshold = "0.20";
    private String sessionIdleSecondsError = "";
    private String snapshotEveryVersionsError = "";
    private String snapshotVolumeThresholdError = "";

    public SettingsScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.settings.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        var project = this.controller.loadProject(this.projectName);
        if (project != null && !this.loaded) {
            this.autoVersionsEnabled = project.settings().autoVersionsEnabled();
            this.safetySnapshotBeforeRestore = project.settings().safetySnapshotBeforeRestore();
            this.previewGenerationEnabled = project.settings().previewGenerationEnabled();
            this.debugLoggingEnabled = project.settings().debugLoggingEnabled();
            this.workspaceHudEnabled = project.settings().workspaceHudVisible();
            this.archived = project.archived();
            this.autoVersionMinutes = Integer.toString(project.settings().autoVersionMinutes());
            this.sessionIdleSeconds = Integer.toString(project.settings().sessionIdleSeconds());
            this.snapshotEveryVersions = Integer.toString(project.settings().snapshotEveryVersions());
            this.snapshotVolumeThreshold = Double.toString(project.settings().snapshotVolumeThreshold());
            this.loaded = true;
        }
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.settings.title", this.projectName)));
        frame.child(LumaUi.statusBanner(Component.translatable(this.status)));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        if (project == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        body.child(this.safetySection());
        body.child(this.previewSection());
        body.child(this.hudSection());
        body.child(this.storageSection());
        body.child(this.performanceSection());
        body.child(this.debugSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout performanceSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.settings.performance_title"),
                Component.translatable("luma.settings.performance_help")
        );
        section.child(this.fieldWithError(
                Component.translatable("luma.settings.idle_seconds"),
                Component.translatable("luma.settings.idle_seconds_help"),
                this.numberInput(this.sessionIdleSeconds, value -> this.sessionIdleSeconds = value),
                this.sessionIdleSecondsError
        ));
        return section;
    }

    private FlowLayout safetySection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.settings.safety_title"),
                Component.translatable("luma.settings.safety_help")
        );

        section.child(this.fieldWithError(
                Component.translatable("luma.settings.safety_snapshot"),
                Component.translatable("luma.settings.safety_snapshot_help"),
                this.toggleControl(this.safetySnapshotBeforeRestore, value -> this.safetySnapshotBeforeRestore = value),
                ""
        ));
        return section;
    }

    private FlowLayout storageSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.settings.storage_title"),
                Component.translatable("luma.settings.storage_help")
        );
        section.child(this.fieldWithError(
                Component.translatable("luma.settings.snapshot_every"),
                Component.translatable("luma.settings.snapshot_every_help"),
                this.numberInput(this.snapshotEveryVersions, value -> this.snapshotEveryVersions = value),
                this.snapshotEveryVersionsError
        ));
        section.child(this.fieldWithError(
                Component.translatable("luma.settings.snapshot_volume"),
                Component.translatable("luma.settings.snapshot_volume_help"),
                this.numberInput(this.snapshotVolumeThreshold, value -> this.snapshotVolumeThreshold = value),
                this.snapshotVolumeThresholdError
        ));
        return section;
    }

    private FlowLayout previewSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.settings.preview_title"),
                Component.translatable("luma.settings.preview_help")
        );
        section.child(this.fieldWithError(
                Component.translatable("luma.settings.preview_generation"),
                Component.translatable("luma.settings.preview_generation_help"),
                this.toggleControl(this.previewGenerationEnabled, value -> this.previewGenerationEnabled = value),
                ""
        ));
        return section;
    }

    private FlowLayout hudSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.settings.hud_title"),
                Component.translatable("luma.settings.hud_help")
        );
        section.child(this.fieldWithError(
                Component.translatable("luma.settings.workspace_hud"),
                Component.translatable("luma.settings.workspace_hud_help"),
                this.toggleControl(this.workspaceHudEnabled, value -> this.workspaceHudEnabled = value),
                ""
        ));
        return section;
    }

    private FlowLayout debugSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.settings.debug_title"),
                Component.translatable("luma.settings.debug_help")
        );
        section.child(this.fieldWithError(
                Component.translatable("luma.settings.debug_logging"),
                Component.translatable("luma.settings.debug_logging_help"),
                this.toggleControl(this.debugLoggingEnabled, value -> this.debugLoggingEnabled = value),
                ""
        ));
        return section;
    }

    private FlowLayout fieldWithError(
            Component label,
            Component help,
            io.wispforest.owo.ui.core.UIComponent control,
            String errorKey
    ) {
        FlowLayout field = LumaUi.formField(label, help, control);
        if (errorKey != null && !errorKey.isBlank()) {
            field.child(LumaUi.danger(Component.translatable(errorKey)));
        }
        return field;
    }

    private io.wispforest.owo.ui.core.UIComponent toggleControl(
            boolean value,
            java.util.function.Consumer<Boolean> onToggle
    ) {
        var checkbox = UIComponents.checkbox(Component.literal(""));
        checkbox.checked(value);
        checkbox.onChanged(checked -> {
            onToggle.accept(checked);
            this.autoSave();
        });
        return checkbox;
    }

    private io.wispforest.owo.ui.component.TextBoxComponent numberInput(
            String value,
            java.util.function.Consumer<String> onChanged
    ) {
        var box = UIComponents.textBox(Sizing.fill(100), value);
        box.onChanged().subscribe(changedValue -> {
            onChanged.accept(changedValue);
            this.autoSave();
        });
        return box;
    }

    private void autoSave() {
        this.clearValidationErrors();

        Integer parsedSessionIdleSeconds = this.parsePositiveInt(this.sessionIdleSeconds, error -> this.sessionIdleSecondsError = error);
        Integer parsedSnapshotEveryVersions = this.parsePositiveInt(this.snapshotEveryVersions, error -> this.snapshotEveryVersionsError = error);
        Double parsedSnapshotVolumeThreshold = this.parsePositiveDouble(this.snapshotVolumeThreshold, error -> this.snapshotVolumeThresholdError = error);

        if (parsedSessionIdleSeconds == null
                || parsedSnapshotEveryVersions == null
                || parsedSnapshotVolumeThreshold == null) {
            this.status = "luma.status.settings_invalid";
            return;
        }

        this.status = this.controller.saveAll(
                this.projectName,
                new ProjectSettings(
                        this.autoVersionsEnabled,
                        this.parseLegacyAutoVersionMinutes(),
                        parsedSessionIdleSeconds,
                        parsedSnapshotEveryVersions,
                        parsedSnapshotVolumeThreshold,
                        this.safetySnapshotBeforeRestore,
                        this.previewGenerationEnabled,
                        this.debugLoggingEnabled,
                        this.workspaceHudEnabled
                ),
                this.archived
        );
    }

    private Integer parsePositiveInt(String value, java.util.function.Consumer<String> onError) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                onError.accept("luma.settings.error_positive_int");
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            onError.accept("luma.settings.error_positive_int");
            return null;
        }
    }

    private Double parsePositiveDouble(String value, java.util.function.Consumer<String> onError) {
        try {
            double parsed = Double.parseDouble(value);
            if (parsed <= 0.0D) {
                onError.accept("luma.settings.error_positive_decimal");
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            onError.accept("luma.settings.error_positive_decimal");
            return null;
        }
    }

    private void clearValidationErrors() {
        this.sessionIdleSecondsError = "";
        this.snapshotEveryVersionsError = "";
        this.snapshotVolumeThresholdError = "";
    }

    private int parseLegacyAutoVersionMinutes() {
        try {
            int parsed = Integer.parseInt(this.autoVersionMinutes);
            return parsed <= 0 ? ProjectSettings.defaults().autoVersionMinutes() : parsed;
        } catch (NumberFormatException exception) {
            return ProjectSettings.defaults().autoVersionMinutes();
        }
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
