package io.github.luma.ui.screen;

import io.github.luma.ui.controller.RecoveryScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RecoveryScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final RecoveryScreenController controller = new RecoveryScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private String status = "luma.status.recovery_ready";

    public RecoveryScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.recovery.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        Optional<io.github.luma.domain.model.RecoveryDraft> draft = this.controller.loadDraft(this.projectName);

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        root.child(UIComponents.label(Component.translatable("luma.screen.recovery.title", this.projectName)).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.status)));

        if (draft.isEmpty()) {
            root.child(UIComponents.label(Component.translatable("luma.recovery.no_draft")));
            root.child(UIComponents.button(Component.translatable("luma.action.open_project"), button -> this.router.openProjectIgnoringRecovery(this.parent, this.projectName)));
            return;
        }

        root.child(UIComponents.label(Component.translatable(
                "luma.recovery.draft_present",
                draft.get().changes().size(),
                draft.get().variantId()
        )));
        root.child(UIComponents.button(Component.translatable("luma.action.recovery_save"), button -> {
            this.status = this.controller.saveDraftVersion(this.projectName, "");
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        root.child(UIComponents.button(Component.translatable("luma.action.recovery_restore"), button -> {
            this.status = this.controller.restoreDraft(this.projectName);
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        root.child(UIComponents.button(Component.translatable("luma.action.recovery_discard"), button -> {
            this.status = this.controller.discardDraft(this.projectName);
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        root.child(UIComponents.button(Component.translatable("luma.action.open_project"), button -> this.router.openProjectIgnoringRecovery(this.parent, this.projectName)));
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }
}
