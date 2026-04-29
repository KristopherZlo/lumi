package io.github.luma.client.onboarding;

import io.github.luma.LumaMod;
import java.io.IOException;

public final class ClientOnboardingService {

    public static final int CURRENT_ONBOARDING_VERSION = 1;

    private final ClientOnboardingStateRepository repository;

    public ClientOnboardingService() {
        this(new ClientOnboardingStateRepository());
    }

    public ClientOnboardingService(ClientOnboardingStateRepository repository) {
        this.repository = repository;
    }

    public boolean shouldShowOnboarding() {
        return this.repository.load().completedOnboardingVersion() < CURRENT_ONBOARDING_VERSION;
    }

    public void markCompleted() {
        ClientOnboardingState state = this.repository.load()
                .withCompletedVersion(CURRENT_ONBOARDING_VERSION);
        try {
            this.repository.save(state);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to save Lumi onboarding state", exception);
        }
    }
}
