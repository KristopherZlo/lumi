package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** Builds four exact endpoints spanning loaded and stored chunk paths. */
final class LumiExactRestoreFixture {
    private static final int DENSE_WIDTH = 128;
    private static final int DENSE_DEPTH = 64;
    private static final int DENSE_HEIGHT = 16;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
    private final LumiBehaviorReport report;
    private final LumiBehaviorActions actions;
    private final LumiDenseSectionFixture dense;
    private final BlockBox loadedArea;
    private final BlockBox storedArea;
    private final BlockBox featureArea;
    private final BlockPos chest;
    private final BlockPos sign;
    private final BlockPos poi;
    private final BlockPos heightProbe;
    private final BlockPos respawnA;
    private final BlockPos respawnB;
    private final Vec3 moverA;
    private final Vec3 moverB;
    private UUID mover;

    LumiExactRestoreFixture(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.context = context;
        this.singleplayer = singleplayer;
        this.report = report;
        actions = new LumiBehaviorActions(singleplayer.getServer(), report);
        dense = new LumiDenseSectionFixture(
                context, singleplayer.getServer(), report);
        BlockPos origin = singleplayer.getServer().computeOnServer(server ->
                server.getPlayerList().getPlayers().getFirst().blockPosition());
        BlockPos feature = origin.offset(2, 0, 2);
        Layout layout = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            int denseY = Math.min(
                    player.level().getMaxY() - DENSE_HEIGHT,
                    Math.max(origin.getY() + 48, 192));
            int loadedX = Math.floorDiv(origin.getX(), 16) * 16 + 64;
            int loadedZ = Math.floorDiv(origin.getZ(), 16) * 16 - 32;
            return new Layout(
                    box(loadedX, denseY, loadedZ),
                    box(loadedX + 2_048, denseY, loadedZ + 2_048),
                    new BlockBox(
                            origin.getX() - 16,
                            Math.max(player.level().getMinY(), feature.getY() - 4),
                            origin.getZ() - 16,
                            origin.getX() + 31,
                            Math.min(player.level().getMaxY() - 1,
                                    feature.getY() + 48),
                            origin.getZ() + 31),
                    feature);
        });
        loadedArea = layout.loaded();
        storedArea = layout.stored();
        featureArea = layout.features();
        BlockPos base = layout.featureBase();
        chest = base;
        sign = base.east();
        poi = base.east(2);
        heightProbe = base.east(3);
        respawnA = base.south(2);
        respawnB = base.east().south(2);
        int boundaryX = (Math.floorDiv(base.getX(), 16) + 1) * 16;
        moverA = Vec3.atBottomCenterOf(new BlockPos(
                boundaryX - 1, base.getY(), base.getZ() + 2));
        moverB = Vec3.atBottomCenterOf(new BlockPos(
                boundaryX, base.getY(), base.getZ() + 2));
    }

    void buildA() {
        dense.fill("exact_a_loaded_dense", loadedArea, 0);
        dense.fill("exact_a_stored_dense", storedArea, 0);
        configureFeatures("A", Items.DIAMOND, 7,
                Blocks.LECTERN, 8, respawnA, 10, 20, true);
        List<BlockPos> stands = List.of(
                chest.west(2).south(),
                chest.south(),
                chest.west().south(2));
        List<UUID> ids = actions.placeArmorStands(stands);
        context.waitTicks(10);
        mover = ids.getFirst();
        actions.moveDurableEntity("exact_a_move_entity", mover, moverA);
        actions.attachPassenger("exact_a_passenger", ids.get(1), ids.get(2));
    }

    void buildB() {
        dense.fill("exact_b_loaded_dense", loadedArea, 1);
        configureFeatures("B", Items.EMERALD, 11,
                Blocks.SMITHING_TABLE, 20, respawnB, 35, -15, false);
        actions.moveDurableEntity("exact_b_move_entity", mover, moverB);
    }

    void buildC() {
        dense.fill("exact_c_stored_dense", storedArea, 2);
    }

    void buildD() {
        dense.fill("exact_d_loaded_dense", loadedArea, 3);
        dense.fill("exact_d_stored_dense", storedArea, 0);
        configureFeatures("D", Items.GOLD_INGOT, 19,
                Blocks.COMPOSTER, 34, respawnA, -20, 5, true);
        actions.moveDurableEntity("exact_d_move_entity", mover, moverA);
    }

    List<BlockBox> areas() {
        return List.of(loadedArea, storedArea, featureArea);
    }

    void awaitStoredUnloaded(String name) {
        dense.awaitUnloaded(name + "_stored_unloaded", storedArea);
    }

    DerivedState derivedState() {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level =
                    server.getPlayerList().getPlayers().getFirst().level();
            String poiType = level.getChunkSource().getPoiManager()
                    .getType(poi)
                    .flatMap(holder -> holder.unwrapKey())
                    .map(key -> key.identifier().toString())
                    .orElse("");
            return new DerivedState(
                    poiType,
                    level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING,
                            heightProbe.getX(), heightProbe.getZ()),
                    level.getHeight(
                            Heightmap.Types.WORLD_SURFACE,
                            heightProbe.getX(), heightProbe.getZ()));
        });
    }

    private void configureFeatures(
            String label,
            Item item,
            int count,
            Block poiBlock,
            int height,
            BlockPos respawn,
            float yaw,
            float pitch,
            boolean forced) {
        long started = System.nanoTime();
        singleplayer.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel level = player.level();
            var runtime = io.github.lumi.LumiMod.serverRuntime()
                    .find(level).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(
                    runtime.liveActions(), player.getUUID())) {
                for (BlockPos support : List.of(
                        chest, sign, poi, heightProbe,
                        chest.west(2).south(), chest.south(),
                        chest.west().south(2),
                        BlockPos.containing(moverA),
                        BlockPos.containing(moverB))) {
                    level.setBlockAndUpdate(
                            support.below(), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                ChestBlockEntity chestEntity =
                        requireType(level, chest, ChestBlockEntity.class);
                chestEntity.clearContent();
                chestEntity.setItem(0, new ItemStack(item, count));
                chestEntity.setChanged();

                level.setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState());
                SignBlockEntity signEntity =
                        requireType(level, sign, SignBlockEntity.class);
                signEntity.setText(new SignText().setMessage(
                        0, Component.literal(label)), true);
                signEntity.setChanged();

                level.setBlockAndUpdate(poi, poiBlock.defaultBlockState());
                for (int y = 0; y <= 40; y++) {
                    level.setBlockAndUpdate(heightProbe.above(y),
                            Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(
                        heightProbe.above(height), Blocks.STONE.defaultBlockState());
            }
            var data = LevelData.RespawnData.of(
                    level.dimension(), respawn, yaw, pitch);
            player.setRespawnPosition(
                    new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                            data, forced),
                    false);
        });
        report.event("fixture", "exact_features_" + label.toLowerCase(),
                "succeeded", 0, elapsedMillis(started),
                "chest=" + item + ";poi=" + poiBlock
                        + ";height=" + height + ";respawn=" + respawn);
    }

    private static <T> T requireType(
            ServerLevel level, BlockPos position, Class<T> type) {
        Object value = level.getBlockEntity(position);
        if (!type.isInstance(value)) {
            throw new AssertionError("Expected " + type.getSimpleName()
                    + " at " + position + " but found " + value);
        }
        return type.cast(value);
    }

    private static BlockBox box(int x, int y, int z) {
        return new BlockBox(
                x, y, z,
                x + DENSE_WIDTH - 1,
                y + DENSE_HEIGHT - 1,
                z + DENSE_DEPTH - 1);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    record DerivedState(
            String poiType, int motionBlockingHeight, int worldSurfaceHeight) {
        DerivedState {
            Objects.requireNonNull(poiType, "poiType");
        }
    }

    private record Layout(
            BlockBox loaded,
            BlockBox stored,
            BlockBox features,
            BlockPos featureBase) { }
}
