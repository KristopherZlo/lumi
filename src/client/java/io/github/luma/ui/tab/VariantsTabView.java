package io.github.luma.ui.tab;

import io.github.luma.ui.LumaUi;
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
            container.child(LumaUi.accent(Component.translatable("luma.variant.active_current", state.project().activeVariantId())));
        }

        FlowLayout creationPanel = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        creationPanel.child(LumaUi.caption(Component.translatable(
                "luma.variant.creation_hint",
                state.selectedVersion() == null ? "" : state.selectedVersion().id()
        )));

        FlowLayout creationRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        creationRow.gap(6);
        var variantNameInput = UIComponents.textBox(Sizing.fill(40), variantNameSupplier.get());
        variantNameInput.onChanged().subscribe(onVariantNameChanged::accept);
        creationRow.child(variantNameInput);

        var baseVersionInput = UIComponents.textBox(Sizing.fill(25), baseVersionSupplier.get());
        baseVersionInput.onChanged().subscribe(onBaseVersionChanged::accept);
        creationRow.child(baseVersionInput);

        creationRow.child(UIComponents.button(Component.translatable("luma.action.variant_create"), button -> {
            String rawName = variantNameSupplier.get();
            String variantName = rawName == null || rawName.isBlank() ? "variant-" + Math.max(1, state.variants().size()) : rawName;
            onStatusChanged.accept(controller.createVariant(projectName, variantName, baseVersionSupplier.get()));
        }));
        creationPanel.child(creationRow);
        container.child(creationPanel);

        if (state.variants().isEmpty()) {
            container.child(LumaUi.caption(Component.translatable("luma.variant.empty")));
            return container;
        }

        for (var variant : state.variants()) {
            FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
            card.gap(4);
            boolean active = state.project() != null && variant.id().equals(state.project().activeVariantId());
            FlowLayout top = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
            top.gap(6);
            top.child(LumaUi.value(Component.literal(variant.name())));
            top.child(LumaUi.chip(Component.literal(variant.id())));
            if (active) {
                top.child(LumaUi.chip(Component.translatable("luma.variant.active_badge")));
            }
            card.child(top);
            card.child(LumaUi.caption(Component.translatable("luma.variant.entry_head", blankToFallback(variant.headVersionId()))));
            card.child(LumaUi.caption(Component.translatable("luma.variant.entry_base", blankToFallback(variant.baseVersionId()))));
            card.child(UIComponents.button(Component.translatable("luma.action.variant_switch"), button -> {
                onStatusChanged.accept(controller.switchVariant(projectName, variant.id()));
            }));
            container.child(card);
        }

        return container;
    }

    private static String blankToFallback(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
