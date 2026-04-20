package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.ui.controller.SettingsScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final SettingsScreenController controller = new SettingsScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private String status = "luma.status.settings_ready";
    private boolean loaded = false;
    private boolean autoVersionsEnabled;
    private boolean safetySnapshotBeforeRestore;
    private boolean previewGenerationEnabled;
    private String autoVersionMinutes = "10";
    private String sessionIdleSeconds = "5";
    private String snapshotEveryVersions = "10";
    private String snapshotVolumeThreshold = "0.20";

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
            this.autoVersionMinutes = Integer.toString(project.settings().autoVersionMinutes());
            this.sessionIdleSeconds = Integer.toString(project.settings().sessionIdleSeconds());
            this.snapshotEveryVersions = Integer.toString(project.settings().snapshotEveryVersions());
            this.snapshotVolumeThreshold = Double.toString(project.settings().snapshotVolumeThreshold());
            this.loaded = true;
        }
        ProjectIntegrityReport integrity = this.controller.loadIntegrity(this.projectName);

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        root.child(header);

        root.child(UIComponents.label(Component.translatable("luma.screen.settings.title", this.projectName)).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.status)));

        if (project == null) {
            root.child(UIComponents.label(Component.translatable("luma.project.unavailable")));
            return;
        }

        root.child(toggleButton("luma.settings.autoversions", this.autoVersionsEnabled, value -> this.autoVersionsEnabled = value));
        root.child(toggleButton("luma.settings.safety_snapshot", this.safetySnapshotBeforeRestore, value -> this.safetySnapshotBeforeRestore = value));
        root.child(toggleButton("luma.settings.preview_generation", this.previewGenerationEnabled, value -> this.previewGenerationEnabled = value));

        root.child(numberRow("luma.settings.auto_minutes", this.autoVersionMinutes, value -> this.autoVersionMinutes = value));
        root.child(numberRow("luma.settings.idle_seconds", this.sessionIdleSeconds, value -> this.sessionIdleSeconds = value));
        root.child(UIComponents.label(Component.translatable("luma.settings.snapshot_mode_hint")));

        FlowLayout projectActions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        projectActions.gap(6);
        projectActions.child(UIComponents.button(
                Component.translatable(project.favorite() ? "luma.action.unfavorite" : "luma.action.favorite"),
                button -> {
                    this.status = this.controller.setFavorite(this.projectName, !project.favorite());
                    this.loaded = false;
                    this.rebuild();
                }
        ));
        projectActions.child(UIComponents.button(
                Component.translatable(project.archived() ? "luma.action.unarchive" : "luma.action.archive"),
                button -> {
                    this.status = this.controller.setArchived(this.projectName, !project.archived());
                    this.loaded = false;
                    this.rebuild();
                }
        ));
        root.child(projectActions);

        root.child(UIComponents.button(Component.translatable("luma.action.save_settings"), button -> {
            this.status = this.controller.saveSettings(this.projectName, new ProjectSettings(
                    this.autoVersionsEnabled,
                    parseInt(this.autoVersionMinutes, 10),
                    parseInt(this.sessionIdleSeconds, 5),
                    parseInt(this.snapshotEveryVersions, 10),
                    parseDouble(this.snapshotVolumeThreshold, 0.20D),
                    this.safetySnapshotBeforeRestore,
                    this.previewGenerationEnabled
            ));
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));

        root.child(UIComponents.label(Component.translatable(
                integrity.valid() ? "luma.integrity.valid" : "luma.integrity.invalid"
        )));
        for (var error : integrity.errors()) {
            root.child(UIComponents.label(Component.translatable("luma.integrity.error", error)));
        }
        for (var warning : integrity.warnings()) {
            root.child(UIComponents.label(Component.translatable("luma.integrity.warning", warning)));
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout toggleButton(String labelKey, boolean value, java.util.function.Consumer<Boolean> onToggle) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.child(UIComponents.label(Component.translatable(labelKey)));
        row.child(UIComponents.button(
                Component.translatable(value ? "luma.common.enabled" : "luma.common.disabled"),
                button -> {
                    onToggle.accept(!value);
                    this.rebuild();
                }
        ));
        return row;
    }

    private FlowLayout numberRow(String labelKey, String value, java.util.function.Consumer<String> onChanged) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.child(UIComponents.label(Component.translatable(labelKey)));
        var box = UIComponents.textBox(Sizing.fixed(80), value);
        box.onChanged().subscribe(onChanged::accept);
        row.child(box);
        return row;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }
}
