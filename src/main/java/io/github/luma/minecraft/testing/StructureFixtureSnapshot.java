package io.github.luma.minecraft.testing;

import io.github.luma.minecraft.capture.EntitySnapshotService;
import java.util.ArrayList;
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
        List<String> entities
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
        List<String> entities = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, volume.bounds(),
                entity -> !(entity instanceof ServerPlayer))) {
            var payload = entitySnapshotService.capture(level, entity);
            if (payload != null) {
                entities.add(snbt(payload.copyTag()));
            }
        }
        entities.sort(String::compareTo);
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
                return "block mismatch at " + this.format(entry.getKey());
            }
        }
        if (!this.entities.equals(other.entities)) {
            return "entity snapshot mismatch expected=" + this.entities.size()
                    + " actual=" + other.entities.size();
        }
        return "unknown mismatch";
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

    private static String snbt(CompoundTag tag) {
        return tag == null ? "" : NbtUtils.structureToSnbt(tag.copy());
    }
}
