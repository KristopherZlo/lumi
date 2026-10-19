package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.operation.OperationProgress;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Predicate;

/** Keeps the durability boundary global while selecting captured scope keys. */
public final class ScopedSavePreparation implements SavePreparation {
    private final SavePreparation source;
    private final Predicate<HistoryKey> includes;

    public ScopedSavePreparation(SavePreparation source, Predicate<HistoryKey> includes) {
        this.source = Objects.requireNonNull(source, "source");
        this.includes = Objects.requireNonNull(includes, "includes");
    }

    @Override
    public Session begin() {
        return new ScopedSession(source.begin());
    }

    private final class ScopedSession implements Session {
        private final Session sourceSession;
        private WorkingIndexSnapshot boundary;
        private WorkingIndexSnapshot preview;

        private ScopedSession(Session sourceSession) {
            this.sourceSession = Objects.requireNonNull(sourceSession, "sourceSession");
        }

        @Override
        public boolean prepareUntil(long deadlineNanos) throws java.io.IOException {
            return sourceSession.prepareUntil(deadlineNanos);
        }

        @Override
        public WorkingIndexSnapshot finish() {
            if (boundary != null) return boundary;
            boundary = filter(sourceSession.finish());
            preview = filter(sourceSession.previewGenerations());
            return boundary;
        }

        @Override
        public WorkingIndexSnapshot previewGenerations() {
            finish();
            return preview;
        }

        private WorkingIndexSnapshot filter(WorkingIndexSnapshot source) {
            var selected = new LinkedHashMap<HistoryKey, Long>();
            source.generations().forEach((key, generation) -> {
                if (includes.test(key)) {
                    selected.put(key, generation);
                }
            });
            return new WorkingIndexSnapshot(selected);
        }

        @Override
        public OperationProgress progress() {
            return sourceSession.progress();
        }

        @Override
        public void close() throws java.io.IOException {
            sourceSession.close();
        }
    }
}
