package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.QuickRollbackService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/** Compact exact live undo/redo journey embedded in the history GameTest. */
final class LiveUndoRedoJourney {

    private static final int SETTLE_TICKS = 8;
    private static final int OPERATION_TIMEOUT_TICKS = 20 * 60;

    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
    private final SingleplayerTestVolume volume;
    private final String projectName;
    private final String projectId;
    private final UndoRedoService undoRedo = new UndoRedoService();
    private final QuickRollbackService quickRollback = new QuickRollbackService();
    private final UndoRedoHistoryManager history = UndoRedoHistoryManager.getInstance();
    private final WorldOperationManager operations = WorldOperationManager.getInstance();

    LiveUndoRedoJourney(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            SingleplayerTestVolume volume,
            String projectName,
            String projectId
    ) {
        this.context = context;
        this.singleplayer = singleplayer;
        this.volume = volume;
        this.projectName = projectName;
        this.projectId = projectId;
    }

    void run() throws Exception {
        this.verifyBlockPlacementAndBreak();
        this.verifyBlockEntityNbt();
        this.verifyEntityLifecycle();
        this.verifyPistonFallout();
        this.verifyFluidAndGravityTicks();
        this.verifyNewActionClearsRedo();
        this.clearFixture();
        this.roundTrip("undo before save", (server, level) ->
                level.setBlock(this.pos(15, 1, 15), Blocks.IRON_BLOCK.defaultBlockState(), 3), 1, true);
    }

    void verifyQuickRollbackUndoable() throws Exception {
        BlockPos marker = this.pos(15, 1, 14);
        this.runAction((server, level) -> level.setBlock(marker, Blocks.COPPER_BLOCK.defaultBlockState(), 3));
        this.waitTicks(SETTLE_TICKS);
        StructureFixtureSnapshot beforeRollback = this.capture();

        OperationHandle rollback = this.singleplayer.getServer().computeOnServer(server ->
                this.quickRollback.quickRollback(server.overworld(), this.projectName));
        this.waitFor(rollback, "quick rollback");
        this.require(this.singleplayer.getServer().computeOnServer(server ->
                server.overworld().getBlockState(marker).isAir()), "Quick rollback did not restore the branch head");

        this.waitFor(this.undo(), "undo quick rollback");
        this.assertSnapshot(beforeRollback, "undo quick rollback");
        this.waitFor(this.redo(), "redo quick rollback");
        this.require(this.singleplayer.getServer().computeOnServer(server ->
                server.overworld().getBlockState(marker).isAir()), "Redo did not reapply quick rollback");
    }

    private void verifyBlockPlacementAndBreak() throws Exception {
        BlockPos marker = this.pos(1, 1, 10);
        this.roundTrip("block placement", (server, level) ->
                level.setBlock(marker, Blocks.STONE.defaultBlockState(), 3), 1, false);
        this.roundTrip("block break", (server, level) ->
                level.setBlock(marker, Blocks.AIR.defaultBlockState(), 3), 1, true);
    }

    private void verifyBlockEntityNbt() throws Exception {
        BlockPos barrel = this.pos(2, 1, 10);
        this.restoreMutation(level -> {
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
            ((BarrelBlockEntity) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND, 1));
        });
        this.roundTrip("barrel NBT", (server, level) ->
                ((BarrelBlockEntity) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND, 16)),
                1, true);
    }

    private void verifyEntityLifecycle() throws Exception {
        UUID[] entityId = new UUID[1];
        this.roundTrip("entity spawn", (server, level) -> {
            Entity entity = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            this.require(entity != null, "Could not create armor stand");
            entity.snapTo(this.pos(4, 1, 10).getCenter().x, this.pos(4, 1, 10).getY(),
                    this.pos(4, 1, 10).getCenter().z, 0.0F, 0.0F);
            entityId[0] = entity.getUUID();
            level.addFreshEntity(entity);
        }, 1, false);
        this.roundTrip("entity update", (server, level) -> {
            Entity entity = this.entity(level, entityId[0]);
            entity.setCustomName(Component.literal("lumi-undo-entity"));
            entity.snapTo(this.pos(5, 1, 10).getCenter().x, this.pos(5, 1, 10).getY(),
                    this.pos(5, 1, 10).getCenter().z, 90.0F, 0.0F);
        }, 1, false);
        this.roundTrip("entity removal", (server, level) -> this.entity(level, entityId[0]).discard(), 1, false);
    }

    private void verifyPistonFallout() throws Exception {
        BlockPos piston = this.pos(8, 1, 12);
        this.restoreMutation(level -> {
            level.setBlock(piston, Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
            level.setBlock(piston.east(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        });
        this.roundTrip("piston fallout", (server, level) ->
                level.setBlock(piston.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3), 2, true);
    }

    private void verifyFluidAndGravityTicks() throws Exception {
        BlockPos water = this.pos(3, 2, 12);
        this.restoreMutation(level -> {
            for (int x = 1; x <= 5; x++) {
                for (int z = 10; z <= 14; z++) {
                    level.setBlock(this.pos(x, 1, z), Blocks.STONE.defaultBlockState(), 3);
                    if (x == 1 || x == 5 || z == 10 || z == 14) {
                        level.setBlock(this.pos(x, 2, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        });
        this.roundTrip("fluid scheduled tick", (server, level) ->
                level.setBlock(water, Blocks.WATER.defaultBlockState(), 3), 2, true);

        BlockPos sand = this.pos(13, 7, 13);
        this.restoreMutation(level -> level.setBlock(this.pos(13, 1, 13), Blocks.STONE.defaultBlockState(), 3));
        this.roundTrip("gravity scheduled tick", (server, level) ->
                level.setBlock(sand, Blocks.SAND.defaultBlockState(), 3), 2, true);
    }

    private void verifyNewActionClearsRedo() throws Exception {
        BlockPos marker = this.pos(14, 1, 15);
        this.runAction((server, level) -> {
            level.setBlock(marker, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(marker, Blocks.AIR.defaultBlockState(), 3);
        });
        this.waitTicks(SETTLE_TICKS);
        this.require(this.history.selectRedo(this.projectId) == null, "New action did not clear redo");
    }

    private void roundTrip(String label, WorldAction action, int minimumChanges, boolean restoreBefore)
            throws Exception {
        StructureFixtureSnapshot before = this.capture();
        String actionId = this.runAction(action);
        this.waitTicks(label.contains("scheduled") ? SETTLE_TICKS * 3 : SETTLE_TICKS);
        StructureFixtureSnapshot after = this.capture();
        UndoRedoAction recorded = this.history.recentUndoActions(this.projectId, 64).stream()
                .filter(candidate -> candidate.id().equals(actionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing action " + label));
        this.require(recorded.size() >= minimumChanges,
                label + " was not grouped: expected at least " + minimumChanges + ", actual=" + recorded.size());

        this.waitFor(this.undo(), "undo " + label);
        this.assertSnapshot(before, "undo " + label);
        this.waitFor(this.redo(), "redo " + label);
        this.assertSnapshot(after, "redo " + label);
        if (restoreBefore) {
            this.waitFor(this.undo(), "cleanup undo " + label);
            this.assertSnapshot(before, "cleanup undo " + label);
        }
    }

    private String runAction(WorldAction action) throws Exception {
        return this.singleplayer.getServer().computeOnServer(server -> {
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushPlayerSource(
                    WorldMutationSource.PLAYER, "Lumi undo journey", true)) {
                String actionId = WorldMutationContext.currentActionId();
                action.run(server, server.overworld());
                return actionId;
            }
        });
    }

    private OperationHandle undo() throws Exception {
        return this.singleplayer.getServer().computeOnServer(server ->
                this.undoRedo.undo(server.overworld(), this.projectName));
    }

    private OperationHandle redo() throws Exception {
        return this.singleplayer.getServer().computeOnServer(server ->
                this.undoRedo.redo(server.overworld(), this.projectName));
    }

    private void waitFor(OperationHandle handle, String label) throws Exception {
        for (int tick = 0; tick < OPERATION_TIMEOUT_TICKS; tick++) {
            OperationSnapshot snapshot = this.singleplayer.getServer().computeOnServer(server ->
                    this.operations.snapshot(server, handle).orElse(null));
            boolean active = this.singleplayer.getServer().computeOnServer(this.operations::hasActiveOperation);
            if (snapshot != null && snapshot.terminal() && !active) {
                this.require(!snapshot.failed(), "Operation failed for " + label + ": " + snapshot.detail());
                this.waitTicks(SETTLE_TICKS);
                return;
            }
            this.context.waitTick();
        }
        throw new AssertionError("Timed out waiting for " + label);
    }

    private StructureFixtureSnapshot capture() throws Exception {
        return this.singleplayer.getServer().computeOnServer(server ->
                StructureFixtureSnapshot.capture(server.overworld(), this.volume));
    }

    private void assertSnapshot(StructureFixtureSnapshot expected, String label) throws Exception {
        StructureFixtureSnapshot actual = this.capture();
        this.require(expected.matches(actual), label + " mismatch: " + expected.diff(actual));
    }

    private void restoreMutation(LevelAction action) throws Exception {
        this.singleplayer.getServer().runOnServer(server ->
                WorldMutationContext.runWithSource(WorldMutationSource.RESTORE,
                        () -> action.run(server.overworld())));
        this.waitTicks(SETTLE_TICKS);
    }

    private void clearFixture() throws Exception {
        this.restoreMutation(level -> {
            for (BlockPos pos : BlockPos.betweenClosed(this.pos(0, 1, 10), this.pos(15, 10, 15))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        });
    }

    private Entity entity(ServerLevel level, UUID id) {
        Entity entity = id == null ? null : level.getEntity(id);
        if (entity == null) {
            throw new AssertionError("Missing entity " + id);
        }
        return entity;
    }

    private BlockPos pos(int x, int y, int z) {
        return this.volume.min().offset(x, y, z);
    }

    private void waitTicks(int ticks) throws Exception {
        for (int tick = 0; tick < ticks; tick++) {
            this.context.waitTick();
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface WorldAction {
        void run(MinecraftServer server, ServerLevel level);
    }

    @FunctionalInterface
    private interface LevelAction {
        void run(ServerLevel level);
    }
}
