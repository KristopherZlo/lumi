package io.github.lumi.vanillatest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;

/** Independent native serialization check; its test JAR contains no Lumi implementation or mixins. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiVanillaReopenGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (FabricLoader.getInstance().isModLoaded("lumi")) throw new AssertionError("Lumi is installed");
        try {
            Class.forName("io.github.lumi.LumiMod", false, getClass().getClassLoader());
            throw new AssertionError("Lumi production classes are on the control classpath");
        } catch (ClassNotFoundException expected) { }
        ServerLifecycleEvents.SERVER_STARTING.register(server -> server.tickRateManager().setFrozen(true));
        Path fixture = Path.of(System.getProperty("lumi.gametest.upgrade.fixture")).toAbsolutePath();
        try {
            var expected = open(context, fixture.resolve("vanilla-saved"), "vanilla-reference");
            var actual = open(context, fixture.resolve("restored-target"), "vanilla-restored");
            if (!expected.equals(actual)) {
                var keys = new java.util.TreeSet<>(expected.keySet());
                keys.addAll(actual.keySet());
                for (String key : keys) {
                    if (!java.util.Objects.equals(expected.get(key), actual.get(key))) {
                        throw new AssertionError("World differs without Lumi at " + key + ": expected "
                                + expected.get(key) + ", got " + actual.get(key));
                    }
                }
            }
            Files.writeString(Path.of(System.getProperty("lumi.gametest.completion")),
                    SharedConstants.getCurrentVersion().name() + "\nwithout-lumi", StandardOpenOption.CREATE_NEW);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot verify world without Lumi", failed);
        }
    }

    private static Map<String, CompoundTag> open(ClientGameTestContext context, Path source, String name)
            throws IOException {
        Path destination = context.computeOnClient(client -> client.getLevelSource().getBaseDir().resolve(name));
        if (!Files.isRegularFile(source.resolve("level.dat")) || Files.exists(destination)) {
            throw new IOException("Control copy requires a closed world and an unused destination");
        }
        try (var paths = Files.walk(source)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path relative = source.relativize(path);
                if (relative.toString().equals("session.lock")) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link in control world");
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        try (var world = new TestWorldSaveImpl(context, destination).open()) {
            context.runOnClient(client -> client.options.pauseOnLostFocus = false);
            context.setScreen(() -> null);
            context.waitFor(client -> client.level != null && client.player != null);
            for (int tick = 0; tick < 1200; tick++) {
                var snapshot = world.getServer().computeOnServer(server -> capture(server.getPlayerList().getPlayers().getFirst().level()));
                if (snapshot.keySet().stream().anyMatch(key -> key.startsWith("entity/"))) return snapshot;
                context.waitTick();
            }
            throw new AssertionError("The upgrade fixture's entity tree did not load without Lumi");
        }
    }

    private static Map<String, CompoundTag> capture(ServerLevel level) {
        Map<String, CompoundTag> result = new HashMap<>();
        for (BlockPos position : BlockPos.betweenClosed(0, 176, 0, 15, 191, 15)) {
            var block = new CompoundTag();
            block.putString("state", BlockStateParser.serialize(level.getBlockState(position)));
            var entity = level.getBlockEntity(position);
            if (entity != null) block.put("blockEntity", entity.saveWithFullMetadata(level.registryAccess()));
            result.put(position.getX() + "," + position.getY() + "," + position.getZ(), block);
        }
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Player || entity.isPassenger() || !entity.shouldBeSaved()
                    || entity.getBlockX() < 0 || entity.getBlockX() > 15
                    || entity.getBlockY() < 176 || entity.getBlockY() > 191
                    || entity.getBlockZ() < 0 || entity.getBlockZ() > 15) continue;
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            if (!entity.save(output)) throw new AssertionError("Control entity was not serializable");
            result.put("entity/" + entity.getUUID(), output.buildResult());
        }
        return result;
    }
}
