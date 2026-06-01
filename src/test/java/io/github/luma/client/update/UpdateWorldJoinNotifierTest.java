package io.github.luma.client.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateWorldJoinNotifierTest {

    @Test
    void startupCheckRequestsUpdateManifest() {
        FakePromptSource source = new FakePromptSource();
        UpdateWorldJoinNotifier notifier = new UpdateWorldJoinNotifier(source);

        notifier.requestStartupCheck();

        assertEquals(1, source.forcedRequestCount);
        assertEquals(0, source.requestCount);
    }

    @Test
    void worldJoinSendsCachedPromptOnlyOncePerVersion() {
        FakePromptSource source = new FakePromptSource();
        source.prompt = Optional.of(release("0.1.0-alpha.2", "https://example.com/lumi.jar"));
        FakeNoticeSink sink = new FakeNoticeSink();
        UpdateWorldJoinNotifier notifier = new UpdateWorldJoinNotifier(source);

        notifier.notifyAfterWorldJoin(sink);
        notifier.notifyAfterWorldJoin(sink);

        assertEquals(2, source.requestCount);
        assertEquals(List.of("0.1.0-alpha.2"), sink.notifiedVersions());
    }

    @Test
    void worldJoinSendsPromptWhenBackgroundCheckCompletes() {
        FakePromptSource source = new FakePromptSource();
        source.requestFuture = new CompletableFuture<>();
        FakeNoticeSink sink = new FakeNoticeSink();
        UpdateWorldJoinNotifier notifier = new UpdateWorldJoinNotifier(source);

        notifier.notifyAfterWorldJoin(sink);

        assertTrue(sink.notices.isEmpty());
        UpdateRelease release = release("0.1.0-alpha.2", "https://example.com/lumi.jar");
        source.prompt = Optional.of(release);
        source.requestFuture.complete(UpdateCheckResult.available(release));

        assertEquals(List.of("0.1.0-alpha.2"), sink.notifiedVersions());
    }

    @Test
    void worldJoinSkipsPromptsWithoutDownloadUrl() {
        FakePromptSource source = new FakePromptSource();
        source.prompt = Optional.of(release("0.1.0-alpha.2", ""));
        FakeNoticeSink sink = new FakeNoticeSink();
        UpdateWorldJoinNotifier notifier = new UpdateWorldJoinNotifier(source);

        notifier.notifyAfterWorldJoin(sink);

        assertTrue(sink.notices.isEmpty());
    }

    private static UpdateRelease release(String version, String downloadUrl) {
        return new UpdateRelease(
                version,
                100002,
                List.of("1.21.11"),
                "fabric",
                "alpha",
                "Lumi " + version,
                "Summary",
                downloadUrl,
                "https://example.com/changelog",
                ""
        );
    }

    private static final class FakePromptSource implements UpdatePromptSource {

        private Optional<UpdateRelease> prompt = Optional.empty();
        private CompletableFuture<UpdateCheckResult> requestFuture =
                CompletableFuture.completedFuture(UpdateCheckResult.noneAvailable());
        private int requestCount;
        private int forcedRequestCount;

        @Override
        public CompletableFuture<UpdateCheckResult> requestCheckIfStale() {
            this.requestCount++;
            return this.requestFuture;
        }

        @Override
        public CompletableFuture<UpdateCheckResult> requestCheckNow() {
            this.forcedRequestCount++;
            return this.requestFuture;
        }

        @Override
        public Optional<UpdateRelease> promptRelease() {
            return this.prompt;
        }
    }

    private static final class FakeNoticeSink implements UpdateWorldJoinNotifier.UpdateNoticeSink {

        private final List<UpdateRelease> notices = new ArrayList<>();

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void sendUpdateNotice(UpdateRelease release) {
            this.notices.add(release);
        }

        private List<String> notifiedVersions() {
            return this.notices.stream()
                    .map(UpdateRelease::version)
                    .toList();
        }
    }
}
