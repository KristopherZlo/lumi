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
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.AABB;

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
        this.verifyDyingMobUndo();
        this.verifyPoweredTntChainUndo();
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
        Entity spawned = this.singleplayer.getServer().computeOnServer(server -> {
            Entity entity;
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
                entity = EntityType.ARMOR_STAND.create(server.overworld(), EntitySpawnReason.COMMAND);
            }
            this.require(entity != null, "Could not create armor stand");
            entity.snapTo(this.pos(4, 1, 10).getCenter().x, this.pos(4, 1, 10).getY(),
                    this.pos(4, 1, 10).getCenter().z, 0.0F, 0.0F);
            entity.setNoGravity(true);
            return entity;
        });
        UUID entityId = spawned.getUUID();
        this.roundTrip("entity spawn", (server, level) -> level.addFreshEntity(spawned), 1, false);
        this.roundTrip("entity update", (server, level) -> {
            Entity entity = this.entity(level, entityId);
            entity.setCustomName(Component.literal("lumi-undo-entity"));
            entity.snapTo(this.pos(5, 1, 10).getCenter().x, this.pos(5, 1, 10).getY(),
                    this.pos(5, 1, 10).getCenter().z, 90.0F, 0.0F);
        }, 1, false);
        this.roundTrip("entity removal", (server, level) -> this.entity(level, entityId).discard(), 1, false);
    }

    private void verifyDyingMobUndo() throws Exception {
        BlockPos mobPos = this.pos(6, 1, 10);
        UUID entityId = this.singleplayer.getServer().computeOnServer(server -> {
            LivingEntity mob;
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
                mob = EntityType.COW.create(server.overworld(), EntitySpawnReason.COMMAND);
                this.require(mob != null, "Could not create dying-mob fixture");
                mob.snapTo(mobPos.getCenter().x, mobPos.getY(), mobPos.getCenter().z, 0.0F, 0.0F);
                this.require(server.overworld().addFreshEntity(mob), "Could not spawn dying-mob fixture");
            }
            return mob.getUUID();
        });
        this.waitTicks(SETTLE_TICKS);
        this.history.clearProject(this.projectId);

        try {
            String actionId = this.runAction((server, level) -> {
                LivingEntity mob = (LivingEntity) this.entity(level, entityId);
                this.require(mob.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE),
                        "Dying-mob fixture rejected lethal damage");
            });
            this.waitForClientEntity(entityId, LivingEntity::isDeadOrDying,
                    "Client never entered the mob death animation");
            this.require(this.history.recentUndoActions(this.projectId, 64).stream()
                            .anyMatch(action -> action.id().equals(actionId)),
                    "Lethal mob action was not recorded");

            this.waitFor(this.undo(), "undo dying mob");
            this.require(this.singleplayer.getServer().computeOnServer(server -> {
                Entity restored = server.overworld().getEntity(entityId);
                return restored instanceof LivingEntity living
                        && living.isAlive()
                        && living.deathTime == 0;
            }), "Server mob remained in its death animation after undo");
            this.waitForClientEntity(entityId,
                    living -> living.isAlive() && living.deathTime == 0,
                    "Client mob remained in its death animation after undo");
        } finally {
            this.singleplayer.getServer().runOnServer(server -> WorldMutationContext.runWithSource(
                    WorldMutationSource.RESTORE,
                    () -> {
                        Entity entity = server.overworld().getEntity(entityId);
                        if (entity != null) {
                            entity.discard();
                        }
                    }
            ));
            this.history.clearProject(this.projectId);
            this.waitTicks(SETTLE_TICKS);
        }
    }

    private void waitForClientEntity(
            UUID entityId,
            Predicate<LivingEntity> predicate,
            String failure
    ) throws Exception {
        for (int tick = 0; tick < 40; tick++) {
            boolean matched = this.context.computeOnClient(client -> client.level != null
                    && java.util.stream.StreamSupport.stream(client.level.entitiesForRendering().spliterator(), false)
                            .filter(LivingEntity.class::isInstance)
                            .map(LivingEntity.class::cast)
                            .anyMatch(entity -> entityId.equals(entity.getUUID()) && predicate.test(entity)));
            if (matched) {
                return;
            }
            this.context.waitTick();
        }
        String clientState = this.context.computeOnClient(client -> client.level == null
                ? "level missing"
                : java.util.stream.StreamSupport.stream(client.level.entitiesForRendering().spliterator(), false)
                        .filter(LivingEntity.class::isInstance)
                        .map(LivingEntity.class::cast)
                        .map(entity -> entity.getUUID() + ":alive=" + entity.isAlive() + ":deathTime=" + entity.deathTime)
                        .toList()
                        .toString());
        throw new AssertionError(failure + "; nearby=" + clientState);
    }

    private void verifyPoweredTntChainUndo() throws Exception {
        BlockPos first = this.pos(9, 3, 12);
        BlockPos second = first.east();
        BlockPos third = first.south();
        List<BlockPos> witnesses = List.of(
                first.north(), first.west(), first.east(2), first.south(2), second.north(), third.west());
        AABB blastArea = new AABB(first).inflate(5.0D);

        this.restoreMutation(level -> {
            for (BlockPos pos : BlockPos.betweenClosed(first.west(3).below(3), first.east(4).south(4).above(4))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(first.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            level.setBlock(second.below(), Blocks.OBSIDIAN.defaultBlockState(), 3);
            level.setBlock(third.below(), Blocks.OBSIDIAN.defaultBlockState(), 3);
            witnesses.forEach(pos -> level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), 3));
        });
        this.history.clearProject(this.projectId);

        try {
            String firstActionId = this.runAction((server, level) ->
                    level.setBlock(first, Blocks.TNT.defaultBlockState(), 3));
            this.waitForServer(level -> this.primedTntPresent(level, blastArea), 40,
                    "Powered TNT did not ignite on placement");

            this.runAction((server, level) -> level.setBlock(second, Blocks.TNT.defaultBlockState(), 3));
            this.runAction((server, level) -> level.setBlock(third, Blocks.TNT.defaultBlockState(), 3));
            this.waitForServer(level -> level.getBlockState(first).isAir()
                            && level.getBlockState(second).isAir()
                            && level.getBlockState(third).isAir()
                            && !this.primedTntPresent(level, blastArea),
                    20 * 10, "TNT chain did not finish exploding");
            this.waitTicks(SETTLE_TICKS * 2);

            this.require(this.singleplayer.getServer().computeOnServer(server -> witnesses.stream()
                            .anyMatch(pos -> !server.overworld().getBlockState(pos).is(Blocks.OAK_PLANKS))),
                    "TNT chain did not destroy a witness block");
            var selection = this.history.selectUndo(this.projectId);
            UndoRedoAction selected = selection == null ? null : selection.action();
            this.require(selected != null && selected.id().equals(firstActionId),
                    "TNT fallout was not promoted to the igniting action");

            this.waitFor(this.undo(), "undo powered TNT chain");
            this.require(this.singleplayer.getServer().computeOnServer(server -> {
                ServerLevel level = server.overworld();
                return level.getBlockState(first).isAir()
                        && level.getBlockState(second).is(Blocks.TNT)
                        && level.getBlockState(third).is(Blocks.TNT)
                        && witnesses.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS))
                        && !this.primedTntPresent(level, blastArea);
            }), "One undo did not restore the complete powered TNT fallout");
        } finally {
            this.restoreMutation(level -> {
                for (BlockPos pos : BlockPos.betweenClosed(first.west(3).below(3), first.east(4).south(4).above(4))) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            });
            this.history.clearProject(this.projectId);
        }
    }

    private boolean primedTntPresent(ServerLevel level, AABB bounds) {
        return !level.getEntities((Entity) null, bounds, entity -> entity.getType() == EntityType.TNT).isEmpty();
    }

    private void waitForServer(Predicate<ServerLevel> condition, int timeoutTicks, String failure) throws Exception {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (this.singleplayer.getServer().computeOnServer(server -> condition.test(server.overworld()))) {
                return;
            }
            this.context.waitTick();
        }
        throw new AssertionError(failure);
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
        this.restoreMutation(level -> level.setBlock(this.pos(13, 5, 13), Blocks.STONE.defaultBlockState(), 3));
        this.roundTrip("gravity scheduled tick", (server, level) ->
                level.setBlock(sand, Blocks.SAND.defaultBlockState(), 3), 1, true);
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
