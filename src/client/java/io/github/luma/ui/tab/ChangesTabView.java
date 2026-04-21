package io.github.luma.ui.tab;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

public final class ChangesTabView {

    private static final int SAMPLE_LIMIT = 24;

    private ChangesTabView() {
    }

    public static FlowLayout build(ProjectViewState state) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        if (state.selectedVersion() == null) {
            container.child(LumaUi.caption(Component.translatable("luma.changes.no_version")));
            return container;
        }

        if (state.selectedVersionDiff() == null) {
            container.child(LumaUi.caption(Component.translatable("luma.changes.no_parent")));
            return container;
        }

        container.child(LumaUi.value(Component.translatable(
                "luma.changes.summary",
                state.selectedVersion().id(),
                state.selectedVersionDiff().changedBlockCount(),
                state.selectedVersionDiff().changedChunks()
        )));

        if (state.selectedVersionDiff().changedBlocks().isEmpty()) {
            container.child(LumaUi.caption(Component.translatable("luma.changes.empty")));
            return container;
        }

        int shown = 0;
        for (var entry : state.selectedVersionDiff().changedBlocks()) {
            if (shown++ >= SAMPLE_LIMIT) {
                break;
            }

            String leftState = entry.leftState() == null || entry.leftState().isBlank() ? "-" : entry.leftState();
            String rightState = entry.rightState() == null || entry.rightState().isBlank() ? "-" : entry.rightState();
            container.child(LumaUi.caption(Component.translatable(
                    "luma.changes.entry",
                    Component.translatable(changeTypeKey(entry.changeType())),
                    entry.pos().x(),
                    entry.pos().y(),
                    entry.pos().z(),
                    trim(leftState),
                    trim(rightState)
            )));
        }

        if (state.selectedVersionDiff().changedBlocks().size() > SAMPLE_LIMIT) {
            container.child(LumaUi.caption(Component.translatable(
                    "luma.changes.more",
                    state.selectedVersionDiff().changedBlocks().size() - SAMPLE_LIMIT
            )));
        }

        return container;
    }

    private static String changeTypeKey(ChangeType type) {
        return switch (type) {
            case ADDED -> "luma.change_type.added";
            case REMOVED -> "luma.change_type.removed";
            case CHANGED -> "luma.change_type.changed";
        };
    }

    private static String trim(String value) {
        return value.length() > 64 ? value.substring(0, 61) + "..." : value;
    }
}
