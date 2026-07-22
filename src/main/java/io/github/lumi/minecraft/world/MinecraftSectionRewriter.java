package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Rewrites a loaded section directly, then repairs derived live-world state once. */
@SuppressWarnings("deprecation")
final class MinecraftSectionRewriter {
    private final ServerLevel level;

    MinecraftSectionRewriter(ServerLevel level) {
        this.level = level;
    }

    SectionApplyResult apply(LevelChunk chunk, SectionKey key, DecodedSection target) {
        int sectionIndex = chunk.getSectionIndexFromSectionY(key.sectionY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            throw new IllegalArgumentException("Section is outside the loaded chunk: " + key);
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        LevelChunkSection replacementSection = target.replacementFor(section);
        PreparedSectionDelta delta = target.deltaFrom(section);
        if (delta.changedIndexes().length == 0) {
            return new SectionApplyResult(key, delta.changedCells(), 0,
                    delta.blockEntitiesChanged());
        }

        chunk.getSections()[sectionIndex] = replacementSection;
        updateHeightmaps(chunk, key, replacementSection, delta.changedIndexes());
        if (delta.lightChanged()) {
            ((SectionLightBatchScheduler) level.getLightEngine())
                    .lumi$scheduleSectionChecks(
                            key.chunkX(), key.sectionY(), key.chunkZ(),
                            delta.lightColumns());
        }
        chunk.markUnsaved();
        return new SectionApplyResult(
                key, delta.changedCells(), delta.changedCells().length,
                delta.blockEntitiesChanged());
    }

    static void markLightChange(short[] updates, int x, int y, int z) {
        updates[(z << 4) | y] |= (short) (1 << x);
    }

    private static void updateHeightmaps(
            LevelChunk chunk,
            SectionKey key,
            LevelChunkSection section,
            int[] changedIndexes) {
        for (int changed = changedIndexes.length - 1; changed >= 0; changed--) {
            int localIndex = changedIndexes[changed];
            int x = localIndex & 15;
            int z = (localIndex >>> 4) & 15;
            int y = (localIndex >>> 8) & 15;
            BlockState state = section.getBlockState(x, y, z);
            int worldY = key.sectionY() * 16 + y;
            for (var heightmap : chunk.getHeightmaps()) {
                heightmap.getValue().update(x, worldY, z, state);
            }
        }
    }

    void synchronize(
            LevelChunk chunk,
            List<SectionApplyResult> sections,
            boolean blockEntitiesChanged) {
        int changedCells = sections.stream()
                .mapToInt(SectionApplyResult::changedCount).sum();
        if (changedCells == 0 && !blockEntitiesChanged) {
            return;
        }
        Packet<ClientGamePacketListener> packet = useFullChunkPacket(
                changedCells, blockEntitiesChanged)
                ? new ClientboundLevelChunkWithLightPacket(
                        chunk, level.getLightEngine(), new BitSet(), new BitSet())
                : null;
        for (var player : level.getChunkSource().chunkMap
                .getPlayers(chunk.getPos(), false)) {
            if (packet != null) {
                player.connection.send(packet);
            } else {
                for (SectionApplyResult section : sections) {
                    if (section.changedCount() > 0) {
                        player.connection.send(sectionPacket(chunk, section));
                    }
                }
            }
        }
    }

    static boolean useFullChunkPacket(int changedCells, boolean blockEntitiesChanged) {
        return blockEntitiesChanged || changedCells >= 1024;
    }

    private static ClientboundSectionBlocksUpdatePacket sectionPacket(
            LevelChunk chunk, SectionApplyResult result) {
        var changedCells = new ShortOpenHashSet(result.changedCells());
        int sectionIndex = chunk.getSectionIndexFromSectionY(result.key().sectionY());
        return new ClientboundSectionBlocksUpdatePacket(
                SectionPos.of(chunk.getPos(), result.key().sectionY()),
                changedCells, chunk.getSection(sectionIndex));
    }

}
