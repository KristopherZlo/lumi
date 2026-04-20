package io.github.luma.ui.tab;

import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.preview.ProjectPreviewTextureCache;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

public final class PreviewTabView {

    private PreviewTabView() {
    }

    public static FlowLayout build(
            ProjectViewState state,
            String projectName,
            ProjectScreenController controller,
            Consumer<String> onStatusChanged
    ) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        if (state.selectedVersion() == null) {
            container.child(UIComponents.label(Component.translatable("luma.preview.no_version")));
            return container;
        }

        container.child(UIComponents.label(Component.translatable(
                "luma.preview.summary",
                state.selectedVersion().id(),
                state.selectedVersion().preview().width(),
                state.selectedVersion().preview().height()
        )));

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.translatable("luma.action.refresh_preview"), button -> {
            ProjectPreviewTextureCache.release(projectName, state.selectedVersion().id());
            onStatusChanged.accept(controller.refreshPreview(projectName, state.selectedVersion().id()));
        }));
        container.child(actions);

        if (state.selectedVersion().preview().fileName() == null || state.selectedVersion().preview().fileName().isBlank()) {
            container.child(UIComponents.label(Component.translatable("luma.preview.unavailable")));
            return container;
        }

        String previewPath = controller.resolvePreviewPath(projectName, state.selectedVersion().id());
        if (previewPath == null || previewPath.isBlank() || !Files.exists(Path.of(previewPath))) {
            container.child(UIComponents.label(Component.translatable("luma.preview.missing_file")));
            return container;
        }

        try {
            var texture = UIComponents.texture(
                    ProjectPreviewTextureCache.load(projectName, state.selectedVersion().id(), Path.of(previewPath)),
                    0,
                    0,
                    state.selectedVersion().preview().width(),
                    state.selectedVersion().preview().height(),
                    state.selectedVersion().preview().width(),
                    state.selectedVersion().preview().height()
            );
            int width = Math.min(192, Math.max(96, state.selectedVersion().preview().width() * 2));
            int height = Math.max(96, (int) Math.round(
                    (double) state.selectedVersion().preview().height()
                            * (double) width
                            / Math.max(1, state.selectedVersion().preview().width())
            ));
            texture.sizing(Sizing.fixed(width), Sizing.fixed(height));
            container.child(texture);
        } catch (Exception exception) {
            container.child(UIComponents.label(Component.translatable("luma.preview.load_failed")));
        }

        container.child(UIComponents.label(Component.translatable("luma.preview.path", previewPath)));
        return container;
    }
}
