package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Publishes an applied partial Restore as ordinary pending world work. */
public final class PendingRestorePublication implements RestorePublication {
    private final MutationDurabilityTracker mutations;
    private final RestorePublication context;
    private MutationDurabilityTracker.DurabilityBoundary boundary;

    public PendingRestorePublication(MutationDurabilityTracker mutations) {
        this(mutations, ignored -> { });
    }

    public PendingRestorePublication(MutationDurabilityTracker mutations, RestorePublication context) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void persist(PreparedRestore restore) throws IOException { context.persist(restore); }

    @Override public void persistReturn(PreparedRestore restore) throws IOException { context.persistReturn(restore); }
    @Override public void installed() throws IOException { context.installed(); }
    @Override public void finishReturnOnServer(PreparedRestore restore) throws IOException { context.installed(); }
    @Override public io.github.lumi.domain.model.OperationTarget historyContext(io.github.lumi.domain.model.OperationTarget target) { return context.historyContext(target); }

    @Override
    public void finishOnServer(PreparedRestore restore) throws IOException { publish(restore); }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        publishOrigins(restore.returnSections(), restore.returnEntities(), false);
    }

    public void publishSections(Map<SectionKey, SectionBlob> before) throws IOException {
        publishOrigins(before, Map.of(), true);
    }

    private void publishOrigins(Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities, boolean builder) throws IOException {
        if (boundary != null) {
            throw new IllegalStateException("Pending Restore was already published");
        }
        Map<HistoryKey, Long> generations = new HashMap<>();
        try {
            for (var key : sections.keySet()) {
                var before = sections.get(key);
                generations.put(key,
                        mutations.registerSectionMutation(key, () -> before));
            }
            for (var key : entities.keySet()) {
                var before = entities.get(key);
                generations.put(key,
                        mutations.registerEntityMutation(key, () -> before));
            }
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        if (builder) sections.keySet().forEach(mutations::markBuilderMutation);
        boundary = mutations.durabilityBoundary(new WorkingIndexSnapshot(generations));
    }

    @Override
    public boolean isDurable() {
        return boundary != null && mutations.isDurable(boundary);
    }

    @Override
    public boolean awaitDurable(long deadlineNanos) throws IOException {
        return boundary != null && mutations.awaitDurable(boundary, deadlineNanos);
    }
}
