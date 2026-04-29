package io.github.luma.ui.screen;

import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.screen.section.CompareScreenSections;
import io.github.luma.ui.state.CompareViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CompareScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final String contextVersionId;
    private final Minecraft client = Minecraft.getInstance();
    private final CompareScreenController controller = new CompareScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final CompareScreenSections sections = new CompareScreenSections(new SectionActions());
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private CompareViewState state = new CompareViewState(
            List.of(),
            List.of(),
            "",
            "",
            "",
            "",
            "",
            null,
            List.of(),
            "luma.status.compare_ready",
            false
    );
    private String leftReference;
    private String rightReference;
    private String status = "luma.status.compare_ready";
    private boolean showMoreDetails = false;
    private boolean showManualCompare = false;
    private boolean initialOverlayAttempted = false;

    public CompareScreen(Screen parent, String projectName, String leftReference, String rightReference) {
        this(parent, projectName, leftReference, rightReference, "");
    }

    public CompareScreen(Screen parent, String projectName, String leftReference, String rightReference, String contextVersionId) {
        super(Component.translatable("luma.screen.compare.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.leftReference = leftReference == null ? "" : leftReference;
        this.rightReference = rightReference == null ? "" : rightReference;
        this.contextVersionId = contextVersionId == null ? "" : contextVersionId;
        this.showManualCompare = this.leftReference.isBlank() && this.rightReference.isBlank();
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, this.leftReference, this.rightReference, this.status);
        this.autoShowInitialOverlay();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(LumaUi.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName
        )));
        frame.child(header);

        frame.child(LumaUi.value(this.sections.compareTitle(this.state)));
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        CompareScreenSections.Model model = this.sectionModel();
        if (this.state.diff() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.compare.empty_title"),
                    Component.translatable("luma.compare.empty")
            ));
            body.child(this.sections.referenceSection(model));
            body.child(LumaUi.bottomSpacer());
            return;
        }

        body.child(this.sections.summarySection(model));
        body.child(this.sections.moreDetailsSection(model));
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private CompareScreenSections.Model sectionModel() {
        return new CompareScreenSections.Model(
                this.state,
                this.projectName,
                this.width,
                this.leftReference,
                this.rightReference,
                this.contextVersionId,
                this.showMoreDetails,
                this.showManualCompare
        );
    }

    private void rebuild() {
        double scrollProgress = this.currentScrollProgress();
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(scrollProgress);
    }

    private void runCompare() {
        CompareViewState comparedState = this.controller.loadState(
                this.projectName,
                this.leftReference,
                this.rightReference,
                "luma.status.compare_ready"
        );
        if (comparedState.diff() == null) {
            CompareOverlayRenderer.clear();
            this.status = comparedState.status();
            this.rebuild();
            return;
        }

        this.status = this.controller.showOverlay(this.projectName, comparedState);
        this.rebuild();
    }

    private double currentScrollProgress() {
        return this.bodyScroll == null ? 0.0D : this.bodyScroll.progress();
    }

    private void restoreScroll(double scrollProgress) {
        if (this.bodyScroll != null) {
            this.bodyScroll.restoreProgress(scrollProgress);
        }
    }

    private void autoShowInitialOverlay() {
        if (this.initialOverlayAttempted || this.leftReference.isBlank() || this.rightReference.isBlank()) {
            return;
        }
        this.initialOverlayAttempted = true;
        if (this.state.diff() == null || this.state.diff().changedBlocks().isEmpty()) {
            return;
        }

        if (!CompareOverlayRenderer.visibleFor(
                this.projectName,
                this.state.leftResolvedVersionId(),
                this.state.rightResolvedVersionId()
        )) {
            this.status = this.controller.showOverlay(this.projectName, this.state);
            this.state = this.withStatus(this.state, this.status);
        }
    }

    private CompareViewState withStatus(CompareViewState source, String nextStatus) {
        return new CompareViewState(
                source.versions(),
                source.variants(),
                source.activeVariantId(),
                source.leftReference(),
                source.rightReference(),
                source.leftResolvedVersionId(),
                source.rightResolvedVersionId(),
                source.diff(),
                source.materialDelta(),
                nextStatus,
                source.debugEnabled()
        );
    }

    private final class SectionActions implements CompareScreenSections.Actions {

        @Override
        public void updateLeftReference(String value) {
            leftReference = value;
        }

        @Override
        public void updateRightReference(String value) {
            rightReference = value;
        }

        @Override
        public void runCompare() {
            CompareScreen.this.runCompare();
        }

        @Override
        public void toggleOverlay() {
            if (state.diff() == null) {
                status = "luma.status.compare_failed";
            } else if (CompareOverlayRenderer.hasDataFor(
                    projectName,
                    state.leftResolvedVersionId(),
                    state.rightResolvedVersionId()
            )) {
                status = controller.toggleOverlayVisibility();
            } else {
                status = controller.showOverlay(projectName, state);
            }
            rebuild();
        }

        @Override
        public void toggleMoreDetails() {
            showMoreDetails = !showMoreDetails;
            rebuild();
        }

        @Override
        public void toggleManualCompare() {
            showManualCompare = !showManualCompare;
            rebuild();
        }

        @Override
        public void applyPreset(String leftReference, String rightReference) {
            CompareScreen.this.leftReference = leftReference;
            CompareScreen.this.rightReference = rightReference;
            initialOverlayAttempted = false;
            status = "luma.status.compare_ready";
            rebuild();
        }
    }
}
