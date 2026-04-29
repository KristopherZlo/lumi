package io.github.luma.client.onboarding;

public record ClientOnboardingState(
        int schemaVersion,
        int completedOnboardingVersion
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static ClientOnboardingState empty() {
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, 0);
    }

    public ClientOnboardingState normalized() {
        int schema = this.schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : this.schemaVersion;
        int completed = Math.max(0, this.completedOnboardingVersion);
        return new ClientOnboardingState(schema, completed);
    }

    public ClientOnboardingState withCompletedVersion(int version) {
        return new ClientOnboardingState(CURRENT_SCHEMA_VERSION, Math.max(0, version));
    }
}
