package io.github.luma.client.update;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckServiceTest {

    @Test
    void checkNowStoresPromptableRelease() {
        InMemoryStateRepository repository = new InMemoryStateRepository();
        UpdateCheckService service = new UpdateCheckService(
                () -> new SourcedUpdateManifest("site", manifest("0.1.0-alpha.2")),
                new UpdateCandidateSelector(),
                repository,
                () -> new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric"),
                Clock.fixed(Instant.parse("2026-05-16T10:00:00Z"), ZoneOffset.UTC)
        );

        UpdateCheckResult result = service.checkNow();
        Optional<UpdateRelease> prompt = service.promptRelease();

        assertTrue(result.available());
        assertTrue(prompt.isPresent());
        assertEquals("0.1.0-alpha.2", prompt.get().version());
        assertEquals(Instant.parse("2026-05-16T10:00:00Z"), repository.state.lastCheckedAt());
    }

    @Test
    void dismissVersionHidesCachedPrompt() {
        InMemoryStateRepository repository = new InMemoryStateRepository();
        repository.state = UpdateCheckState.empty().withChecked(
                Instant.parse("2026-05-16T10:00:00Z"),
                UpdateCheckResult.available(release("0.1.0-alpha.2"))
        );
        UpdateCheckService service = new UpdateCheckService(
                () -> new SourcedUpdateManifest("site", manifest("0.1.0-alpha.2")),
                new UpdateCandidateSelector(),
                repository,
                () -> new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric"),
                Clock.fixed(Instant.parse("2026-05-16T10:00:00Z"), ZoneOffset.UTC)
        );

        service.dismissVersion("0.1.0-alpha.2");

        assertTrue(service.promptRelease().isEmpty());
        assertEquals("0.1.0-alpha.2", repository.state.dismissedVersion());
    }

    @Test
    void snoozeVersionHidesPromptForCurrentSessionOnly() {
        InMemoryStateRepository repository = new InMemoryStateRepository();
        repository.state = UpdateCheckState.empty().withChecked(
                Instant.parse("2026-05-16T10:00:00Z"),
                UpdateCheckResult.available(release("0.1.0-alpha.2"))
        );
        UpdateCheckService service = new UpdateCheckService(
                () -> new SourcedUpdateManifest("site", manifest("0.1.0-alpha.2")),
                new UpdateCandidateSelector(),
                repository,
                () -> new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric"),
                Clock.fixed(Instant.parse("2026-05-16T10:00:00Z"), ZoneOffset.UTC)
        );

        service.snoozeVersion("0.1.0-alpha.2");

        assertTrue(service.promptRelease().isEmpty());
        assertEquals("", repository.state.dismissedVersion());
    }

    @Test
    void requestCheckNowIgnoresFreshCache() {
        InMemoryStateRepository repository = new InMemoryStateRepository();
        repository.state = UpdateCheckState.empty().withChecked(
                Instant.parse("2026-05-16T10:00:00Z"),
                UpdateCheckResult.noneAvailable()
        );
        UpdateCheckService service = new UpdateCheckService(
                () -> new SourcedUpdateManifest("github", manifest("0.1.0-alpha.2")),
                new UpdateCandidateSelector(),
                repository,
                () -> new InstalledModInfo("0.1.0-alpha.1", "1.21.11", "fabric"),
                Clock.fixed(Instant.parse("2026-05-16T10:30:00Z"), ZoneOffset.UTC)
        );

        service.requestCheckNow().join();

        assertTrue(service.promptRelease().isPresent());
        assertEquals("0.1.0-alpha.2", service.promptRelease().orElseThrow().version());
        assertEquals(Instant.parse("2026-05-16T10:30:00Z"), repository.state.lastCheckedAt());
    }

    private static UpdateManifest manifest(String version) {
        return new UpdateManifest(1, "lumi", List.of(release(version)));
    }

    private static UpdateRelease release(String version) {
        return new UpdateRelease(
                version,
                100002,
                List.of("1.21.11"),
                "fabric",
                "stable",
                "Lumi " + version,
                "Summary",
                "https://example.com/download",
                "https://example.com/changelog",
                ""
        );
    }

    private static final class InMemoryStateRepository implements UpdateStateRepository {

        private UpdateCheckState state = UpdateCheckState.empty();

        @Override
        public UpdateCheckState load() {
            return this.state;
        }

        @Override
        public void save(UpdateCheckState state) {
            this.state = state;
        }
    }
}
