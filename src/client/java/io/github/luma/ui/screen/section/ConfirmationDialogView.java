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

public final class ConfirmationDialogView {

    private final Actions actions;

    public ConfirmationDialogView(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout overlay(Model model) {
        FlowLayout overlay = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        overlay.surface(Surface.flat(0x99000000));
        overlay.padding(Insets.of(10));
        overlay.horizontalAlignment(HorizontalAlignment.CENTER);
        overlay.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(Math.max(260, Math.min(380, model.width() - 24)));
        frame.child(LumaUi.value(model.title()));
        frame.child(LumaUi.caption(model.help()));
        if (model.warning() != null) {
            frame.child(LumaUi.danger(model.warning()));
        }

        FlowLayout actionsRow = LumaUi.actionRow();
        actionsRow.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.actions.cancel()));
        ButtonComponent primary = LumaUi.primaryButton(model.primaryAction(), button -> this.actions.confirm());
        primary.active(!model.primaryDisabled());
        actionsRow.child(primary);
        frame.child(actionsRow);
        overlay.child(frame);
        return overlay;
    }

    public record Model(
            int width,
            Component title,
            Component help,
            Component warning,
            Component primaryAction,
            boolean primaryDisabled
    ) {

        public Model {
            title = Objects.requireNonNull(title, "title");
            help = Objects.requireNonNull(help, "help");
            primaryAction = Objects.requireNonNull(primaryAction, "primaryAction");
        }
    }

    public interface Actions {

        void confirm();

        void cancel();
    }
}
