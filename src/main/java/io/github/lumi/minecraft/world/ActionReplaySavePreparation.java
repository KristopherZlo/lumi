package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.*;
import io.github.lumi.minecraft.operation.OperationProgress;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Captures full action containers once, expanding only entity identity dependencies. */
public final class ActionReplaySavePreparation implements SavePreparation, WorldStateReader {
    @FunctionalInterface
    public interface EntityScope {
        Map<EntityChunkKey, Set<UUID>> expand(Set<EntityChunkKey> chunks, Set<UUID> identities) throws IOException;
    }

    private final WorldStateReader reader;
    private final MutationDurabilityTracker mutations;
    private final EntityChunkDurabilityGate entities;
    private final ChunkLoadSession chunks;
    private final EntityScope scope;
    private final Function<UUID, Optional<EntityChunkKey>> locate;
    private final Executor background;
    private final Set<HistoryKey> keys;
    private final Set<UUID> identities;
    private final Map<SectionKey, SectionBlob> sections = new HashMap<>();
    private final Map<EntityChunkKey, EntityChunkBlob> capturedEntities = new HashMap<>();

    public ActionReplaySavePreparation(WorldStateReader reader, MutationDurabilityTracker mutations,
            EntityChunkDurabilityGate entities, ChunkLoadSession chunks, Set<HistoryKey> keys, Set<UUID> identities,
            EntityScope scope, Function<UUID, Optional<EntityChunkKey>> locate, Executor background) {
        this.reader = reader;
        this.mutations = mutations;
        this.entities = entities;
        this.chunks = chunks;
        this.keys = new HashSet<>(keys);
        this.identities = new HashSet<>(identities);
        this.scope = scope;
        this.locate = locate;
        this.background = background;
    }

    @Override public SectionBlob read(SectionKey key) { return Objects.requireNonNull(sections.get(key)); }
    @Override public EntityChunkBlob read(EntityChunkKey key) { return Objects.requireNonNull(capturedEntities.get(key)); }
    @Override public Map<UUID, PlayerSpawn> readPlayerSpawns() { return reader.readPlayerSpawns(); }

    @Override public Session begin() {
        return new Session() {
            private final Deque<HistoryKey> pending = new ArrayDeque<>();
            private CompletableFuture<Map<EntityChunkKey, Set<UUID>>> expansion;
            private final MutationDurabilityTracker.DurabilityBoundary initial = mutations.durabilityBoundary(Set.of());
            private MutationDurabilityTracker.DurabilityBoundary boundary;
            private boolean complete;

            @Override public boolean prepareUntil(long deadline) throws IOException {
                // Include all origins already queued before identity metadata is inspected.
                if (!mutations.awaitDurable(initial, deadline)) return false;
                if (boundary == null) {
                    if (expansion != null) {
                        if (!expansion.isDone()) return false;
                        Map<EntityChunkKey, Set<UUID>> expanded;
                        try { expanded = expansion.join(); }
                        catch (CompletionException failed) { throw new IOException("Cannot discover action entity scope", failed.getCause()); }
                        expanded.forEach((key, ids) -> { keys.add(key); identities.addAll(ids); });
                        identities.forEach(id -> locate.apply(id).ifPresent(keys::add));
                        expansion = null;
                        if (keys.size() == sections.size() + capturedEntities.size()) {
                            boundary = mutations.durabilityBoundary(keys);
                        }
                    }
                    if (boundary == null) {
                        identities.forEach(id -> locate.apply(id).ifPresent(keys::add));
                        if (pending.isEmpty()) for (var key : keys)
                            if (!sections.containsKey(key) && !capturedEntities.containsKey(key)) pending.add(key);
                        while (!pending.isEmpty() && System.nanoTime() < deadline) {
                            var key = pending.peek();
                            var chunk = ChunkCoordinate.from(key);
                            if (!chunks.loadOneUntil(chunk, deadline)) return false;
                            if (key instanceof SectionKey section) {
                                var value = reader.read(section);
                                sections.put(section, value);
                                mutations.registerSectionMutation(section, () -> value);
                            } else {
                                var entityChunk = (EntityChunkKey) key;
                                var value = reader.read(entityChunk);
                                capturedEntities.put(entityChunk, value);
                                value.entities().forEach(entity -> identities.add(entity.id()));
                                entities.observeCurrent(entityChunk, value);
                                mutations.registerEntityMutation(entityChunk, () -> value);
                            }
                            chunks.release(chunk);
                            pending.remove();
                        }
                        if (!pending.isEmpty()) return false;
                        if (identities.isEmpty()) boundary = mutations.durabilityBoundary(keys);
                        else {
                            var entityKeys = new HashSet<>(capturedEntities.keySet());
                            var selected = Set.copyOf(identities);
                            expansion = CompletableFuture.supplyAsync(() -> {
                                try { return scope.expand(entityKeys, selected); }
                                catch (IOException failed) { throw new CompletionException(failed); }
                            }, background);
                            return false;
                        }
                    }
                }
                complete = mutations.awaitDurable(boundary, deadline);
                return complete;
            }

            @Override public WorkingIndexSnapshot finish() {
                if (!complete) throw new IllegalStateException("Action capture is not durable");
                return boundary.working();
            }
            @Override public WorkingIndexSnapshot previewGenerations() {
                finish();
                return boundary.builder();
            }
            @Override public OperationProgress progress() {
                return OperationProgress.indeterminate(boundary == null
                        ? "Undo/Redo: capturing action footprint" : "Undo/Redo: publishing shared index");
            }
            @Override public void close() {
                if (expansion != null) expansion.cancel(true);
                chunks.close();
                sections.clear();
                capturedEntities.clear();
            }
        };
    }
}
