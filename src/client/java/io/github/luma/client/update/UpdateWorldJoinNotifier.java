package io.github.luma.client.update;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class UpdateWorldJoinNotifier {

    private final UpdatePromptSource updatePromptSource;
    private final Set<String> notifiedVersions = ConcurrentHashMap.newKeySet();

    public UpdateWorldJoinNotifier() {
        this(UpdateCheckService.getInstance());
    }

    UpdateWorldJoinNotifier(UpdatePromptSource updatePromptSource) {
        this.updatePromptSource = updatePromptSource;
    }

    public void requestStartupCheck() {
        if (this.updatePromptSource != null) {
            this.updatePromptSource.requestCheckIfStale();
        }
    }

    public void notifyAfterWorldJoin(UpdateNoticeSink sink) {
        if (sink == null || !sink.isReady() || this.updatePromptSource == null) {
            return;
        }
        this.sendPromptIfAvailable(sink);
        this.updatePromptSource.requestCheckIfStale().thenRun(() ->
                sink.execute(() -> this.sendPromptIfAvailable(sink)));
    }

    private void sendPromptIfAvailable(UpdateNoticeSink sink) {
        this.updatePromptSource.promptRelease()
                .filter(release -> !release.downloadUrl().isBlank())
                .filter(release -> this.notifiedVersions.add(release.version()))
                .ifPresent(sink::sendUpdateNotice);
    }

    public interface UpdateNoticeSink {

        boolean isReady();

        void execute(Runnable task);

        void sendUpdateNotice(UpdateRelease release);
    }
}
