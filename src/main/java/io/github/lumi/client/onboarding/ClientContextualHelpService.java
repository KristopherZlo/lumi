package io.github.lumi.client.onboarding;

import java.util.Collection;
import java.util.Objects;

/** Coordinates contextual-tip visibility without coupling screens to storage. */
public final class ClientContextualHelpService {
    private final ClientOnboardingStateRepository repository;

    public ClientContextualHelpService() {
        this(new ClientOnboardingStateRepository());
    }

    public ClientContextualHelpService(ClientOnboardingStateRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public boolean shouldShowHint(ClientContextualHelpHint hint) {
        return hint != null && !repository.dismissedHintIds().contains(hint.id());
    }

    public void dismissHint(ClientContextualHelpHint hint) {
        if (hint != null) {
            repository.dismissHint(hint.id());
        }
    }

    public void dismissHints(Collection<ClientContextualHelpHint> hints) {
        repository.dismissHints(hints.stream()
                .filter(Objects::nonNull)
                .map(ClientContextualHelpHint::id)
                .toList());
    }

    public void resetHints() {
        repository.resetHints();
    }
}
