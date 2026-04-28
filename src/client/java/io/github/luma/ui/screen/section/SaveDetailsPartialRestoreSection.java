package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.PartialRestoreFormState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

public final class SaveDetailsPartialRestoreSection {

    private final Actions actions;

    public SaveDetailsPartialRestoreSection(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout section(Model model) {
        model.form().ensureDefaults(model.projectBounds(), model.fallbackBounds());
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.partial_restore.title"),
                Component.translatable("luma.partial_restore.help")
        );
        section.child(this.boundsRow(
                "luma.partial_restore.min",
                model.form().minX(),
                model.form().minY(),
                model.form().minZ(),
                model.form()::setMinX,
                model.form()::setMinY,
                model.form()::setMinZ
        ));
        section.child(this.boundsRow(
                "luma.partial_restore.max",
                model.form().maxX(),
                model.form().maxY(),
                model.form().maxZ(),
                model.form()::setMaxX,
                model.form()::setMaxY,
                model.form()::setMaxZ
        ));

        if (model.form().summary() != null) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.partial_restore.summary",
                    model.form().summary().changedBlocks(),
                    model.form().summary().touchedChunks().size()
            )));
        }

        FlowLayout actionsRow = LumaUi.actionRow();
        actionsRow.child(LumaUi.button(
                Component.translatable("luma.action.preview_partial_restore"),
                button -> this.preview(model)
        ));
        ButtonComponent applyButton = LumaUi.primaryButton(
                Component.translatable("luma.action.apply_partial_restore"),
                button -> this.apply(model)
        );
        applyButton.active(!model.operationActive()
                && model.form().summary() != null
                && model.form().summary().changedBlocks() > 0);
        actionsRow.child(applyButton);
        section.child(actionsRow);
        return section;
    }

    private FlowLayout boundsRow(
            String labelKey,
            String x,
            String y,
            String z,
            Consumer<String> onX,
            Consumer<String> onY,
            Consumer<String> onZ
    ) {
        FlowLayout row = LumaUi.actionRow();
        row.child(LumaUi.caption(Component.translatable(labelKey)));

        var xBox = UIComponents.textBox(Sizing.fixed(60), x);
        xBox.onChanged().subscribe(onX::accept);
        row.child(xBox);

        var yBox = UIComponents.textBox(Sizing.fixed(60), y);
        yBox.onChanged().subscribe(onY::accept);
        row.child(yBox);

        var zBox = UIComponents.textBox(Sizing.fixed(60), z);
        zBox.onChanged().subscribe(onZ::accept);
        row.child(zBox);
        return row;
    }

    private void preview(Model model) {
        Optional<PartialRestoreRequest> request = model.form().request(model.projectName(), model.version().id(), model.actor());
        if (request.isEmpty()) {
            this.actions.invalidBounds();
            return;
        }
        this.actions.preview(request.get());
    }

    private void apply(Model model) {
        Optional<PartialRestoreRequest> request = model.form().request(model.projectName(), model.version().id(), model.actor());
        if (request.isEmpty()) {
            this.actions.invalidBounds();
            return;
        }
        this.actions.apply(request.get());
    }

    public record Model(
            String projectName,
            ProjectVersion version,
            String actor,
            boolean operationActive,
            PartialRestoreFormState form,
            Bounds3i projectBounds,
            Bounds3i fallbackBounds
    ) {
    }

    public interface Actions {

        void preview(PartialRestoreRequest request);

        void apply(PartialRestoreRequest request);

        void invalidBounds();
    }
}
