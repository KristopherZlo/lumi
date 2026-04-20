package io.github.luma.ui.tab;

import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

public final class HistoryTabView {

    private HistoryTabView() {
    }

    public static FlowLayout build(
            ProjectViewState state,
            ProjectScreenController controller,
            String projectName,
            Supplier<String> saveMessageSupplier,
            Consumer<String> onSaveMessageChanged,
            Consumer<String> onVersionSelected,
            Consumer<String> onCompareRequested,
            Consumer<String> onStatusChanged
    ) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        FlowLayout toolbar = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        toolbar.gap(6);
        toolbar.child(UIComponents.label(Component.translatable("luma.history.message_input")));
        var messageInput = UIComponents.textBox(Sizing.fill(70), saveMessageSupplier.get());
        messageInput.onChanged().subscribe(onSaveMessageChanged::accept);
        toolbar.child(messageInput);
        toolbar.child(UIComponents.button(Component.translatable("luma.action.save_version"), button -> {
            onStatusChanged.accept(controller.saveVersion(projectName, saveMessageSupplier.get()));
        }));
        container.child(toolbar);

        if (state.versions().isEmpty()) {
            container.child(UIComponents.label(Component.translatable("luma.history.empty")));
            return container;
        }

        for (var version : state.versions()) {
            FlowLayout card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
            card.gap(3);
            card.child(UIComponents.label(Component.translatable(
                    state.selectedVersion() != null && version.id().equals(state.selectedVersion().id())
                            ? "luma.history.version_header_selected"
                            : "luma.history.version_header",
                    version.id(),
                    Component.translatable(versionKindKey(version.versionKind()))
            )));
            card.child(UIComponents.label(Component.translatable("luma.history.version_message", version.message())));
            card.child(UIComponents.label(Component.translatable("luma.history.version_author", version.author())));
            card.child(UIComponents.label(Component.translatable("luma.history.version_date", version.createdAt().toString())));
            card.child(UIComponents.label(Component.translatable(
                    "luma.history.version_changes",
                    version.stats().changedBlocks(),
                    version.stats().changedChunks(),
                    version.stats().distinctBlockTypes()
            )));
            card.child(UIComponents.label(Component.translatable(
                    "luma.history.version_source",
                    version.sourceInfo().operationLabel()
            )));
            FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
            actions.gap(6);
            actions.child(UIComponents.button(Component.translatable("luma.action.select"), button -> onVersionSelected.accept(version.id())));
            actions.child(UIComponents.button(Component.translatable("luma.action.compare"), button -> onCompareRequested.accept(version.id())));
            actions.child(UIComponents.button(Component.translatable("luma.action.restore"), button -> {
                onStatusChanged.accept(controller.restoreVersion(projectName, version.id()));
            }));
            card.child(actions);
            container.child(card);
        }

        return container;
    }

    private static String versionKindKey(io.github.luma.domain.model.VersionKind versionKind) {
        return switch (versionKind) {
            case INITIAL -> "luma.version_kind.initial";
            case MANUAL -> "luma.version_kind.manual";
            case RECOVERY -> "luma.version_kind.recovery";
            case RESTORE -> "luma.version_kind.restore";
            case LEGACY -> "luma.version_kind.legacy";
        };
    }
}
