package io.github.luma.ui.tab;

import io.github.luma.ui.MaterialEntryView;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

public final class MaterialsTabView {

    private static final int MATERIAL_LIMIT = 32;

    private MaterialsTabView() {
    }

    public static FlowLayout build(ProjectViewState state) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        if (state.selectedVersion() == null) {
            container.child(LumaUi.caption(Component.translatable("luma.materials.no_version")));
            return container;
        }

        if (state.materialDelta().isEmpty()) {
            container.child(LumaUi.caption(Component.translatable("luma.materials.empty")));
            return container;
        }

        int shown = 0;
        for (var entry : state.materialDelta()) {
            if (shown++ >= MATERIAL_LIMIT) {
                break;
            }

            container.child(MaterialEntryView.row(
                    entry.blockId(),
                    Component.translatable(
                            "luma.materials.entry",
                            entry.blockId(),
                            entry.leftCount(),
                            entry.rightCount(),
                            entry.delta()
                    )
            ));
        }

        if (state.materialDelta().size() > MATERIAL_LIMIT) {
            container.child(LumaUi.caption(Component.translatable(
                    "luma.materials.more",
                    state.materialDelta().size() - MATERIAL_LIMIT
            )));
        }

        return container;
    }
}
