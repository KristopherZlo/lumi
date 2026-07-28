package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Converts one persistent Restore state into an immutable Minecraft-native state. */
public final class MinecraftRestorePreparation {
    static final int MAX_RECENT_VALIDATIONS = 32;
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
                DecodedSection target = base == null
                        ? sections.decode(entry.getValue())
                        : sections.decodeAgainst(entry.getValue(),
                                base.sections().get(entry.getKey()));
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
                    source.sections(), normalizedEntities, source.playerSpawns(),
                    source.playerSpawnsIncluded());
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
            var validatedTarget = new ValidatedSectionWindow(sections);
            var validatedBase = new ValidatedSectionWindow(sections);
            for (var entry : source.sections().entrySet()) {
                validatedTarget.validate(entry.getValue());
                validatedBase.validate(base.sections().get(entry.getKey()));
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
                    source.sections(), normalizedSource, source.playerSpawns(),
                    source.playerSpawnsIncluded());
            var normalizedReturn = new WorldStateApply.State(
                    base.sections(), normalizedBase, base.playerSpawns(),
                    base.playerSpawnsIncluded());
            return new PreparedMinecraftPlanState(
                    normalizedTarget, normalizedReturn, decodedEntities,
                    decodedBaseEntities,
                    orderedSections(source.sections().keySet()),
                    List.copyOf(decodedEntities.keySet()));
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    PreparedMinecraftState preparePreflightedBatch(
            WorldStateApply.State source,
            WorldStateApply.State base,
            List<SectionKey> order,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(base, "base");
        order = List.copyOf(Objects.requireNonNull(order, "order"));
        Objects.requireNonNull(cancelled, "cancelled");
        if (!source.sections().keySet().equals(base.sections().keySet())
                || !source.entities().isEmpty() || !base.entities().isEmpty()
                || !source.playerSpawns().isEmpty() || !base.playerSpawns().isEmpty()
                || source.playerSpawnsIncluded() || base.playerSpawnsIncluded()) {
            throw new IllegalArgumentException(
                    "Preflighted section batch keys must match and contain only sections");
        }
        if (order.size() != source.sections().size()
                || !Set.copyOf(order).equals(source.sections().keySet())) {
            throw new IllegalArgumentException(
                    "Preflighted section order must contain every key once");
        }
        Map<SectionBlob, DecodedSection> templates = new IdentityHashMap<>();
        Map<SectionKey, DecodedSection> decoded = new LinkedHashMap<>();
        for (SectionKey key : order) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Restore preparation cancelled");
            }
            SectionBlob sourceSection = source.sections().get(key);
            DecodedSection template = templates.get(sourceSection);
            if (template == null) {
                template = sections.decode(sourceSection);
                templates.put(sourceSection, template);
            }
            decoded.put(key, template.prepareAgainst(
                    sourceSection, base.sections().get(key), sections));
        }
        return new PreparedMinecraftState(
                source, decoded, Map.of(), order, List.of());
    }

    private static List<SectionKey> orderedSections(Set<SectionKey> keys) {
        return keys.stream()
                .sorted(Comparator.comparingInt(SectionKey::chunkX)
                        .thenComparingInt(SectionKey::chunkZ)
                        .thenComparingInt(SectionKey::sectionY))
                .toList();
    }

    static final class ValidatedSectionWindow {
        private final MinecraftBlockStateDecoder decoder;
        private final ArrayDeque<SectionBlob> recent =
                new ArrayDeque<>(MAX_RECENT_VALIDATIONS);

        ValidatedSectionWindow(MinecraftBlockStateDecoder decoder) {
            this.decoder = Objects.requireNonNull(decoder, "decoder");
        }

        boolean validate(SectionBlob section) throws IOException {
            Objects.requireNonNull(section, "section");
            for (Iterator<SectionBlob> iterator = recent.iterator();
                    iterator.hasNext();) {
                if (iterator.next() == section) {
                    iterator.remove();
                    recent.addLast(section);
                    return false;
                }
            }
            decoder.validate(section);
            if (recent.size() == MAX_RECENT_VALIDATIONS) {
                recent.removeFirst();
            }
            recent.addLast(section);
            return true;
        }

        int size() {
            return recent.size();
        }

        boolean tracks(SectionBlob section) {
            return recent.stream().anyMatch(candidate -> candidate == section);
        }
    }
}
