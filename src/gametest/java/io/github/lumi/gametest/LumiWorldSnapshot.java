package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import io.github.lumi.minecraft.world.MinecraftNbtCodec;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.minecraft.world.MinecraftWorldStateReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.StreamSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Exact blocks, block entities, durable entities and player spawns for tests. */
final class LumiWorldSnapshot {
    private static final Comparator<SectionKey> SECTION_ORDER =
            Comparator.comparingInt(SectionKey::chunkX)
                    .thenComparingInt(SectionKey::sectionY)
                    .thenComparingInt(SectionKey::chunkZ);
    private static final Comparator<EntityState> ENTITY_ORDER =
            Comparator.comparing(entity -> entity.id().toString());

    private final Map<SectionKey, SectionBlob> sections;
    private final EntityChunkBlob entities;
    private final Map<UUID, PlayerSpawn> playerSpawns;
    private final List<BlockBox> areas;
    private final String digest;

    private LumiWorldSnapshot(
            Map<SectionKey, SectionBlob> sections,
            EntityChunkBlob entities,
            Map<UUID, PlayerSpawn> playerSpawns,
            List<BlockBox> areas) {
        this.sections = Map.copyOf(sections);
        this.entities = entities;
        this.playerSpawns = Map.copyOf(playerSpawns);
        this.areas = List.copyOf(areas);
        digest = digest();
    }

    static LumiWorldSnapshot capture(
            ServerLevel level, List<BlockBox> areas) throws IOException {
        return capture(level, areas, null, "");
    }

    static LumiWorldSnapshot capture(
            ServerLevel level,
            List<BlockBox> areas,
            LumiBehaviorReport report,
            String name) throws IOException {
        Objects.requireNonNull(level, "level");
        List<BlockBox> copiedAreas = List.copyOf(areas);
        if (copiedAreas.isEmpty()) {
            throw new IllegalArgumentException("Snapshot needs at least one area");
        }
        long started = System.nanoTime();
        var sectionCapture = new MinecraftSectionCapture();
        Map<SectionKey, SectionBlob> sections = new LinkedHashMap<>();
        for (SectionKey key : sectionKeys(copiedAreas)) {
            sections.put(key, sectionCapture.capture(
                    level, level.getChunk(key.chunkX(), key.chunkZ()), key.sectionY()));
        }
        var entityCapture = new MinecraftEntityChunkCapture();
        var capturedEntities = entityCapture.capture(level, StreamSupport.stream(
                        level.getAllEntities().spliterator(), false)
                .filter(entity -> copiedAreas.stream().anyMatch(area -> area.contains(
                        entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()))));
        var sortedEntities = new EntityChunkBlob(capturedEntities.entities().stream()
                .sorted(ENTITY_ORDER).toList());
        Map<UUID, PlayerSpawn> playerSpawns =
                new MinecraftWorldStateReader(level).readPlayerSpawns();
        LumiWorldSnapshot snapshot = new LumiWorldSnapshot(
                sections, sortedEntities, playerSpawns, copiedAreas);
        if (report != null) {
            report.snapshot(name, snapshot.digest, sections.size(),
                    sortedEntities.entities().size(), elapsedMillis(started));
        }
        return snapshot;
    }

    void assertMatches(LumiWorldSnapshot expected, String label) {
        Objects.requireNonNull(expected, "expected");
        if (digest.equals(expected.digest)) {
            return;
        }
        throw new AssertionError(label + " snapshot mismatch: expected "
                + expected.digest + ", got " + digest + "; " + firstDifference(expected));
    }

    String sha256() {
        return digest;
    }

    private String firstDifference(LumiWorldSnapshot expected) {
        if (!sections.keySet().equals(expected.sections.keySet())) {
            return "section keys differ";
        }
        for (SectionKey key : sectionKeys(sections.keySet())) {
            SectionBlob wanted = expected.sections.get(key);
            SectionBlob actual = sections.get(key);
            for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                BlockPos position = absolutePosition(key, index);
                if (!contains(position)) {
                    continue;
                }
                String wantedState = wanted.blockStates().get(index);
                String actualState = actual.blockStates().get(index);
                if (!wantedState.equals(actualState)) {
                    return "block " + position
                            + " expected " + wantedState + " but was " + actualState;
                }
            }
            if (!blockEntities(key, wanted).equals(blockEntities(key, actual))) {
                return describeBlockEntityDifference(key, wanted, actual);
            }
        }
        if (!entities.equals(expected.entities)) {
            return describeEntityDifference(expected.entities, entities);
        }
        if (!playerSpawns.equals(expected.playerSpawns)) {
            return describePlayerSpawnDifference(
                    expected.playerSpawns, playerSpawns);
        }
        return "digest differs despite equal decoded state";
    }

    private String digest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SectionKey key : sectionKeys(sections.keySet())) {
                update(digest, key.chunkX() + "," + key.sectionY() + "," + key.chunkZ());
                SectionBlob section = sections.get(key);
                for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                    if (contains(absolutePosition(key, index))) {
                        update(digest, section.blockStates().get(index));
                    }
                }
                section.blockEntities().entrySet().stream()
                        .filter(entry -> contains(absolutePosition(key, entry.getKey())))
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            update(digest, Integer.toString(entry.getKey()));
                            digest.update(entry.getValue().bytes());
                        });
            }
            for (EntityState entity : entities.entities()) {
                update(digest, entity.id().toString());
                update(digest, entity.type());
                digest.update(entity.nbt().bytes());
            }
            playerSpawns.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        PlayerSpawn spawn = entry.getValue();
                        update(digest, entry.getKey().toString());
                        update(digest, spawn.x() + "," + spawn.y() + "," + spawn.z());
                        update(digest, Float.toString(spawn.yaw()));
                        update(digest, Float.toString(spawn.pitch()));
                        update(digest, Boolean.toString(spawn.forced()));
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Set<SectionKey> sectionKeys(List<BlockBox> areas) {
        Set<SectionKey> keys = new TreeSet<>(SECTION_ORDER);
        areas.forEach(area -> keys.addAll(area.sectionCells(Integer.MAX_VALUE)));
        return keys;
    }

    private static Set<SectionKey> sectionKeys(Set<SectionKey> source) {
        Set<SectionKey> keys = new TreeSet<>(SECTION_ORDER);
        keys.addAll(source);
        return keys;
    }

    private static BlockPos absolutePosition(SectionKey section, int index) {
        return new BlockPos(
                section.chunkX() * 16 + (index & 15),
                section.sectionY() * 16 + ((index >> 8) & 15),
                section.chunkZ() * 16 + ((index >> 4) & 15));
    }

    private Map<Integer, io.github.lumi.domain.model.CanonicalNbt> blockEntities(
            SectionKey key, SectionBlob section) {
        Map<Integer, io.github.lumi.domain.model.CanonicalNbt> selected =
                new LinkedHashMap<>();
        section.blockEntities().forEach((index, nbt) -> {
            if (contains(absolutePosition(key, index))) {
                selected.put(index, nbt);
            }
        });
        return selected;
    }

    private String describeBlockEntityDifference(
            SectionKey key, SectionBlob expected, SectionBlob actual) {
        Map<Integer, io.github.lumi.domain.model.CanonicalNbt> wanted =
                blockEntities(key, expected);
        Map<Integer, io.github.lumi.domain.model.CanonicalNbt> found =
                blockEntities(key, actual);
        Set<Integer> indices = new TreeSet<>(wanted.keySet());
        indices.addAll(found.keySet());
        for (int index : indices) {
            var wantedNbt = wanted.get(index);
            var foundNbt = found.get(index);
            if (Objects.equals(wantedNbt, foundNbt)) {
                continue;
            }
            BlockPos position = absolutePosition(key, index);
            try {
                return "block entity " + position + " expected "
                        + (wantedNbt == null ? "none" : MinecraftNbtCodec.decode(wantedNbt))
                        + " but was "
                        + (foundNbt == null ? "none" : MinecraftNbtCodec.decode(foundNbt));
            } catch (IOException invalid) {
                return "block entity " + position + " differs: " + invalid.getMessage();
            }
        }
        return "block entities differ in section " + key;
    }

    private boolean contains(BlockPos position) {
        return areas.stream().anyMatch(area -> area.contains(
                position.getX(), position.getY(), position.getZ()));
    }

    private static String describe(EntityChunkBlob blob) {
        List<String> descriptions = new ArrayList<>();
        blob.entities().forEach(entity -> descriptions.add(
                entity.type() + "[" + entity.id() + "]"));
        return descriptions.toString();
    }

    private static String describeEntityDifference(
            EntityChunkBlob expected, EntityChunkBlob actual) {
        if (expected.entities().size() == actual.entities().size()) {
            for (int index = 0; index < expected.entities().size(); index++) {
                EntityState wanted = expected.entities().get(index);
                EntityState found = actual.entities().get(index);
                if (wanted.id().equals(found.id())
                        && wanted.type().equals(found.type())
                        && !wanted.nbt().equals(found.nbt())) {
                    try {
                        return "durable entity NBT differs for " + wanted.type()
                                + "[" + wanted.id() + "]: expected "
                                + MinecraftNbtCodec.decode(wanted.nbt()) + " but was "
                                + MinecraftNbtCodec.decode(found.nbt());
                    } catch (IOException invalid) {
                        return "durable entity NBT differs for " + wanted.type()
                                + "[" + wanted.id() + "]: " + invalid.getMessage();
                    }
                }
            }
        }
        return "durable entities differ: expected "
                + describe(expected) + " but was " + describe(actual);
    }

    private static String describePlayerSpawnDifference(
            Map<UUID, PlayerSpawn> expected,
            Map<UUID, PlayerSpawn> actual) {
        Set<UUID> players = new TreeSet<>();
        players.addAll(expected.keySet());
        players.addAll(actual.keySet());
        for (UUID player : players) {
            PlayerSpawn wanted = expected.get(player);
            PlayerSpawn found = actual.get(player);
            if (!Objects.equals(wanted, found)) {
                return "player spawn " + player + " expected "
                        + wanted + " but was " + found;
            }
        }
        return "player spawns differ";
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
