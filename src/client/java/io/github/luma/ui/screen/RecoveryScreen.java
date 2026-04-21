package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.RecoveryScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
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

public final class RecoveryScreen extends LumaScreen {

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

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.recovery.title", this.projectName)));
        frame.child(LumaUi.statusBanner(Component.translatable(this.status)));

        FlowLayout body = LumaUi.screenBody();
        frame.child(LumaUi.screenScroll(body));

        if (draft.isEmpty()) {
            FlowLayout empty = LumaUi.emptyState(
                    Component.translatable("luma.recovery.empty_title"),
                    Component.translatable("luma.recovery.no_draft")
            );
            FlowLayout actions = LumaUi.actionRow();
            actions.child(UIComponents.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(this.parent, this.projectName)));
            empty.child(actions);
            body.child(empty);
            return;
        }

        FlowLayout summary = LumaUi.sectionCard(
                Component.translatable("luma.recovery.summary_title"),
                Component.translatable(
                        "luma.recovery.draft_present",
                        draft.get().changes().size(),
                        draft.get().variantId()
                )
        );
        summary.child(LumaUi.caption(Component.translatable("luma.recovery.summary_help")));
        summary.child(LumaUi.caption(Component.translatable("luma.recovery.summary_behavior")));
        body.child(summary);

        FlowLayout actions = LumaUi.sectionCard(
                Component.translatable("luma.recovery.actions_title"),
                Component.translatable("luma.recovery.actions_help")
        );
        FlowLayout primaryActions = LumaUi.actionRow();
        primaryActions.child(UIComponents.button(Component.translatable("luma.action.recovery_save"), button -> {
            this.status = this.controller.saveDraftVersion(this.projectName, "");
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        primaryActions.child(UIComponents.button(Component.translatable("luma.action.recovery_restore"), button -> {
            this.status = this.controller.restoreDraft(this.projectName);
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        actions.child(primaryActions);
        actions.child(LumaUi.caption(Component.translatable("luma.recovery.save_help")));
        actions.child(LumaUi.caption(Component.translatable("luma.recovery.restore_help")));

        FlowLayout exitActions = LumaUi.actionRow();
        exitActions.child(UIComponents.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(this.parent, this.projectName)));
        actions.child(exitActions);
        body.child(actions);

        FlowLayout destructive = LumaUi.sectionCard(
                Component.translatable("luma.recovery.discard_title"),
                Component.translatable("luma.recovery.discard_help")
        );
        FlowLayout destructiveActions = LumaUi.actionRow();
        destructiveActions.child(UIComponents.button(Component.translatable("luma.action.recovery_discard"), button -> {
            this.status = this.controller.discardDraft(this.projectName);
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, this.status);
        }));
        destructive.child(destructiveActions);
        destructive.child(LumaUi.caption(Component.translatable("luma.recovery.discard_extra_help")));
        body.child(destructive);
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }
}
