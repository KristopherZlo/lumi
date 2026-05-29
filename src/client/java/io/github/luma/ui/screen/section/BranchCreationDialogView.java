package io.github.luma.ui.screen.section;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.state.BranchCreationDialogState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class BranchCreationDialogView {

    private final ProjectScreenController previewController;
    private final Actions actions;

    public BranchCreationDialogView(ProjectScreenController previewController, Actions actions) {
        this.previewController = Objects.requireNonNull(previewController, "previewController");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout overlay(Model model) {
        FlowLayout overlay = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        overlay.surface(Surface.flat(0x99000000));
        overlay.padding(Insets.of(10));
        overlay.horizontalAlignment(HorizontalAlignment.CENTER);
        overlay.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(Math.max(260, Math.min(380, model.width() - 24)));
        frame.child(LumaUi.value(Component.translatable("luma.ideas.create_title")));
        frame.child(LumaUi.caption(Component.translatable(
                "luma.project.branch_create_from_selected",
                model.dialog().baseVersionName()
        )));
        if (model.statusBanner() != null) {
            frame.child(LumaUi.statusBanner(model.statusBanner()));
        }
        frame.child(this.commitPreview(model));

        ButtonComponent createButton = LumaUi.primaryButton(Component.translatable("luma.action.create_idea"), button -> this.actions.create());
        var branchNameInput = UIComponents.textBox(Sizing.fill(100), model.dialog().branchName());
        branchNameInput.setHint(Component.translatable("luma.idea.name_input"));
        branchNameInput.onChanged().subscribe(value -> {
            this.actions.updateBranchName(value == null ? "" : value);
            createButton.active(this.actions.canCreate());
        });
        frame.child(LumaUi.formField(
                Component.translatable("luma.idea.name_input"),
                Component.translatable("luma.ideas.name_help"),
                branchNameInput
        ));

        FlowLayout actionRow = LumaUi.actionRow();
        actionRow.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.actions.cancel()));
        createButton.active(model.dialog().canCreate());
        actionRow.child(createButton);
        frame.child(actionRow);
        overlay.child(frame);
        return overlay;
    }

    private FlowLayout commitPreview(Model model) {
        BranchCreationDialogState dialog = model.dialog();
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(8);
        row.child(ProjectUiSupport.versionPreview(
                this.previewController,
                model.projectName(),
                dialog.baseVersion(),
                72,
                54,
                72
        ));

        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(4);
        text.child(LumaUi.value(Component.literal(dialog.baseVersionName())));
        text.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(dialog.baseVersion().author()),
                ProjectUiSupport.formatTimestamp(dialog.baseVersion().createdAt())
        )));
        if (!dialog.baseVariantName().isBlank()) {
            FlowLayout meta = LumaUi.actionRow();
            meta.child(LumaUi.chip(Component.translatable("luma.build.current_idea", dialog.baseVariantName())));
            text.child(meta);
        }
        row.child(text);
        card.child(row);
        return card;
    }

    public record Model(
            String projectName,
            int width,
            BranchCreationDialogState dialog,
            Component statusBanner
    ) {

        public Model {
            projectName = projectName == null ? "" : projectName;
            dialog = Objects.requireNonNull(dialog, "dialog");
        }
    }

    public interface Actions {

        void updateBranchName(String value);

        boolean canCreate();

        void create();

        void cancel();
    }
}
