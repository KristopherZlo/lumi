package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.screen.section.BranchHistoryVersions;
import io.github.luma.ui.screen.section.ProjectCompareScreenSections;
import io.github.luma.ui.screen.section.ProjectCompareScreenSections.Side;
import io.github.luma.ui.state.CompareLoadState;
import io.github.luma.ui.state.CompareViewState;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProjectCompareScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private final CompareScreenController compareController = new CompareScreenController();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final BranchHistoryVersions branchHistoryVersions = new BranchHistoryVersions();
    private final ProjectCompareScreenSections sections = new ProjectCompareScreenSections(new SectionActions());
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private ProjectHomeViewState state;
    private String status = "luma.status.compare_ready";
    private String selectedLeftVariantId = "";
    private String selectedRightVariantId = "";
    private String selectedLeftVersionId = "";
    private String selectedRightVersionId = "";
    private String pendingCompareLeftReference = "";
    private String pendingCompareRightReference = "";
    private int refreshCooldown = 0;

    public ProjectCompareScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.compare.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.stateController.loadState(this.projectName, this.status, false);
        this.ensureSelections();

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

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.compare.title"),
                this.state.project(),
                this.state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.COMPARE);
        if (!"luma.status.compare_ready".equals(this.status)) {
            window.content().child(LumaUi.statusBanner(Component.translatable(this.status)));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        body.child(this.sections.pickerSection(this.sectionModel()));
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        if (!this.pendingCompareRightReference.isBlank()) {
            this.continuePendingCompareOverlay();
            return;
        }
        ProjectHomeViewState refreshed = this.stateController.loadState(this.projectName, this.status, false);
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.ensureSelections();
            this.rebuild();
        }
    }

    private ProjectCompareScreenSections.Model sectionModel() {
        return new ProjectCompareScreenSections.Model(
                this.state,
                this.height,
                this.selectedLeftVariantId,
                this.selectedRightVariantId,
                this.selectedLeftVersionId,
                this.selectedRightVersionId
        );
    }

    private void requestCompareOverlay() {
        if (!this.canCompare()) {
            this.refresh("luma.status.compare_select_two");
            return;
        }
        this.pendingCompareLeftReference = this.selectedLeftVersionId;
        this.pendingCompareRightReference = this.selectedRightVersionId;
        this.continuePendingCompareOverlay();
    }

    private void continuePendingCompareOverlay() {
        CompareViewState compare = this.compareController.loadState(
                this.projectName,
                this.pendingCompareLeftReference,
                this.pendingCompareRightReference,
                "luma.status.compare_loading"
        );
        if (compare.loadState() == CompareLoadState.LOADING) {
            this.refresh(compare.status());
            return;
        }
        if (compare.loadState() == CompareLoadState.READY) {
            String result = this.compareController.showOverlay(this.projectName, compare);
            this.pendingCompareLeftReference = "";
            this.pendingCompareRightReference = "";
            if ("luma.status.compare_no_changes".equals(result) || "luma.status.compare_failed".equals(result)) {
                this.refresh(result);
                return;
            }
            this.status = result;
            this.client.setScreen(null);
            return;
        }
        this.pendingCompareLeftReference = "";
        this.pendingCompareRightReference = "";
        this.refresh(compare.status());
    }

    private boolean canCompare() {
        return !this.selectedLeftVersionId.isBlank()
                && !this.selectedRightVersionId.isBlank()
                && !this.selectedLeftVersionId.equals(this.selectedRightVersionId);
    }

    private void ensureSelections() {
        if (this.state.project() == null) {
            return;
        }
        this.selectedLeftVariantId = this.ensureVariantId(this.selectedLeftVariantId);
        this.selectedRightVariantId = this.ensureVariantId(this.selectedRightVariantId);
        this.selectedLeftVersionId = this.ensureVersionId(this.selectedLeftVariantId, this.selectedLeftVersionId);
        this.selectedRightVersionId = this.ensureVersionId(this.selectedRightVariantId, this.selectedRightVersionId);
    }

    private String ensureVariantId(String variantId) {
        if (ProjectUiSupport.variantFor(this.state.variants(), variantId) != null) {
            return variantId;
        }
        String activeVariantId = this.state.project().activeVariantId();
        if (ProjectUiSupport.variantFor(this.state.variants(), activeVariantId) != null) {
            return activeVariantId;
        }
        return this.state.variants().isEmpty() ? "" : this.state.variants().getFirst().id();
    }

    private String ensureVersionId(String variantId, String versionId) {
        List<BranchHistoryVersions.Entry> entries = this.entriesFor(variantId);
        for (BranchHistoryVersions.Entry entry : entries) {
            if (entry.version().id().equals(versionId)) {
                return versionId;
            }
        }
        return entries.isEmpty() ? "" : entries.getFirst().version().id();
    }

    private List<BranchHistoryVersions.Entry> entriesFor(String variantId) {
        ProjectVariant variant = ProjectUiSupport.variantFor(this.state.variants(), variantId);
        return variant == null
                ? List.of()
                : this.branchHistoryVersions.forVariant(this.state.versions(), this.state.variants(), variant);
    }

    private String selectedVariantId(Side side) {
        return side == Side.LEFT ? this.selectedLeftVariantId : this.selectedRightVariantId;
    }

    private String selectedVersionId(Side side) {
        return side == Side.LEFT ? this.selectedLeftVersionId : this.selectedRightVersionId;
    }

    private void selectVariant(Side side, String variantId) {
        if (side == Side.LEFT) {
            this.selectedLeftVariantId = variantId == null ? "" : variantId;
            this.selectedLeftVersionId = this.ensureVersionId(this.selectedLeftVariantId, "");
        } else {
            this.selectedRightVariantId = variantId == null ? "" : variantId;
            this.selectedRightVersionId = this.ensureVersionId(this.selectedRightVariantId, "");
        }
        this.refresh("luma.status.compare_ready");
    }

    private void selectVersion(Side side, String versionId) {
        if (side == Side.LEFT) {
            this.selectedLeftVersionId = versionId == null ? "" : versionId;
        } else {
            this.selectedRightVersionId = versionId == null ? "" : versionId;
        }
        this.refresh("luma.status.compare_ready");
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.compare_ready" : statusKey;
        this.rebuild();
    }

    private void rebuild() {
        this.rebuildPreservingScroll(() -> this.bodyScroll);
    }

    private final class SectionActions implements ProjectCompareScreenSections.Actions {

        @Override
        public void selectVariant(Side side, String variantId) {
            ProjectCompareScreen.this.selectVariant(side, variantId);
        }

        @Override
        public void selectVersion(Side side, String versionId) {
            ProjectCompareScreen.this.selectVersion(side, versionId);
        }

        @Override
        public void runCompare() {
            ProjectCompareScreen.this.requestCompareOverlay();
        }
    }
}
