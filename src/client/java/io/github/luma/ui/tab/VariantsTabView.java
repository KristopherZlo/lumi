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

public final class VariantsTabView {

    private VariantsTabView() {
    }

    public static FlowLayout build(
            ProjectViewState state,
            ProjectScreenController controller,
            String projectName,
            Supplier<String> variantNameSupplier,
            Supplier<String> baseVersionSupplier,
            Consumer<String> onVariantNameChanged,
            Consumer<String> onBaseVersionChanged,
            Consumer<String> onStatusChanged
    ) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        if (state.project() != null) {
            container.child(UIComponents.label(Component.translatable(
                    "luma.variant.active_current",
                    state.project().activeVariantId()
            )));
        }

        FlowLayout creationRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        creationRow.gap(6);
        creationRow.child(UIComponents.label(Component.translatable("luma.variant.name_input")));
        var variantNameInput = UIComponents.textBox(Sizing.fill(40), variantNameSupplier.get());
        variantNameInput.onChanged().subscribe(onVariantNameChanged::accept);
        creationRow.child(variantNameInput);

        creationRow.child(UIComponents.label(Component.translatable("luma.variant.base_version_input")));
        var baseVersionInput = UIComponents.textBox(Sizing.fill(25), baseVersionSupplier.get());
        baseVersionInput.onChanged().subscribe(onBaseVersionChanged::accept);
        creationRow.child(baseVersionInput);

        creationRow.child(UIComponents.button(Component.translatable("luma.action.variant_create"), button -> {
            String rawName = variantNameSupplier.get();
            String variantName = rawName == null || rawName.isBlank() ? "variant-" + Math.max(1, state.variants().size()) : rawName;
            onStatusChanged.accept(controller.createVariant(projectName, variantName, baseVersionSupplier.get()));
        }));
        container.child(creationRow);

        if (state.variants().isEmpty()) {
            container.child(UIComponents.label(Component.translatable("luma.variant.empty")));
            return container;
        }

        for (var variant : state.variants()) {
            FlowLayout card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
            card.gap(3);
            boolean active = state.project() != null && variant.id().equals(state.project().activeVariantId());
            card.child(UIComponents.label(Component.translatable(
                    active ? "luma.variant.entry_active" : "luma.variant.entry",
                    variant.name(),
                    variant.id()
            )));
            card.child(UIComponents.label(Component.translatable("luma.variant.entry_base", variant.baseVersionId())));
            card.child(UIComponents.label(Component.translatable("luma.variant.entry_head", variant.headVersionId())));
            card.child(UIComponents.label(Component.translatable("luma.variant.entry_created", variant.createdAt().toString())));
            card.child(UIComponents.button(Component.translatable("luma.action.variant_switch"), button -> {
                onStatusChanged.accept(controller.switchVariant(projectName, variant.id()));
            }));
            container.child(card);
        }

        return container;
    }
}
