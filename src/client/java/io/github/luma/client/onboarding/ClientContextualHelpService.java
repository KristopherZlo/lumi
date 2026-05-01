package io.github.luma.client.onboarding;

import io.github.luma.LumaMod;
import java.io.IOException;

public final class ClientContextualHelpService {

    private final ClientOnboardingStateRepository repository;

    public ClientContextualHelpService() {
        this(new ClientOnboardingStateRepository());
    }

    public ClientContextualHelpService(ClientOnboardingStateRepository repository) {
        this.repository = repository;
    }

    public boolean shouldShowHint(ClientContextualHelpHint hint) {
        if (hint == null) {
            return false;
        }
        return !this.repository.load().dismissedContextualHintIds().contains(hint.id());
    }

    public void dismissHint(ClientContextualHelpHint hint) {
        if (hint == null) {
            return;
        }
        ClientOnboardingState state = this.repository.load()
                .withDismissedContextualHint(hint.id());
        this.save(state);
    }

    public void resetHints() {
        this.save(this.repository.load().withClearedContextualHints());
    }

    private void save(ClientOnboardingState state) {
        try {
            this.repository.save(state);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to save Lumi contextual help state", exception);
        }
    }
}
