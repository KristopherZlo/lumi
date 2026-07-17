package io.github.lumi.gametest;

import com.sk89q.worldedit.fabric.FabricPermissionsProvider;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Performs the requested changes through real player and command entry points. */
final class LumiBehaviorActions {
    private final TestServerContext server;
    private final LumiBehaviorReport report;

    LumiBehaviorActions(TestServerContext server, LumiBehaviorReport report) {
        this.server = server;
        this.report = report;
    }

    List<BlockPos> planTwentyTnt(BlockPos origin) {
        return server.computeOnServer(minecraft -> {
            ServerLevel level = player(minecraft).level();
            List<BlockPos> positions = new ArrayList<>(20);
            for (int z = 3; z < 7; z++) {
                for (int x = -2; x < 3; x++) {
                    positions.add(surfacePlacement(
                            level, origin.getX() + x, origin.getZ() + z));
                }
            }
            return List.copyOf(positions);
        });
    }

    List<BlockPos> planTntGrid128(BlockPos origin) {
        return server.computeOnServer(minecraft -> {
            ServerLevel level = player(minecraft).level();
            List<BlockPos> positions = new ArrayList<>(128);
            for (int z = -7; z <= 7; z += 2) {
                for (int x = -15; x <= 15; x += 2) {
                    positions.add(surfacePlacement(
                            level, origin.getX() + x, origin.getZ() + z));
                }
            }
            return List.copyOf(positions);
        });
    }

    void placeTnt(BlockPos target) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            place(player, Items.TNT, target);
            require(player.level().getBlockState(target).is(Blocks.TNT),
                    "Player did not place TNT at " + target);
        });
    }

    void igniteTnt(String name, BlockPos target) {
        timed(name, () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            useOn(player, Items.FLINT_AND_STEEL, target, Direction.UP);
        }));
    }

    void placeBlocks(String name, Item item, List<BlockPos> positions) {
        timed(name, () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            Block expected = Block.byItem(item);
            require(expected != Blocks.AIR, "Item is not a placeable block: " + item);
            for (BlockPos position : positions) {
                place(player, item, position);
                require(player.level().getBlockState(position).is(expected),
                        "Player did not place " + item + " at " + position);
            }
        }));
    }

    void destroyBlocks(String name, List<BlockPos> positions) {
        timed(name, () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            for (BlockPos position : positions) {
                require(player.gameMode.destroyBlock(position),
                        "Player could not break block " + position);
            }
        }));
    }

    BlockPos surfacePosition(int x, int z) {
        return server.computeOnServer(minecraft ->
                surfacePlacement(player(minecraft).level(), x, z));
    }

    boolean hasTnt(List<BlockPos> positions) {
        return server.computeOnServer(minecraft -> {
            ServerLevel level = player(minecraft).level();
            return positions.stream().anyMatch(position ->
                    level.getBlockState(position).is(Blocks.TNT))
                    || StreamSupport.stream(
                            level.getAllEntities().spliterator(), false)
                            .anyMatch(PrimedTnt.class::isInstance);
        });
    }

    boolean hasPrimedTnt(BlockPos position) {
        return server.computeOnServer(minecraft -> StreamSupport.stream(
                        player(minecraft).level().getAllEntities().spliterator(), false)
                .filter(PrimedTnt.class::isInstance)
                .anyMatch(entity -> entity.distanceToSqr(
                        Vec3.atCenterOf(position)) < 9));
    }

    boolean hasEntity(UUID id) {
        return server.computeOnServer(minecraft ->
                player(minecraft).level().getEntityInAnyDimension(id) != null);
    }

    UUID placeEndCrystal(BlockPos obsidian) {
        return timed("place_end_crystal", () -> server.computeOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            useOn(player, Items.END_CRYSTAL, obsidian, Direction.UP);
            EndCrystal crystal = nearest(
                    player.level(), EndCrystal.class, Vec3.atCenterOf(obsidian.above()));
            require(crystal != null, "End crystal was not placed on " + obsidian);
            return crystal.getUUID();
        }));
    }

    List<UUID> placeArmorStands(List<BlockPos> positions) {
        return timed("place_armor_stands", () -> server.computeOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            List<UUID> placed = new ArrayList<>(positions.size());
            for (BlockPos position : positions) {
                useOn(player, Items.ARMOR_STAND, position.below(), Direction.UP);
                ArmorStand stand = nearestNew(
                        player.level(), ArmorStand.class,
                        Vec3.atBottomCenterOf(position), placed);
                require(stand != null, "Armor stand was not placed at " + position);
                placed.add(stand.getUUID());
            }
            return List.copyOf(placed);
        }));
    }

    void equipArmorStands(List<UUID> stands, long seed) {
        timed("equip_armor_stands", () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            List<List<Item>> sets = List.of(
                    List.of(Items.IRON_BOOTS, Items.IRON_LEGGINGS,
                            Items.IRON_CHESTPLATE, Items.IRON_HELMET),
                    List.of(Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS,
                            Items.GOLDEN_CHESTPLATE, Items.GOLDEN_HELMET),
                    List.of(Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS,
                            Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET));
            Random random = new Random(seed);
            for (UUID id : stands) {
                ArmorStand stand = entity(player.level(), id, ArmorStand.class);
                player.teleportTo(stand.getX() + 2, stand.getY(), stand.getZ());
                for (Item item : sets.get(random.nextInt(sets.size()))) {
                    ItemStack stack = new ItemStack(item);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    player.connection.handleInteract(
                            ServerboundInteractPacket.createInteractionPacket(
                                    stand, false, InteractionHand.MAIN_HAND,
                                    new Vec3(0, 1, 0)));
                    require(stand.getItemBySlot(
                            stand.getEquipmentSlotForItem(stack)).is(item),
                            "Armor stand did not equip " + item);
                }
            }
        }));
    }

    UUID spawnChicken(BlockPos position) {
        return timed("spawn_chicken", () -> server.computeOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            useOn(player, Items.CHICKEN_SPAWN_EGG, position.below(), Direction.UP);
            Chicken chicken = nearest(
                    player.level(), Chicken.class, Vec3.atBottomCenterOf(position));
            require(chicken != null, "Chicken was not spawned at " + position);
            return chicken.getUUID();
        }));
    }

    void attackEntity(String name, UUID id, Item weapon) {
        timed(name, () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            Entity target = entity(player.level(), id, Entity.class);
            player.teleportTo(target.getX() + 2, target.getY(), target.getZ());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
            player.connection.handleInteract(
                    ServerboundInteractPacket.createAttackPacket(target, false));
        }));
    }

    void shootEndCrystal(UUID id) {
        timed("shoot_end_crystal", () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            ServerLevel level = player.level();
            EndCrystal crystal = entity(level, id, EndCrystal.class);
            player.teleportTo(crystal.getX() - 3, crystal.getY(), crystal.getZ());
            var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(
                    runtime.liveActions(), player.getUUID())) {
                Arrow arrow = new Arrow(level, player,
                        new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                arrow.setPos(crystal.getX() - 3, crystal.getY() + 1, crystal.getZ());
                arrow.shoot(1, 0, 0, 3, 0);
                require(level.addFreshEntity(arrow), "Bow arrow was not spawned");
            }
        }));
    }

    void placeTwentyTnt(List<BlockPos> positions) {
        timed("place_20_tnt", () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            for (BlockPos target : positions) {
                place(player, Items.TNT, target);
                require(player.level().getBlockState(target).is(Blocks.TNT),
                        "Player did not place TNT at " + target);
            }
        }));
    }

    void powerTnt(BlockPos tnt) {
        timed("place_redstone_block", () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            Direction direction = List.of(
                            Direction.UP, Direction.NORTH, Direction.SOUTH,
                            Direction.WEST, Direction.EAST)
                    .stream()
                    .filter(candidate -> player.level().getBlockState(
                            tnt.relative(candidate)).canBeReplaced())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "TNT has no free side at " + tnt));
            BlockPos redstone = tnt.relative(direction);
            useOn(player, Items.REDSTONE_BLOCK, tnt, direction);
            require(player.level().getBlockState(redstone).is(Blocks.REDSTONE_BLOCK),
                    "Player did not place the redstone block");
        }));
    }

    Platform buildPlatform(BlockPos origin) {
        int minX = origin.getX() - 10;
        int minZ = origin.getZ() - 10;
        int minY = origin.getY() + 100;
        BlockPos min = new BlockPos(minX, minY, minZ);
        BlockPos max = new BlockPos(minX + 19, minY + 4, minZ + 19);
        playerCommand("build_platform", "fill " + coordinates(min) + " "
                + coordinates(max) + " minecraft:smooth_stone");
        server.runOnServer(minecraft -> player(minecraft).teleportTo(
                origin.getX() + 0.5, max.getY() + 1, origin.getZ() + 0.5));
        return new Platform(min, max, new BlockPos(
                origin.getX(), max.getY() + 1, origin.getZ()));
    }

    void breakPlatformByHand(Platform platform) {
        timed("break_platform_by_hand", () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            int y = platform.max().getY();
            for (int x = platform.center().getX() - 2;
                    x <= platform.center().getX() + 2; x++) {
                for (int z = platform.center().getZ() - 1;
                        z <= platform.center().getZ(); z++) {
                    BlockPos target = new BlockPos(x, y, z);
                    require(player.gameMode.destroyBlock(target),
                            "Player could not break platform block " + target);
                    require(player.level().getBlockState(target).isAir(),
                            "Broken platform block remained at " + target);
                }
            }
        }));
    }

    void worldEditCylinder() {
        worldEdit("worldedit_cyl_dirt", "//cyl dirt 20");
    }

    void worldEditGreen() {
        worldEdit("worldedit_green", "//green 30");
    }

    void worldEditReplaceNear() {
        worldEdit("worldedit_replace_near",
                "//replacenear 30 dirt smooth_stone");
    }

    void buildOakCube(BlockPos center) {
        BlockPos min = center.offset(-2, 0, -2);
        BlockPos max = center.offset(2, 4, 2);
        playerCommand("build_oak_cube", "fill " + coordinates(min) + " "
                + coordinates(max) + " minecraft:oak_planks");
    }

    List<BlockPos> placeAndIgniteFiveTnt(BlockPos center) {
        return timed("place_and_ignite_5_tnt", () -> server.computeOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            List<BlockPos> positions = List.of(
                    center.offset(3, 0, 0),
                    center.offset(-3, 0, 0),
                    center.offset(0, 0, 3),
                    center.offset(0, 0, -3),
                    center.offset(3, 0, 3));
            for (BlockPos position : positions) {
                place(player, Items.TNT, position);
                require(player.level().getBlockState(position).is(Blocks.TNT),
                        "Player did not place TNT at " + position);
            }
            for (BlockPos position : positions) {
                useOn(player, Items.FLINT_AND_STEEL, position, Direction.UP);
            }
            return positions;
        }));
    }

    private void playerCommand(String name, String command) {
        timed(name, () -> server.runOnServer(minecraft -> {
            execute(minecraft, player(minecraft), command);
        }));
    }

    private void worldEdit(String name, String command) {
        timed(name, () -> server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            FabricPermissionsProvider previous =
                    FabricWorldEdit.inst.getPermissionsProvider();
            FabricWorldEdit.inst.setPermissionsProvider(new FabricPermissionsProvider() {
                @Override
                public boolean hasPermission(ServerPlayer actor, String permission) {
                    return actor == player;
                }

                @Override
                public void registerPermission(String permission) { }
            });
            try {
                execute(minecraft, player, command);
            } finally {
                FabricWorldEdit.inst.setPermissionsProvider(previous);
            }
        }));
    }

    private static void execute(
            MinecraftServer minecraft, ServerPlayer player, String command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var source = player.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
        String parsedCommand = Commands.trimOptionalPrefix(command);
        var parsed = minecraft.getCommands().getDispatcher().parse(parsedCommand, source);
        Commands.validateParseResults(parsed);
        minecraft.getCommands().performCommand(parsed, parsedCommand);
    }

    private static void place(ServerPlayer player, Item item, BlockPos target) {
        useOn(player, item, target.below(), Direction.UP);
    }

    private static void useOn(
            ServerPlayer player, Item item, BlockPos clicked, Direction face) {
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(clicked), face, clicked, false);
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var result = player.gameMode.useItemOn(
                player, player.level(), stack,
                InteractionHand.MAIN_HAND, hit);
        require(result.consumesAction(), "Player item use failed at " + clicked);
    }

    private static BlockPos surfacePlacement(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = top + 1; y >= level.getMinY(); y--) {
            BlockPos target = new BlockPos(x, y, z);
            BlockPos below = target.below();
            if (level.getBlockState(target).canBeReplaced()
                    && level.getBlockState(below).isFaceSturdy(
                            level, below, Direction.UP)) {
                return target;
            }
        }
        throw new AssertionError("No free surface block at " + x + "," + z);
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static <T extends Entity> T entity(
            ServerLevel level, UUID id, Class<T> type) {
        Entity entity = level.getEntityInAnyDimension(id);
        require(type.isInstance(entity), "Missing " + type.getSimpleName() + " " + id);
        return type.cast(entity);
    }

    private static <T extends Entity> T nearest(
            ServerLevel level, Class<T> type, Vec3 position) {
        return StreamSupport.stream(level.getAllEntities().spliterator(), false)
                .filter(type::isInstance)
                .map(type::cast)
                .min(java.util.Comparator.comparingDouble(
                        entity -> entity.distanceToSqr(position)))
                .filter(entity -> entity.distanceToSqr(position) < 9)
                .orElse(null);
    }

    private static <T extends Entity> T nearestNew(
            ServerLevel level,
            Class<T> type,
            Vec3 position,
            List<UUID> excluded) {
        return StreamSupport.stream(level.getAllEntities().spliterator(), false)
                .filter(type::isInstance)
                .map(type::cast)
                .filter(entity -> !excluded.contains(entity.getUUID()))
                .min(java.util.Comparator.comparingDouble(
                        entity -> entity.distanceToSqr(position)))
                .filter(entity -> entity.distanceToSqr(position) < 9)
                .orElse(null);
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }

    private <T> T timed(String name, Action<T> action) {
        long started = System.nanoTime();
        try {
            T result = action.run();
            report.event("change", name, "succeeded", 0,
                    elapsedMillis(started), "");
            return result;
        } catch (RuntimeException | Error failed) {
            report.event("change", name, "failed", 0,
                    elapsedMillis(started), failed.toString());
            throw failed;
        } catch (Exception failed) {
            report.event("change", name, "failed", 0,
                    elapsedMillis(started), failed.toString());
            throw new IllegalStateException(name + " failed", failed);
        }
    }

    private void timed(String name, CheckedAction action) {
        timed(name, () -> {
            action.run();
            return null;
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    record Platform(BlockPos min, BlockPos max, BlockPos center) { }

    @FunctionalInterface
    private interface Action<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
