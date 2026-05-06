package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.minecraft.capture.EntitySnapshotService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exact block, block-entity, and non-player entity snapshot for a structure fixture volume.
 */
record StructureFixtureSnapshot(
        Map<BlockPos, BlockSnapshot> blocks,
        List<EntitySnapshot> entities
) {

    static StructureFixtureSnapshot capture(ServerLevel level, SingleplayerTestVolume volume) {
        LinkedHashMap<BlockPos, BlockSnapshot> blocks = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(volume.min(), volume.max())) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            BlockEntity blockEntity = level.getBlockEntity(immutable);
            blocks.put(immutable, BlockSnapshot.capture(level, state, blockEntity));
        }

        EntitySnapshotService entitySnapshotService = new EntitySnapshotService();
        List<EntitySnapshot> entities = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, volume.bounds(),
                entity -> !(entity instanceof ServerPlayer))) {
            var payload = entitySnapshotService.capture(level, entity);
            if (payload != null) {
                CompoundTag tag = payload.copyTag();
                entities.add(new EntitySnapshot(snbt(tag), entitySummary(tag)));
            }
        }
        entities.sort(Comparator.comparing(EntitySnapshot::snbt));
        return new StructureFixtureSnapshot(Map.copyOf(blocks), List.copyOf(entities));
    }

    boolean matches(StructureFixtureSnapshot other) {
        return other != null
                && this.blocks.equals(other.blocks)
                && this.entities.equals(other.entities);
    }

    String diff(StructureFixtureSnapshot other) {
        if (other == null) {
            return "missing restored snapshot";
        }
        for (Map.Entry<BlockPos, BlockSnapshot> entry : this.blocks.entrySet()) {
            BlockSnapshot restored = other.blocks.get(entry.getKey());
            if (!entry.getValue().equals(restored)) {
                return "block mismatch at " + this.format(entry.getKey())
                        + " expectedState=" + truncated(entry.getValue().stateSnbt())
                        + " actualState=" + truncated(restored == null ? "" : restored.stateSnbt())
                        + this.blockEntityDiff(entry.getValue(), restored);
            }
        }
        if (!this.entities.equals(other.entities)) {
            return "entity snapshot mismatch expected=" + this.entities.size()
                    + " actual=" + other.entities.size()
                    + this.entityDiff(other);
        }
        return "unknown mismatch";
    }

    private String entityDiff(StructureFixtureSnapshot other) {
        Map<String, Integer> actualCounts = new LinkedHashMap<>();
        for (EntitySnapshot entity : other.entities) {
            actualCounts.merge(entity.snbt(), 1, Integer::sum);
        }

        List<String> missing = new ArrayList<>();
        for (EntitySnapshot entity : this.entities) {
            int count = actualCounts.getOrDefault(entity.snbt(), 0);
            if (count <= 0) {
                missing.add(entity.summary());
                continue;
            }
            actualCounts.put(entity.snbt(), count - 1);
        }

        List<String> extra = new ArrayList<>();
        for (EntitySnapshot entity : other.entities) {
            int count = actualCounts.getOrDefault(entity.snbt(), 0);
            if (count > 0) {
                extra.add(entity.summary());
                actualCounts.put(entity.snbt(), count - 1);
            }
        }

        return " missing=" + firstSummaries(missing)
                + " extra=" + firstSummaries(extra);
    }

    private String blockEntityDiff(BlockSnapshot expected, BlockSnapshot actual) {
        String actualSnbt = actual == null ? "" : actual.blockEntitySnbt();
        if (expected.blockEntitySnbt().equals(actualSnbt)) {
            return "";
        }
        return " expectedBlockEntity=" + truncated(expected.blockEntitySnbt())
                + " actualBlockEntity=" + truncated(actualSnbt);
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record BlockSnapshot(String stateSnbt, String blockEntitySnbt) {

        static BlockSnapshot capture(ServerLevel level, BlockState state, BlockEntity blockEntity) {
            CompoundTag blockEntityTag = blockEntity == null
                    ? null
                    : blockEntity.saveWithFullMetadata(level.registryAccess());
            return new BlockSnapshot(snbt(NbtUtils.writeBlockState(state)), snbt(blockEntityTag));
        }
    }

    private record EntitySnapshot(String snbt, String summary) {
    }

    private static String snbt(CompoundTag tag) {
        return tag == null ? "" : NbtUtils.structureToSnbt(tag.copy());
    }

    private static String entitySummary(CompoundTag tag) {
        if (tag == null) {
            return "unknown";
        }
        String type = tag.getString("id").orElse("unknown");
        String uuid = EntityPayload.readUuid(tag)
                .map(java.util.UUID::toString)
                .orElse("no-uuid");
        var pos = tag.getListOrEmpty("Pos");
        if (pos.size() < 3) {
            return type + "@" + uuid;
        }
        return type + "@" + uuid
                + "[" + rounded(pos.getDoubleOr(0, 0.0D))
                + "," + rounded(pos.getDoubleOr(1, 0.0D))
                + "," + rounded(pos.getDoubleOr(2, 0.0D)) + "]";
    }

    private static String firstSummaries(List<String> summaries) {
        if (summaries.isEmpty()) {
            return "[]";
        }
        int limit = Math.min(3, summaries.size());
        return summaries.subList(0, limit) + (summaries.size() > limit ? "..." : "");
    }

    private static String rounded(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String truncated(String value) {
        if (value == null || value.length() <= 180) {
            return value == null ? "" : value;
        }
        return value.substring(0, 180) + "...";
    }
}
