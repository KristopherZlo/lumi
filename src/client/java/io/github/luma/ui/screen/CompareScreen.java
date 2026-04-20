package io.github.luma.ui.screen;

import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareViewState;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CompareScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final CompareScreenController controller = new CompareScreenController();
    private CompareViewState state = new CompareViewState(List.of(), List.of(), "", "", "", "", null, List.of(), "luma.status.compare_ready");
    private String leftReference;
    private String rightReference;
    private String status = "luma.status.compare_ready";

    public CompareScreen(Screen parent, String projectName, String leftReference, String rightReference) {
        super(Component.translatable("luma.screen.compare.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.leftReference = leftReference == null ? "" : leftReference;
        this.rightReference = rightReference == null ? "" : rightReference;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, this.leftReference, this.rightReference, this.status);

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.refresh"), button -> this.rebuild()));
        header.child(UIComponents.button(Component.translatable("luma.action.compare_overlay"), button -> {
            this.status = this.controller.showOverlay(this.state);
            this.rebuild();
        }));
        header.child(UIComponents.button(Component.translatable("luma.action.clear_overlay"), button -> {
            this.status = this.controller.clearOverlay();
            this.rebuild();
        }));
        root.child(header);

        root.child(UIComponents.label(Component.translatable("luma.screen.compare.title", this.projectName)).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.state.status())));
        root.child(UIComponents.label(Component.translatable(
                CompareOverlayRenderer.active() ? "luma.compare.overlay_on" : "luma.compare.overlay_off"
        )));

        root.child(referenceRow("luma.compare.left", this.leftReference, value -> this.leftReference = value));
        root.child(referenceRow("luma.compare.right", this.rightReference, value -> this.rightReference = value));
        root.child(UIComponents.button(Component.translatable("luma.action.compare"), button -> {
            this.status = "luma.status.compare_ready";
            this.rebuild();
        }));

        if (!this.state.variants().isEmpty()) {
            root.child(UIComponents.label(Component.translatable(
                    "luma.compare.variants_hint",
                    String.join(", ", this.state.variants().stream().map(variant -> variant.id()).toList())
            )));
        }

        if (this.state.diff() == null) {
            root.child(UIComponents.label(Component.translatable("luma.compare.empty")));
            return;
        }

        root.child(UIComponents.label(Component.translatable(
                "luma.compare.summary",
                this.state.leftResolvedVersionId(),
                this.state.rightResolvedVersionId(),
                this.state.diff().changedBlockCount(),
                this.state.diff().changedChunks()
        )));

        int blockLimit = Math.min(24, this.state.diff().changedBlocks().size());
        for (int index = 0; index < blockLimit; index++) {
            var entry = this.state.diff().changedBlocks().get(index);
            root.child(UIComponents.label(Component.translatable(
                    "luma.compare.block_entry",
                    entry.pos().x(),
                    entry.pos().y(),
                    entry.pos().z(),
                    entry.changeType().name()
            )));
        }

        int materialLimit = Math.min(16, this.state.materialDelta().size());
        for (int index = 0; index < materialLimit; index++) {
            var entry = this.state.materialDelta().get(index);
            root.child(UIComponents.label(Component.translatable(
                    "luma.compare.material_entry",
                    entry.blockId(),
                    entry.delta()
            )));
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout referenceRow(String labelKey, String value, java.util.function.Consumer<String> onChanged) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.child(UIComponents.label(Component.translatable(labelKey)));
        var box = UIComponents.textBox(Sizing.fill(100), value);
        box.onChanged().subscribe(onChanged::accept);
        row.child(box);
        return row;
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }
}
