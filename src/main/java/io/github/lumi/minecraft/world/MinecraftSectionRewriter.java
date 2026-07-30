package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
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
        if (delta.changedCells().length == 0) {
            return new SectionApplyResult(key, delta.changedCells(), 0,
                    delta.blockEntitiesChanged(), false);
        }

        chunk.getSections()[sectionIndex] = replacementSection;
        updatePois(key, replacementSection, delta.poiIndexes());
        updateHeightmaps(chunk, key, replacementSection, delta.heightmapIndexes());
        if (delta.lightChanged()) {
            ((SectionLightBatchScheduler) level.getLightEngine())
                    .lumi$scheduleSectionChecks(
                            key.chunkX(), key.sectionY(), key.chunkZ(),
                            delta.lightColumns());
        }
        chunk.markUnsaved();
        return new SectionApplyResult(
                key, delta.changedCells(), delta.changedCells().length,
                delta.blockEntitiesChanged(), delta.lightChanged());
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

    ChunkSyncResult synchronize(
            LevelChunk chunk,
            List<SectionApplyResult> sections,
            boolean blockEntitiesChanged) {
        int changedCells = sections.stream()
                .mapToInt(SectionApplyResult::changedCount).sum();
        if (changedCells == 0 && !blockEntitiesChanged) {
            return ChunkSyncResult.NONE;
        }
        boolean full = useFullChunkPacket(changedCells, blockEntitiesChanged);
        Packet<ClientGamePacketListener> packet = full
                ? new ClientboundLevelChunkWithLightPacket(
                        chunk, level.getLightEngine(), new BitSet(), new BitSet())
                : null;
        var players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        long payloadBytes = packetPayloadBytes(packet, sections);
        for (var player : players) {
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
        int recipients = players.size();
        return full
                ? new ChunkSyncResult(recipients, 0, payloadBytes * recipients)
                : new ChunkSyncResult(0,
                        sections.stream().mapToInt(section -> section.changedCount() > 0
                                ? recipients : 0).sum(), payloadBytes * recipients);
    }

    private static long packetPayloadBytes(
            Packet<ClientGamePacketListener> packet,
            List<SectionApplyResult> sections) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket full) {
            long bytes = full.getChunkData().getReadBuffer().readableBytes();
            bytes += full.getLightData().getSkyUpdates().stream()
                    .mapToLong(update -> update.length).sum();
            bytes += full.getLightData().getBlockUpdates().stream()
                    .mapToLong(update -> update.length).sum();
            return bytes;
        }
        return sections.stream().mapToLong(
                section -> section.changedCount() == 0
                        ? 0 : 16L + section.changedCount() * 10L).sum();
    }

    private void updatePois(
            SectionKey key,
            LevelChunkSection section,
            int[] changedIndexes) {
        var pois = level.getChunkSource().getPoiManager();
        for (int localIndex : changedIndexes) {
            int x = localIndex & 15;
            int z = (localIndex >>> 4) & 15;
            int y = (localIndex >>> 8) & 15;
            BlockPos position = new BlockPos(
                    key.chunkX() * 16 + x,
                    key.sectionY() * 16 + y,
                    key.chunkZ() * 16 + z);
            var target = PoiTypes.forState(section.getBlockState(x, y, z));
            var current = pois.getType(position);
            if (current.equals(target)) {
                continue;
            }
            current.ifPresent(ignored -> pois.remove(position));
            target.ifPresent(type -> pois.add(position, type));
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
