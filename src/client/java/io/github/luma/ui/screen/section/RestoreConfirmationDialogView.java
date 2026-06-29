package io.github.luma.ui.screen.section;

import io.github.luma.ui.LumaUi;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class RestoreConfirmationDialogView {

    private final Actions actions;

    public RestoreConfirmationDialogView(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout overlay(Model model) {
        FlowLayout overlay = LumaUi.modalOverlay();

        FlowLayout frame = LumaUi.modalFrame(Math.max(280, Math.min(420, model.width() - 24)), false);
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
        if (model.hasEntityTypes()) {
            frame.child(this.entityTypes(model));
        }

        FlowLayout actionsRow = LumaUi.actionRow();
        actionsRow.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.actions.cancel()));
        ButtonComponent wholeRestore = LumaUi.primaryButton(
                model.primaryAction(),
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

    private FlowLayout entityTypes(Model model) {
        FlowLayout section = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(4);
        header.verticalAlignment(VerticalAlignment.CENTER);
        header.child(LumaUi.value(Component.translatable(
                "luma.restore.entities_title",
                model.totalEntityCount()
        )));
        header.child(UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1)));
        header.child(LumaUi.iconButton(
                model.entityListExpanded() ? "chevron-up" : "chevron-down",
                Component.translatable(model.entityListExpanded() ? "luma.action.collapse" : "luma.action.expand"),
                button -> this.actions.toggleEntityList()
        ));
        section.child(header);
        section.child(LumaUi.caption(Component.translatable("luma.restore.entities_help")));
        if (model.entityListExpanded()) {
            FlowLayout list = LumaUi.revealGroup();
            for (EntityTypeOption option : model.entityTypes()) {
                var toggle = UIComponents.checkbox(Component.literal(option.entityType() + " (x" + option.count() + ")"));
                toggle.checked(option.included());
                toggle.onChanged(checked -> {
                    if (!model.operationActive()) {
                        this.actions.toggleEntityType(option.entityType());
                    }
                });
                list.child(toggle);
            }
            section.child(list);
        }
        return section;
    }

    public record Model(
            int width,
            Component title,
            Component help,
            Component target,
            boolean safetySnapshot,
            boolean initialRestore,
            boolean hasSelection,
            boolean operationActive,
            boolean entityListExpanded,
            List<EntityTypeOption> entityTypes,
            Component primaryAction
    ) {

        public Model(
                int width,
                Component title,
                Component help,
                Component target,
                boolean safetySnapshot,
                boolean initialRestore,
                boolean hasSelection,
                boolean operationActive
        ) {
            this(
                    width,
                    title,
                    help,
                    target,
                    safetySnapshot,
                    initialRestore,
                    hasSelection,
                    operationActive,
                    false,
                    List.of(),
                    Component.translatable(hasSelection ? "luma.action.restore_whole_save" : "luma.action.restore")
            );
        }

        public Model(
                int width,
                Component title,
                Component help,
                Component target,
                boolean safetySnapshot,
                boolean initialRestore,
                boolean hasSelection,
                boolean operationActive,
                boolean entityListExpanded,
                List<EntityTypeOption> entityTypes
        ) {
            this(
                    width,
                    title,
                    help,
                    target,
                    safetySnapshot,
                    initialRestore,
                    hasSelection,
                    operationActive,
                    entityListExpanded,
                    entityTypes,
                    Component.translatable(hasSelection ? "luma.action.restore_whole_save" : "luma.action.restore")
            );
        }

        public Model {
            title = Objects.requireNonNull(title, "title");
            help = Objects.requireNonNull(help, "help");
            target = Objects.requireNonNull(target, "target");
            entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
            primaryAction = primaryAction == null
                    ? Component.translatable(hasSelection ? "luma.action.restore_whole_save" : "luma.action.restore")
                    : primaryAction;
        }

        public boolean hasEntityTypes() {
            return !this.entityTypes.isEmpty();
        }

        public int totalEntityCount() {
            int total = 0;
            for (EntityTypeOption option : this.entityTypes) {
                total += option.count();
            }
            return total;
        }
    }

    public record EntityTypeOption(
            String entityType,
            int count,
            boolean included
    ) {

        public EntityTypeOption {
            entityType = entityType == null || entityType.isBlank() ? "unknown:entity" : entityType;
            count = Math.max(0, count);
        }
    }

    public interface Actions {

        void cancel();

        void restoreWhole();

        void restoreSelectedArea();

        void restoreOutsideSelection();

        default void toggleEntityList() {
        }

        default void toggleEntityType(String entityType) {
        }
    }
}
