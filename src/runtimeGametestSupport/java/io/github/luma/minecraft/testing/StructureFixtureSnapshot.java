package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.minecraft.capture.EntitySnapshotService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

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
        return this.matches(other, ComparisonPolicy.exact());
    }

    boolean matches(StructureFixtureSnapshot other, ComparisonPolicy comparisonPolicy) {
        return other != null
                && this.blocksMatch(other, comparisonPolicy)
                && this.entities.equals(other.entities);
    }

    String diff(StructureFixtureSnapshot other) {
        return this.diff(other, ComparisonPolicy.exact());
    }

    String diff(StructureFixtureSnapshot other, ComparisonPolicy comparisonPolicy) {
        if (other == null) {
            return "missing restored snapshot";
        }
        BlockMismatch mismatch = this.firstBlockMismatch(other, comparisonPolicy);
        if (mismatch != null) {
            return "block mismatch at " + this.format(mismatch.pos())
                    + " expectedState=" + truncated(mismatch.expectedStateSnbt())
                    + " actualState=" + truncated(mismatch.actualStateSnbt())
                    + this.blockEntityDiff(mismatch);
        }
        if (!this.entities.equals(other.entities)) {
            return "entity snapshot mismatch expected=" + this.entities.size()
                    + " actual=" + other.entities.size()
                    + this.entityDiff(other);
        }
        return "unknown mismatch";
    }

    BlockMismatch firstBlockMismatch(StructureFixtureSnapshot other) {
        return this.firstBlockMismatch(other, ComparisonPolicy.exact());
    }

    BlockMismatch firstBlockMismatch(StructureFixtureSnapshot other, ComparisonPolicy comparisonPolicy) {
        if (other == null) {
            return null;
        }
        ComparisonPolicy effectivePolicy = comparisonPolicy == null
                ? ComparisonPolicy.exact()
                : comparisonPolicy;
        for (Map.Entry<BlockPos, BlockSnapshot> entry : this.blocks.entrySet()) {
            BlockSnapshot restored = other.blocks.get(entry.getKey());
            if (!effectivePolicy.equivalent(entry.getKey(), entry.getValue(), restored)) {
                return BlockMismatch.of(entry.getKey(), entry.getValue(), restored);
            }
        }
        return null;
    }

    static ComparisonPolicy exactComparison() {
        return ComparisonPolicy.exact();
    }

    static ComparisonPolicy ignoringObserverPoweredAt(Collection<BlockPos> positions) {
        return ComparisonPolicy.ignoringObserverPoweredAt(positions);
    }

    static ComparisonPolicy ignoringRedstoneTorchLitAt(Collection<BlockPos> positions) {
        return ComparisonPolicy.ignoringRedstoneTorchLitAt(positions);
    }

    static ComparisonPolicy ignoringRedstoneLampLitAt(Collection<BlockPos> positions) {
        return ComparisonPolicy.ignoringRedstoneLampLitAt(positions);
    }

    private boolean blocksMatch(StructureFixtureSnapshot other, ComparisonPolicy comparisonPolicy) {
        ComparisonPolicy effectivePolicy = comparisonPolicy == null
                ? ComparisonPolicy.exact()
                : comparisonPolicy;
        if (!this.blocks.keySet().equals(other.blocks.keySet())) {
            return false;
        }
        for (Map.Entry<BlockPos, BlockSnapshot> entry : this.blocks.entrySet()) {
            if (!effectivePolicy.equivalent(entry.getKey(), entry.getValue(), other.blocks.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
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

    private String blockEntityDiff(BlockMismatch mismatch) {
        if (mismatch.expectedBlockEntitySnbt().equals(mismatch.actualBlockEntitySnbt())) {
            return "";
        }
        return " expectedBlockEntity=" + truncated(mismatch.expectedBlockEntitySnbt())
                + " actualBlockEntity=" + truncated(mismatch.actualBlockEntitySnbt());
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    static final class ComparisonPolicy {

        private static final ComparisonPolicy EXACT = new ComparisonPolicy(Set.of());
        private final Set<IgnoredBlockProperty> ignoredPhaseProperties;

        private ComparisonPolicy(Collection<IgnoredBlockProperty> ignoredPhaseProperties) {
            LinkedHashSet<IgnoredBlockProperty> copiedProperties = new LinkedHashSet<>();
            if (ignoredPhaseProperties != null) {
                for (IgnoredBlockProperty property : ignoredPhaseProperties) {
                    if (property != null) {
                        copiedProperties.add(property);
                    }
                }
            }
            this.ignoredPhaseProperties = Set.copyOf(copiedProperties);
        }

        static ComparisonPolicy exact() {
            return EXACT;
        }

        static ComparisonPolicy ignoringObserverPoweredAt(Collection<BlockPos> positions) {
            return EXACT.withObserverPoweredAt(positions);
        }

        static ComparisonPolicy ignoringRedstoneTorchLitAt(Collection<BlockPos> positions) {
            return EXACT.withRedstoneTorchLitAt(positions);
        }

        static ComparisonPolicy ignoringRedstoneLampLitAt(Collection<BlockPos> positions) {
            return EXACT.withRedstoneLampLitAt(positions);
        }

        ComparisonPolicy withObserverPoweredAt(Collection<BlockPos> positions) {
            return this.withBlockPropertyAt(positions, Blocks.OBSERVER, "powered");
        }

        ComparisonPolicy withRedstoneTorchLitAt(Collection<BlockPos> positions) {
            return this.withBlockPropertyAt(positions, Blocks.REDSTONE_TORCH, "lit");
        }

        ComparisonPolicy withRedstoneLampLitAt(Collection<BlockPos> positions) {
            return this.withBlockPropertyAt(positions, Blocks.REDSTONE_LAMP, "lit");
        }

        private ComparisonPolicy withBlockPropertyAt(
                Collection<BlockPos> positions,
                Block block,
                String propertyName
        ) {
            if (positions == null || positions.isEmpty()) {
                return this;
            }

            LinkedHashSet<IgnoredBlockProperty> combined = new LinkedHashSet<>(this.ignoredPhaseProperties);
            for (BlockPos pos : positions) {
                if (pos != null) {
                    combined.add(new IgnoredBlockProperty(pos, block, propertyName));
                }
            }
            if (combined.equals(this.ignoredPhaseProperties)) {
                return this;
            }
            return new ComparisonPolicy(combined);
        }

        private boolean equivalent(BlockPos pos, BlockSnapshot expected, BlockSnapshot actual) {
            if (Objects.equals(expected, actual)) {
                return true;
            }
            if (expected == null || actual == null || !expected.blockEntitySnbt().equals(actual.blockEntitySnbt())) {
                return false;
            }
            for (IgnoredBlockProperty property : this.ignoredPhaseProperties) {
                if (property.matches(pos, expected, actual)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record IgnoredBlockProperty(BlockPos pos, Block block, String propertyName) {

        IgnoredBlockProperty {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            block = Objects.requireNonNull(block, "block");
            propertyName = Objects.requireNonNull(propertyName, "propertyName");
        }

        private boolean matches(BlockPos candidate, BlockSnapshot expected, BlockSnapshot actual) {
            return this.pos.equals(candidate)
                    && expected.differsOnlyByBlockProperty(actual, this.block, this.propertyName);
        }
    }

    private record BlockSnapshot(BlockState state, String stateSnbt, String blockEntitySnbt) {

        static BlockSnapshot capture(ServerLevel level, BlockState state, BlockEntity blockEntity) {
            CompoundTag blockEntityTag = blockEntity == null
                    ? null
                    : blockEntity.saveWithFullMetadata(level.registryAccess());
            return new BlockSnapshot(state, snbt(NbtUtils.writeBlockState(state)), snbt(blockEntityTag));
        }

        private boolean differsOnlyByBlockProperty(
                BlockSnapshot other,
                Block block,
                String ignoredPropertyName
        ) {
            if (this.state == null || other == null || other.state == null
                    || !this.state.is(block) || !other.state.is(block)
                    || this.state.getBlock() != other.state.getBlock()) {
                return false;
            }
            return this.differsOnlyByProperty(other.state, ignoredPropertyName);
        }

        private boolean differsOnlyByProperty(BlockState otherState, String ignoredPropertyName) {
            boolean ignoredPropertyDiffered = false;
            for (Property<?> property : this.state.getProperties()) {
                if (property.getName().equals(ignoredPropertyName)) {
                    ignoredPropertyDiffered = !samePropertyValue(this.state, otherState, property);
                    continue;
                }
                if (!samePropertyValue(this.state, otherState, property)) {
                    return false;
                }
            }
            return ignoredPropertyDiffered;
        }
    }

    record BlockMismatch(
            BlockPos pos,
            String expectedStateSnbt,
            String actualStateSnbt,
            String expectedBlockEntitySnbt,
            String actualBlockEntitySnbt
    ) {

        private static BlockMismatch of(BlockPos pos, BlockSnapshot expected, BlockSnapshot actual) {
            return new BlockMismatch(
                    pos.immutable(),
                    expected == null ? "" : expected.stateSnbt(),
                    actual == null ? "" : actual.stateSnbt(),
                    expected == null ? "" : expected.blockEntitySnbt(),
                    actual == null ? "" : actual.blockEntitySnbt()
            );
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

    private static <T extends Comparable<T>> boolean samePropertyValue(
            BlockState expected,
            BlockState actual,
            Property<T> property
    ) {
        return actual.hasProperty(property)
                && Objects.equals(expected.getValue(property), actual.getValue(property));
    }
}
