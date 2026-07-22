package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(progress, "progress");
        long completed = 0;
        Map<SectionKey, DecodedSection> decodedSections = new HashMap<>();
        for (var entry : source.sections().entrySet()) {
            decodedSections.put(entry.getKey(), sections.decode(entry.getValue()));
            progress.accept(++completed);
        }
        Map<EntityChunkKey, EntityChunkBlob> normalizedEntities =
                entities.normalize(source.entities());
        Map<EntityChunkKey, DecodedEntityChunk> decodedEntities = new HashMap<>();
        for (var entry : normalizedEntities.entrySet()) {
            decodedEntities.put(entry.getKey(), entities.decodeNormalized(entry.getValue()));
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
    }
}
