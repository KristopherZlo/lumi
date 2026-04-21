package io.github.luma.ui.tab;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

public final class LogTabView {

    private LogTabView() {
    }

    public static FlowLayout build(ProjectViewState state) {
        FlowLayout container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        container.gap(6);

        if (state.project() != null && state.project().isLegacySnapshotProject()) {
            container.child(LumaUi.caption(Component.translatable("luma.log.legacy_notice")));
        }

        if (state.recoveryDraft() != null) {
            container.child(LumaUi.caption(Component.translatable(
                    "luma.recovery.draft_present",
                    state.recoveryDraft().changes().size(),
                    state.recoveryDraft().variantId()
            )));
        } else {
            container.child(LumaUi.caption(Component.translatable("luma.recovery.no_draft")));
        }

        if (state.journal().isEmpty()) {
            container.child(LumaUi.caption(Component.translatable("luma.log.empty")));
            return container;
        }

        for (var entry : state.journal()) {
            FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
            card.child(LumaUi.value(Component.translatable("luma.log.entry_header", entry.type(), entry.timestamp().toString())));
            card.child(LumaUi.caption(Component.translatable("luma.log.entry_message", entry.message())));
            if (entry.versionId() != null && !entry.versionId().isBlank()) {
                card.child(LumaUi.caption(Component.translatable("luma.log.entry_version", entry.versionId())));
            }
            if (entry.variantId() != null && !entry.variantId().isBlank()) {
                card.child(LumaUi.caption(Component.translatable("luma.log.entry_variant", entry.variantId())));
            }
            container.child(card);
        }

        return container;
    }
}
