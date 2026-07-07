package io.github.luma.gametest;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.screen.CleanupScreen;
import io.github.luma.ui.screen.CreateProjectScreen;
import io.github.luma.ui.screen.section.ProjectScreenSections;
import io.github.luma.ui.screen.section.RestoreConfirmationDialogView;
import io.github.luma.ui.screen.section.SaveDetailsPartialRestoreSection;
import io.github.luma.ui.state.PartialRestoreFormState;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.ParentUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.lwjgl.glfw.GLFW;

@SuppressWarnings("UnstableApiUsage")
public final class LumiScreenClientGameTests implements FabricClientGameTest {

    private static final Instant FIXTURE_NOW = Instant.parse("2026-05-10T12:00:00Z");
    private static final Field UI_ADAPTER_FIELD = uiAdapterField();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientGameTestSingleplayerSupport.prepare(singleplayer);
            this.exerciseSafeScreens(context);
            this.exerciseSectionButtons(context);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi screen client gametest failed", exception);
        }
    }

    private void exerciseSafeScreens(ClientGameTestContext context) throws Exception {
        this.open(context, () -> new CreateProjectScreen(null), CreateProjectScreen.class, "lumi-ui-create-project");
        this.clickAndStay(context, "luma.action.create_project", CreateProjectScreen.class, "lumi-ui-create-project-validation");
        this.closeCurrentScreen(context);

        this.open(context, () -> new CleanupScreen(null, "lumi-ui-smoke"), CleanupScreen.class, "lumi-ui-cleanup");
        this.clickAndClose(context, "luma.action.cancel");
    }

    private void exerciseSectionButtons(ClientGameTestContext context) throws Exception {
        context.computeOnClient(client -> {
            this.verifyProjectSectionActions();
            this.verifyPartialRestoreActions();
            return null;
        });
    }

    private void verifyProjectSectionActions() {
        RecordingProjectActions actions = new RecordingProjectActions();
        ProjectScreenSections sections = new ProjectScreenSections(new ProjectScreenController(), actions);

        FlowLayout section = sections.buildSection(this.projectModel(new PendingChangeSummary(3, 1, 2), null, false));
        this.assertCurrentBuildStatsBeforeActions(section);
        this.assertActive(section, "luma.action.save_build");
        this.assertActive(section, "luma.action.amend_version");
        this.assertActive(section, "luma.action.see_changes");
        this.assertIconButtonHasNoVisibleMessage(section, "luma.action.see_changes");

        this.press(section, "luma.action.save_build");
        this.assertEquals("openSave", actions.lastAction, "save action");
        this.press(section, "luma.action.amend_version");
        this.assertEquals("openAmend", actions.lastAction, "amend action");
        this.press(section, "luma.action.see_changes");
        this.assertEquals("openCompare", actions.lastAction, "compare action");
        this.assertEquals("v0001", actions.leftReference, "compare left reference");

        FlowLayout busy = sections.buildSection(this.projectModel(new PendingChangeSummary(1, 0, 0), this.activeOperation(), true));
        this.assertInactive(busy, "luma.action.save_build");
        this.assertInactive(busy, "luma.action.amend_version");
        this.assertActive(busy, "luma.action.see_changes");

        RecordingRestoreActions restoreActions = new RecordingRestoreActions();
        RestoreConfirmationDialogView restoreDialog = new RestoreConfirmationDialogView(restoreActions);
        FlowLayout restore = restoreDialog.overlay(new RestoreConfirmationDialogView.Model(
                960,
                540,
                Component.translatable("luma.restore.confirm_title"),
                Component.translatable("luma.restore.confirm_help"),
                Component.translatable("luma.restore.confirm_target", "main", "v0001"),
                false,
                false,
                true,
                false
        ));
        this.assertActive(restore, "luma.action.restore_whole_save");
        this.assertActive(restore, "luma.action.restore_only_selected_area");
        this.assertActive(restore, "luma.action.restore_everything_except_selection");
        this.press(restore, "luma.action.restore_only_selected_area");
        this.assertEquals("restoreSelectedArea", restoreActions.lastAction, "selected restore action");
        this.press(restore, "luma.action.restore_everything_except_selection");
        this.assertEquals("restoreOutsideSelection", restoreActions.lastAction, "outside restore action");
        this.press(restore, "luma.action.cancel");
        this.assertEquals("cancel", restoreActions.lastAction, "cancel restore action");
    }

    private void verifyPartialRestoreActions() {
        RecordingPartialActions actions = new RecordingPartialActions();
        SaveDetailsPartialRestoreSection sectionBuilder = new SaveDetailsPartialRestoreSection(actions);
        PartialRestoreFormState form = new PartialRestoreFormState();
        Bounds3i projectBounds = bounds(0, 64, 0, 6, 70, 6);
        Bounds3i selection = bounds(1, 65, 1, 2, 66, 2);
        form.ensureDefaults(projectBounds, null);
        form.setSummary(new PartialRestorePlanSummary(
                RestorePlanMode.PATCH_REPLAY,
                projectBounds,
                PartialRestoreMode.SELECTED_AREA,
                PartialRestoreRegionSource.MANUAL_BOUNDS,
                List.of(new ChunkPoint(0, 0)),
                "main",
                "v0001",
                "v0002",
                4
        ));

        FlowLayout section = sectionBuilder.section(new SaveDetailsPartialRestoreSection.Model(
                "Tower",
                version("v0002", "v0001", VersionKind.MANUAL),
                "Tester",
                false,
                form,
                projectBounds,
                null,
                Optional.of(selection),
                Map.of()
        ));
        this.assertActive(section, "luma.action.use_selected_area");
        this.assertActive(section, "luma.partial_restore.mode_outside_selection");
        this.assertActive(section, "luma.action.apply_partial_restore");

        this.press(section, "luma.action.use_selected_area");
        this.assertEquals("selectionApplied", actions.lastAction, "selection action");
        this.assertEquals(PartialRestoreRegionSource.LUMI_REGION, form.regionSource(), "selection source");
        this.press(section, "luma.partial_restore.mode_outside_selection");
        this.assertEquals("modeChanged", actions.lastAction, "mode action");
        this.assertEquals(PartialRestoreMode.OUTSIDE_SELECTED_AREA, form.restoreMode(), "partial restore mode");
        this.press(section, "luma.action.preview_partial_restore");
        this.assertEquals("preview", actions.lastAction, "preview action");
        if (actions.previewRequest == null || !"Tower".equals(actions.previewRequest.projectName())) {
            throw new AssertionError("Partial restore preview did not receive the expected request");
        }

        form.setSummary(new PartialRestorePlanSummary(
                RestorePlanMode.PATCH_REPLAY,
                selection,
                PartialRestoreMode.OUTSIDE_SELECTED_AREA,
                PartialRestoreRegionSource.LUMI_REGION,
                List.of(new ChunkPoint(0, 0)),
                "main",
                "v0001",
                "v0002",
                4
        ));
        FlowLayout refreshed = sectionBuilder.section(new SaveDetailsPartialRestoreSection.Model(
                "Tower",
                version("v0002", "v0001", VersionKind.MANUAL),
                "Tester",
                false,
                form,
                projectBounds,
                null,
                Optional.of(selection),
                Map.of()
        ));
        this.press(refreshed, "luma.action.apply_partial_restore");
        this.assertEquals("apply", actions.lastAction, "apply action");
        if (actions.applyRequest == null
                || actions.applyRequest.restoreMode() != PartialRestoreMode.OUTSIDE_SELECTED_AREA) {
            throw new AssertionError("Partial restore apply did not receive the expected request");
        }
    }

    private <T extends Screen> void open(
            ClientGameTestContext context,
            Supplier<T> screenSupplier,
            Class<T> screenType,
            String screenshotName
    ) throws Exception {
        context.setScreen(screenSupplier::get);
        this.waitForRenderedScreen(context, screenType, screenshotName);
    }

    private <T extends Screen> void clickAndWait(
            ClientGameTestContext context,
            String buttonLabel,
            Class<T> screenType,
            String screenshotName
    ) throws Exception {
        this.pressCurrentScreenButton(context, buttonLabel);
        this.waitForRenderedScreen(context, screenType, screenshotName);
    }

    private <T extends Screen> void clickAndStay(
            ClientGameTestContext context,
            String buttonLabel,
            Class<T> screenType,
            String screenshotName
    ) throws Exception {
        this.clickAndWait(context, buttonLabel, screenType, screenshotName);
    }

    private void clickAndClose(ClientGameTestContext context, String buttonLabel) throws Exception {
        this.pressCurrentScreenButton(context, buttonLabel);
        context.waitFor(client -> client.screen == null);
    }

    private void closeCurrentScreen(ClientGameTestContext context) throws Exception {
        context.runOnClient(client -> {
            if (client.screen == null) {
                throw new AssertionError("Cannot close without an open screen");
            }
            client.screen.onClose();
        });
        context.waitFor(client -> client.screen == null);
    }

    private void pressCurrentScreenButton(ClientGameTestContext context, String buttonKey) {
        context.runOnClient(client -> {
            if (client.screen == null) {
                throw new AssertionError("Cannot press " + buttonKey + " without an open screen");
            }
            this.press(this.screenRoot(client.screen), buttonKey);
        });
    }

    private <T extends Screen> void waitForRenderedScreen(
            ClientGameTestContext context,
            Class<T> screenType,
            String screenshotName
    ) throws Exception {
        context.waitForScreen(screenType);
        context.waitTicks(2);
        this.assertCurrentScreen(context, screenType);
        this.assertScreenshotWritten(context.takeScreenshot(screenshotName));
    }

    private <T extends Screen> void assertCurrentScreen(ClientGameTestContext context, Class<T> screenType) throws Exception {
        context.computeOnClient(client -> {
            if (!screenType.isInstance(client.screen)) {
                throw new AssertionError("Expected " + screenType.getSimpleName() + " but got " + client.screen);
            }
            if (client.screen.width <= 0 || client.screen.height <= 0) {
                throw new AssertionError(screenType.getSimpleName() + " mounted with invalid size");
            }
            return null;
        });
    }

    private void assertScreenshotWritten(Path screenshot) throws Exception {
        if (!Files.isRegularFile(screenshot) || Files.size(screenshot) <= 0L) {
            throw new AssertionError("Screen screenshot was not written: " + screenshot);
        }
    }

    private FlowLayout screenRoot(Screen screen) {
        if (!(screen instanceof BaseOwoScreen<?>)) {
            throw new AssertionError("Expected an owo screen but got " + screen.getClass().getName());
        }
        try {
            OwoUIAdapter<?> adapter = (OwoUIAdapter<?>) UI_ADAPTER_FIELD.get(screen);
            if (adapter == null || !(adapter.rootComponent instanceof FlowLayout root)) {
                throw new AssertionError("Screen has no mounted FlowLayout root: " + screen.getClass().getName());
            }
            return root;
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Could not inspect owo screen root", exception);
        }
    }

    private ProjectScreenSections.Model projectModel(
            PendingChangeSummary pending,
            OperationSnapshot operation,
            boolean restoreReturnPoint
    ) {
        return this.projectModel(pending, operation, restoreReturnPoint, "", "", Optional.empty());
    }

    private ProjectScreenSections.Model projectModel(
            PendingChangeSummary pending,
            OperationSnapshot operation,
            boolean restoreReturnPoint,
            String pendingRestoreVariantId,
            String pendingRestoreVersionId,
            Optional<Bounds3i> selection
    ) {
        BuildProject project = BuildProject.create(
                "Tower",
                "minecraft:overworld",
                bounds(0, 64, 0, 8, 72, 8),
                new BlockPoint(0, 64, 0),
                FIXTURE_NOW
        );
        ProjectVersion root = version("v0001", "", VersionKind.INITIAL);
        ProjectHomeViewState state = new ProjectHomeViewState(
                project,
                List.of(root),
                List.of(ProjectVariant.main(root.id(), FIXTURE_NOW)),
                pending,
                false,
                operation,
                null,
                "luma.status.project_ready",
                restoreReturnPoint
        );
        return new ProjectScreenSections.Model(
                project.name(),
                state,
                960,
                "main",
                false,
                "",
                "",
                "",
                pendingRestoreVariantId,
                pendingRestoreVersionId,
                selection
        );
    }

    private static ProjectVersion version(String id, String parentId, VersionKind kind) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentId,
                "",
                List.of(),
                kind,
                "Tester",
                id,
                ChangeStats.empty(),
                null,
                null,
                FIXTURE_NOW
        );
    }

    private OperationSnapshot activeOperation() {
        return new OperationSnapshot(
                new OperationHandle("op", "project", "restore-version", FIXTURE_NOW, false),
                OperationStage.APPLYING,
                new OperationProgress(1, 2, "blocks"),
                "",
                FIXTURE_NOW
        );
    }

    private void assertActive(FlowLayout root, String key) {
        if (!this.button(root, key).active()) {
            throw new AssertionError("Expected active button " + key);
        }
    }

    private void assertInactive(FlowLayout root, String key) {
        if (this.button(root, key).active()) {
            throw new AssertionError("Expected inactive button " + key);
        }
    }

    private void assertIconButtonHasNoVisibleMessage(FlowLayout root, String key) {
        if (!this.button(root, key).getMessage().getString().isBlank()) {
            throw new AssertionError("Expected icon-only button " + key);
        }
    }

    private ButtonComponent button(FlowLayout root, String key) {
        return this.buttons(root).stream()
                .filter(button -> this.matches(button, key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + key));
    }

    private void press(FlowLayout root, String key) {
        this.button(root, key).onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
    }

    private void assertCurrentBuildStatsBeforeActions(FlowLayout section) {
        if (section.children().size() < 3) {
            throw new AssertionError("Current build section should render stats and actions on separate rows");
        }
        if (!this.buttons(section.children().get(1)).isEmpty()) {
            throw new AssertionError("Current build stats row should not contain action buttons");
        }
        if (this.buttons(section.children().get(2)).isEmpty()) {
            throw new AssertionError("Current build actions row should follow stats row");
        }
    }

    private List<ButtonComponent> buttons(UIComponent component) {
        List<ButtonComponent> buttons = new ArrayList<>();
        this.collectButtons(component, buttons);
        return buttons;
    }

    private void collectButtons(UIComponent component, List<ButtonComponent> buttons) {
        if (component instanceof ButtonComponent button) {
            buttons.add(button);
        }
        if (component instanceof ParentUIComponent parent) {
            for (UIComponent child : parent.children()) {
                this.collectButtons(child, buttons);
            }
        }
    }

    private boolean matches(ButtonComponent button, String key) {
        if (key.equals(button.id())) {
            return true;
        }
        return button.getMessage().getContents() instanceof TranslatableContents translatable
                ? key.equals(translatable.getKey())
                : key.equals(button.getMessage().getString());
    }

    private void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static Bounds3i bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new Bounds3i(new BlockPoint(minX, minY, minZ), new BlockPoint(maxX, maxY, maxZ));
    }

    private static Field uiAdapterField() {
        try {
            Field field = BaseOwoScreen.class.getDeclaredField("uiAdapter");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class RecordingProjectActions implements ProjectScreenSections.Actions {
        private String lastAction = "";
        private String leftReference = "";

        @Override
        public void openSave() {
            this.lastAction = "openSave";
        }

        @Override
        public void openAmend(ProjectVersion activeHead) {
            this.lastAction = "openAmend";
        }

        @Override
        public void openCompare(String leftReference, String rightReference, String contextVersionId) {
            this.lastAction = "openCompare";
            this.leftReference = leftReference;
        }

        @Override
        public void openRecovery() {
            this.lastAction = "openRecovery";
        }

        @Override
        public void openSaveDetails(String versionId) {
            this.lastAction = "openSaveDetails";
        }

        @Override
        public void openBranchDialog(ProjectVersion version) {
            this.lastAction = "openBranchDialog";
        }

        @Override
        public void setHistoryGraphVisible(boolean visible) {
            this.lastAction = visible ? "showHistoryGraph" : "showHistoryCards";
        }

        @Override
        public void selectHistoryVariant(String variantId) {
            this.lastAction = "selectHistoryVariant";
        }

        @Override
        public void setHistoryTagFilter(String filter) {
            this.lastAction = "setHistoryTagFilter";
        }

        @Override
        public void toggleTagEditor(ProjectVersion version) {
            this.lastAction = "toggleTagEditor";
        }

        @Override
        public void updateTagEditor(String value) {
            this.lastAction = "updateTagEditor";
        }

        @Override
        public void saveTags(ProjectVersion version) {
            this.lastAction = "saveTags";
        }

        @Override
        public void requestRestore(ProjectVariant variant, ProjectVersion version) {
            this.lastAction = "requestRestore";
        }
    }

    private static final class RecordingRestoreActions implements RestoreConfirmationDialogView.Actions {
        private String lastAction = "";

        @Override
        public void cancel() {
            this.lastAction = "cancel";
        }

        @Override
        public void restoreWhole() {
            this.lastAction = "restoreWhole";
        }

        @Override
        public void restoreSelectedArea() {
            this.lastAction = "restoreSelectedArea";
        }

        @Override
        public void restoreOutsideSelection() {
            this.lastAction = "restoreOutsideSelection";
        }
    }

    private static final class RecordingPartialActions implements SaveDetailsPartialRestoreSection.Actions {
        private String lastAction = "";
        private PartialRestoreRequest previewRequest;
        private PartialRestoreRequest applyRequest;

        @Override
        public void preview(PartialRestoreRequest request) {
            this.lastAction = "preview";
            this.previewRequest = request;
        }

        @Override
        public void apply(PartialRestoreRequest request) {
            this.lastAction = "apply";
            this.applyRequest = request;
        }

        @Override
        public void selectionApplied() {
            this.lastAction = "selectionApplied";
        }

        @Override
        public void modeChanged() {
            this.lastAction = "modeChanged";
        }

        @Override
        public void invalidBounds() {
            this.lastAction = "invalidBounds";
        }
    }
}
