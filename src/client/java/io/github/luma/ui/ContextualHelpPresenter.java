package io.github.luma.ui;

import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.wispforest.owo.ui.container.FlowLayout;

public final class ContextualHelpPresenter {

    private final ClientContextualHelpService helpService;
    private final Runnable rebuildAction;

    public ContextualHelpPresenter(ClientContextualHelpService helpService, Runnable rebuildAction) {
        this.helpService = helpService == null ? new ClientContextualHelpService() : helpService;
        this.rebuildAction = rebuildAction == null ? () -> {
        } : rebuildAction;
    }

    public boolean addHint(FlowLayout parent, ClientContextualHelpHint hint) {
        if (parent == null || !this.shouldShowHint(hint)) {
            return false;
        }
        parent.child(LumaUi.contextualHint(hint, button -> {
            this.helpService.dismissHint(hint);
            this.rebuildAction.run();
        }));
        return true;
    }

    public boolean shouldShowHint(ClientContextualHelpHint hint) {
        return this.helpService.shouldShowHint(hint);
    }
}
