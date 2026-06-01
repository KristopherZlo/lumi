package io.github.luma.ui.screen.section;

import io.github.luma.ui.LumaUi;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class RestoreConfirmationDialogView {

    private final Actions actions;

    public RestoreConfirmationDialogView(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout overlay(Model model) {
        FlowLayout overlay = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        overlay.surface(Surface.flat(0x99000000));
        overlay.padding(Insets.of(10));
        overlay.horizontalAlignment(HorizontalAlignment.CENTER);
        overlay.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(Math.max(280, Math.min(420, model.width() - 24)));
        frame.child(LumaUi.value(model.title()));
        frame.child(LumaUi.caption(model.help()));
        if (model.safetySnapshot()) {
            frame.child(LumaUi.caption(Component.translatable("luma.restore.confirm_safety")));
        }
        if (model.initialRestore()) {
            frame.child(LumaUi.danger(Component.translatable("luma.restore.initial_confirm_warning")));
        }
        if (model.hasSelection()) {
            frame.child(LumaUi.caption(Component.translatable("luma.restore.selection_choice_help")));
        }
        frame.child(LumaUi.caption(model.target()));

        FlowLayout actionsRow = LumaUi.actionRow();
        actionsRow.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.actions.cancel()));
        ButtonComponent wholeRestore = LumaUi.primaryButton(
                Component.translatable(model.hasSelection() ? "luma.action.restore_whole_save" : "luma.action.restore"),
                button -> this.actions.restoreWhole()
        );
        wholeRestore.active(!model.operationActive());
        actionsRow.child(wholeRestore);
        frame.child(actionsRow);

        if (model.hasSelection()) {
            FlowLayout partialActions = LumaUi.actionRow();
            ButtonComponent selectedOnly = LumaUi.button(
                    Component.translatable("luma.action.restore_only_selected_area"),
                    button -> this.actions.restoreSelectedArea()
            );
            selectedOnly.active(!model.operationActive());
            partialActions.child(selectedOnly);

            ButtonComponent outsideOnly = LumaUi.button(
                    Component.translatable("luma.action.restore_everything_except_selection"),
                    button -> this.actions.restoreOutsideSelection()
            );
            outsideOnly.active(!model.operationActive());
            partialActions.child(outsideOnly);
            frame.child(partialActions);
        }
        overlay.child(frame);
        return overlay;
    }

    public record Model(
            int width,
            Component title,
            Component help,
            Component target,
            boolean safetySnapshot,
            boolean initialRestore,
            boolean hasSelection,
            boolean operationActive
    ) {

        public Model {
            title = Objects.requireNonNull(title, "title");
            help = Objects.requireNonNull(help, "help");
            target = Objects.requireNonNull(target, "target");
        }
    }

    public interface Actions {

        void cancel();

        void restoreWhole();

        void restoreSelectedArea();

        void restoreOutsideSelection();
    }
}
