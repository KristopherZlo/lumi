package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
        Objects.requireNonNull(source, "source");
        Map<SectionKey, DecodedSection> decodedSections = new HashMap<>();
        for (var entry : source.sections().entrySet()) {
            decodedSections.put(entry.getKey(), sections.decode(entry.getValue()));
        }
        Map<EntityChunkKey, EntityChunkBlob> normalizedEntities =
                entities.normalize(source.entities());
        Map<EntityChunkKey, DecodedEntityChunk> decodedEntities = new HashMap<>();
        for (var entry : normalizedEntities.entrySet()) {
            decodedEntities.put(entry.getKey(), entities.decodeNormalized(entry.getValue()));
        }
        var normalizedSource = new WorldStateApply.State(
                source.sections(), normalizedEntities, source.playerSpawns());
        return new PreparedMinecraftState(normalizedSource, decodedSections, decodedEntities);
    }
}
