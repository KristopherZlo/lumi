package io.github.luma.ui.screen.section;

import io.github.luma.client.update.UpdateProjectNotice;
import io.github.luma.ui.LumaUi;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class UpdateNoticeDialogView {

    private final Actions actions;

    public UpdateNoticeDialogView(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout overlay(Model model) {
        FlowLayout overlay = LumaUi.modalOverlay();

        UpdateProjectNotice notice = model.notice();
        FlowLayout frame = LumaUi.modalFrame(Math.max(280, Math.min(420, model.width() - 24)));
        frame.child(LumaUi.value(Component.translatable("luma.update.card_title", notice.version())));
        frame.child(LumaUi.caption(Component.translatable("luma.update.card_body", notice.minecraftVersion())));
        if (!notice.title().isBlank()) {
            frame.child(LumaUi.accent(Component.literal(notice.title())));
        }
        if (!notice.changeLines().isEmpty()) {
            frame.child(this.changeList(notice));
        }

        FlowLayout actionRow = LumaUi.actionRow();
        actionRow.child(LumaUi.button(Component.translatable("luma.action.skip"), button -> this.actions.skip()));
        actionRow.child(LumaUi.primaryButton(
                Component.translatable("luma.action.download_update"),
                button -> this.actions.download()
        ));
        frame.child(actionRow);
        overlay.child(frame);
        return overlay;
    }

    private FlowLayout changeList(UpdateProjectNotice notice) {
        FlowLayout changes = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        changes.child(LumaUi.value(Component.translatable("luma.update.changes_title")));
        changes.child(LumaUi.caption(Component.translatable(
                "luma.update.changes_count",
                notice.changeCharacterCount()
        )));
        for (String line : notice.changeLines()) {
            changes.child(LumaUi.caption(Component.literal("- " + line)));
        }
        return changes;
    }

    public record Model(int width, UpdateProjectNotice notice) {

        public Model {
            notice = Objects.requireNonNull(notice, "notice");
        }
    }

    public interface Actions {

        void skip();

        void download();
    }
}
