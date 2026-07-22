package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

/** Converts one persistent Restore state into an immutable Minecraft-native state. */
public final class MinecraftRestorePreparation {
    private final MinecraftBlockStateDecoder sections;
    private final MinecraftEntityStateDecoder entities;

    public MinecraftRestorePreparation(
            MinecraftBlockStateDecoder sections,
            MinecraftEntityStateDecoder entities) {
        this.sections = Objects.requireNonNull(sections, "sections");
        this.entities = Objects.requireNonNull(entities, "entities");
    }

    public PreparedMinecraftState prepare(WorldStateApply.State source) throws IOException {
        return prepare(source, ignored -> { });
    }

    public PreparedMinecraftState prepare(
            WorldStateApply.State source,
            LongConsumer progress) throws IOException {
        return prepare(source, null, progress);
    }

    public PreparedMinecraftState prepare(
            WorldStateApply.State source,
            WorldStateApply.State base,
            LongConsumer progress) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(progress, "progress");
        if (base != null && (!source.sections().keySet().equals(base.sections().keySet())
                || !source.entities().keySet().equals(base.entities().keySet()))) {
            throw new IllegalArgumentException("Restore target and base keys must match");
        }
        try {
            long completed = 0;
            Map<SectionKey, DecodedSection> decodedSections = new HashMap<>();
            for (var entry : source.sections().entrySet()) {
                DecodedSection target = sections.decode(entry.getValue());
                if (base != null) {
                    target = target.prepareAgainst(sections.decode(
                            base.sections().get(entry.getKey())));
                }
                decodedSections.put(entry.getKey(), target);
                progress.accept(++completed);
            }
            Map<EntityChunkKey, EntityChunkBlob> normalizedEntities =
                    entities.normalize(source.entities());
            Map<EntityChunkKey, DecodedEntityChunk> decodedEntities = new HashMap<>();
            for (var entry : normalizedEntities.entrySet()) {
                decodedEntities.put(
                        entry.getKey(), entities.decodeNormalized(entry.getValue()));
                progress.accept(++completed);
            }
            var normalizedSource = new WorldStateApply.State(
                    source.sections(), normalizedEntities, source.playerSpawns());
            var sectionOrder = decodedSections.keySet().stream()
                    .sorted(Comparator.comparingInt(SectionKey::chunkX)
                            .thenComparingInt(SectionKey::chunkZ)
                            .thenComparingInt(SectionKey::sectionY))
                    .toList();
            return new PreparedMinecraftState(
                    normalizedSource, decodedSections, decodedEntities,
                    sectionOrder, List.copyOf(decodedEntities.keySet()));
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    public PreparedMinecraftPlanState preflight(
            WorldStateApply.State source,
            WorldStateApply.State base,
            LongConsumer progress) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(progress, "progress");
        if (!source.sections().keySet().equals(base.sections().keySet())
                || !source.entities().keySet().equals(base.entities().keySet())) {
            throw new IllegalArgumentException("Restore target and base keys must match");
        }
        try {
            long completed = 0;
            for (var entry : source.sections().entrySet()) {
                sections.validate(entry.getValue());
                sections.validate(base.sections().get(entry.getKey()));
                progress.accept(++completed);
            }
            Map<EntityChunkKey, EntityChunkBlob> normalizedSource =
                    entities.normalize(source.entities());
            Map<EntityChunkKey, EntityChunkBlob> normalizedBase =
                    entities.normalize(base.entities());
            Map<EntityChunkKey, DecodedEntityChunk> decodedEntities = new HashMap<>();
            Map<EntityChunkKey, DecodedEntityChunk> decodedBaseEntities = new HashMap<>();
            for (var entry : normalizedSource.entrySet()) {
                decodedEntities.put(
                        entry.getKey(), entities.decodeNormalized(entry.getValue()));
                decodedBaseEntities.put(entry.getKey(), entities.decodeNormalized(
                        normalizedBase.get(entry.getKey())));
                progress.accept(++completed);
            }
            var normalizedTarget = new WorldStateApply.State(
                    source.sections(), normalizedSource, source.playerSpawns());
            var normalizedReturn = new WorldStateApply.State(
                    base.sections(), normalizedBase, base.playerSpawns());
            return new PreparedMinecraftPlanState(
                    normalizedTarget, normalizedReturn, decodedEntities,
                    decodedBaseEntities,
                    orderedSections(source.sections().keySet()),
                    List.copyOf(decodedEntities.keySet()));
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    PreparedMinecraftState prepareBatch(
            WorldStateApply.State source,
            WorldStateApply.State base) throws IOException {
        return prepare(source, base, ignored -> { });
    }

    private static List<SectionKey> orderedSections(Set<SectionKey> keys) {
        return keys.stream()
                .sorted(Comparator.comparingInt(SectionKey::chunkX)
                        .thenComparingInt(SectionKey::chunkZ)
                        .thenComparingInt(SectionKey::sectionY))
                .toList();
    }
}
