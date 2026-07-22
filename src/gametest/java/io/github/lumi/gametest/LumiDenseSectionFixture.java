package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Builds deterministic high-entropy benchmark data by replacing native sections. */
final class LumiDenseSectionFixture {
    private static final List<BlockState> PALETTE = List.of(
            Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
            Blocks.TERRACOTTA, Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE,
            Blocks.MAGENTA_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE,
            Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE, Blocks.PINK_CONCRETE,
            Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CYAN_CONCRETE,
            Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.BROWN_CONCRETE,
            Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE, Blocks.BLACK_CONCRETE,
            Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.QUARTZ_BLOCK,
            Blocks.PRISMARINE, Blocks.DARK_PRISMARINE, Blocks.PURPUR_BLOCK,
            Blocks.END_STONE).stream().map(Block::defaultBlockState).toList();

    private final TestServerContext server;
    private final LumiBehaviorReport report;

    LumiDenseSectionFixture(TestServerContext server, LumiBehaviorReport report) {
        this.server = server;
        this.report = report;
    }

    void fill(String name, BlockBox area, int paletteOffset) {
        long started = System.nanoTime();
        try {
            server.runOnServer(minecraft -> fillOnServer(
                    minecraft.getPlayerList().getPlayers().getFirst().level(),
                    area, paletteOffset));
            report.event("fixture", name, "succeeded", 0,
                    elapsedMillis(started), describe(area));
        } catch (RuntimeException | Error failed) {
            report.event("fixture", name, "failed", 0,
                    elapsedMillis(started), failed.toString());
            throw failed;
        }
    }

    private static void fillOnServer(
            ServerLevel level, BlockBox area, int paletteOffset) {
        var runtime = LumiMod.serverRuntime().find(level).orElseThrow();
        var capture = new MinecraftSectionCapture();
        int minChunkX = SectionPos.blockToSectionCoord(area.minX());
        int maxChunkX = SectionPos.blockToSectionCoord(area.maxX());
        int minChunkZ = SectionPos.blockToSectionCoord(area.minZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(area.maxZ());
        int minSectionY = SectionPos.blockToSectionCoord(area.minY());
        int maxSectionY = SectionPos.blockToSectionCoord(area.maxY());
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int capturedSectionY = sectionY;
                    SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
                    runtime.mutations().registerSectionMutation(key,
                            () -> captureUnchecked(
                                    capture, level, chunk, capturedSectionY));
                    replaceSection(chunk, level, area, key, paletteOffset);
                    runtime.mutations().markBuilderMutation(key);
                }
                chunk.markUnsaved();
            }
        }
    }

    private static SectionBlob captureUnchecked(
            MinecraftSectionCapture capture,
            ServerLevel level,
            LevelChunk chunk,
            int sectionY) {
        try {
            return capture.capture(level, chunk, sectionY);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static void replaceSection(
            LevelChunk chunk,
            ServerLevel level,
            BlockBox area,
            SectionKey key,
            int paletteOffset) {
        int sectionIndex = level.getSectionIndexFromSectionY(key.sectionY());
        LevelChunkSection current = chunk.getSection(sectionIndex);
        var states = current.getStates().copy();
        int baseX = key.chunkX() * 16;
        int baseY = key.sectionY() * 16;
        int baseZ = key.chunkZ() * 16;
        for (int y = Math.max(area.minY(), baseY);
                y <= Math.min(area.maxY(), baseY + 15); y++) {
            for (int z = Math.max(area.minZ(), baseZ);
                    z <= Math.min(area.maxZ(), baseZ + 15); z++) {
                for (int x = Math.max(area.minX(), baseX);
                        x <= Math.min(area.maxX(), baseX + 15); x++) {
                    states.set(x & 15, y & 15, z & 15,
                            denseState(x, y, z, paletteOffset));
                }
            }
        }
        chunk.getSections()[sectionIndex] = new LevelChunkSection(
                states, current.getBiomes());
    }

    private static BlockState denseState(int x, int y, int z, int offset) {
        long mixed = x * 0x9E3779B97F4A7C15L
                ^ y * 0xC2B2AE3D27D4EB4FL
                ^ z * 0x165667B19E3779F9L
                ^ offset * 0x85EBCA77C2B2AE63L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        return PALETTE.get((int) (mixed & (PALETTE.size() - 1)));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String describe(BlockBox area) {
        return area.minX() + "," + area.minY() + "," + area.minZ()
                + ".." + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }
}
