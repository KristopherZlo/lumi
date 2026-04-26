package io.github.luma.ui.tab;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

public final class IntegrationsTabView {

    private IntegrationsTabView() {
    }

    public static FlowLayout build(ProjectViewState state) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        container.child(LumaUi.caption(Component.translatable(
                state.integrityReport().valid() ? "luma.integrity.valid" : "luma.integrity.invalid"
        )));

        for (var error : state.integrityReport().errors()) {
            container.child(LumaUi.danger(Component.translatable("luma.integrity.error", error)));
        }
        for (var warning : state.integrityReport().warnings()) {
            container.child(LumaUi.caption(Component.translatable("luma.integrity.warning", warning)));
        }

        if (state.integrations().isEmpty()) {
            container.child(LumaUi.caption(Component.translatable("luma.integrations.empty")));
            return container;
        }

        for (var integration : state.integrations()) {
            container.child(LumaUi.caption(Component.translatable(
                    "luma.integrations.entry",
                    integration.toolId(),
                    integration.available() ? Component.translatable("luma.common.available") : Component.translatable("luma.common.unavailable"),
                    integration.modeLabel(),
                    String.join(", ", integration.capabilityLabels())
            )));
        }

        return container;
    }
}
