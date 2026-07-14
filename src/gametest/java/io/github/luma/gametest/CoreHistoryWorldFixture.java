package io.github.luma.gametest;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Owns the deterministic live-world states used by the core history journey. */
final class CoreHistoryWorldFixture {

    private static final String AUTHOR = "Lumi core journey";

    private final String projectName;
    private final String projectId;
    private final BlockPos block;
    private final BlockPos barrel;
    private final BlockPos stairs;
    private final BlockPos entity;

    private CoreHistoryWorldFixture(String projectName, String projectId, BlockPos origin) {
        this.projectName = projectName;
        this.projectId = projectId;
        this.block = origin;
        this.barrel = origin.east();
        this.stairs = origin.south();
        this.entity = origin.offset(2, 0, 2);
    }

    static CoreHistoryWorldFixture create(ServerLevel level, BlockPos origin) throws IOException {
        var project = new ProjectService().ensureWorldProject(level, AUTHOR);
        return new CoreHistoryWorldFixture(project.name(), project.id().toString(), origin);
    }

    UUID applyStateA(ServerLevel level) {
        Entity spawned = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (spawned == null) {
            throw new AssertionError("Could not create core journey entity");
        }
        try (WorldMutationContext.SourceFrame ignored = this.playerAction()) {
            level.setBlock(this.block, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(this.barrel, Blocks.BARREL.defaultBlockState(), 3);
            this.setBarrelItem(level, Items.DIAMOND, 3);
            level.setBlock(this.stairs, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.EAST), 3);
            spawned.snapTo(this.entity.getX() + 0.5D, this.entity.getY(), this.entity.getZ() + 0.5D,
                    0.0F, 0.0F);
            level.addFreshEntity(spawned);
            spawned.setCustomName(Component.literal("core-state-a"));
        }
        return spawned.getUUID();
    }

    void applyStateB(ServerLevel level, UUID entityId) {
        try (WorldMutationContext.SourceFrame ignored = this.playerAction()) {
            level.setBlock(this.block, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            this.setBarrelItem(level, Items.EMERALD, 5);
            level.setBlock(this.stairs, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.WEST), 3);
            Entity changed = this.requireEntity(level, entityId);
            BlockPos moved = this.entity.east();
            changed.snapTo(moved.getX() + 0.5D, moved.getY(), moved.getZ() + 0.5D, 90.0F, 0.0F);
            changed.setCustomName(Component.literal("core-state-b"));
            changed.setGlowingTag(true);
        }
    }

    void applyStateC(ServerLevel level) {
        try (WorldMutationContext.SourceFrame ignored = this.playerAction()) {
            level.setBlock(this.block, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        }
    }

    void assertStateA(ServerLevel level, UUID entityId) {
        this.assertBlock(level, Blocks.STONE, "state A block");
        this.assertStairs(level, Direction.EAST, "state A stairs");
        this.assertBarrel(level, Items.DIAMOND, 3, "state A barrel");
        this.assertEntity(level, entityId, this.entity, "core-state-a", false, "state A entity");
    }

    void assertStateB(ServerLevel level, UUID entityId) {
        this.assertBlock(level, Blocks.GOLD_BLOCK, "state B block");
        this.assertStateBDetails(level, entityId);
    }

    private void assertStateBDetails(ServerLevel level, UUID entityId) {
        this.assertStairs(level, Direction.WEST, "state B stairs");
        this.assertBarrel(level, Items.EMERALD, 5, "state B barrel");
        this.assertEntity(level, entityId, this.entity.east(), "core-state-b", true, "state B entity");
    }

    void assertStateC(ServerLevel level, UUID entityId) {
        this.assertBlock(level, Blocks.DIAMOND_BLOCK, "branch state block");
        this.assertStateBDetails(level, entityId);
    }

    void assertLatestActionCapturedEntityMove(UUID entityId) {
        var action = UndoRedoHistoryManager.getInstance().recentUndoActions(this.projectId, 1)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing core journey undo action"));
        var change = action.redoEntityChanges().stream()
                .filter(candidate -> entityId.toString().equals(candidate.entityId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing core journey entity action"));
        BlockPos oldPos = change.oldValue() == null ? BlockPos.ZERO : change.oldValue().blockPos();
        BlockPos newPos = change.newValue() == null ? BlockPos.ZERO : change.newValue().blockPos();
        if (!this.entity.equals(oldPos) || !this.entity.east().equals(newPos)) {
            throw new AssertionError("Entity action positions did not match: " + oldPos + " -> " + newPos);
        }
    }

    String projectName() {
        return this.projectName;
    }

    String projectId() {
        return this.projectId;
    }

    private WorldMutationContext.SourceFrame playerAction() {
        return WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, AUTHOR, true);
    }

    private void setBarrelItem(ServerLevel level, Item item, int count) {
        if (!(level.getBlockEntity(this.barrel) instanceof BarrelBlockEntity blockEntity)) {
            throw new AssertionError("Missing barrel at " + this.barrel);
        }
        blockEntity.clearContent();
        blockEntity.setItem(0, new ItemStack(item, count));
    }

    private void assertBlock(ServerLevel level, Block expected, String label) {
        if (!level.getBlockState(this.block).is(expected)) {
            throw new AssertionError(label + ": " + level.getBlockState(this.block));
        }
    }

    private void assertStairs(ServerLevel level, Direction facing, String label) {
        if (!level.getBlockState(this.stairs).is(Blocks.OAK_STAIRS)
                || level.getBlockState(this.stairs).getValue(StairBlock.FACING) != facing) {
            throw new AssertionError(label + ": " + level.getBlockState(this.stairs));
        }
    }

    private void assertBarrel(ServerLevel level, Item item, int count, String label) {
        if (!(level.getBlockEntity(this.barrel) instanceof BarrelBlockEntity blockEntity)
                || !blockEntity.getItem(0).is(item)
                || blockEntity.getItem(0).getCount() != count) {
            throw new AssertionError(label + " did not match");
        }
    }

    private Entity requireEntity(ServerLevel level, UUID entityId) {
        Entity found = level.getEntity(entityId);
        if (found == null || found.isRemoved()) {
            throw new AssertionError("Missing core journey entity " + entityId);
        }
        return found;
    }

    private void assertEntity(
            ServerLevel level,
            UUID entityId,
            BlockPos pos,
            String name,
            boolean glowing,
            String label
    ) {
        Entity found = this.requireEntity(level, entityId);
        if (!found.blockPosition().equals(pos)
                || found.getCustomName() == null
                || !name.equals(found.getCustomName().getString())
                || found.isCurrentlyGlowing() != glowing) {
            throw new AssertionError(label + " did not match: pos=" + found.blockPosition()
                    + ", name=" + (found.getCustomName() == null ? "" : found.getCustomName().getString())
                    + ", glowing=" + found.isCurrentlyGlowing());
        }
    }
}
