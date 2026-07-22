package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Copies one visible 16x16x16 section into the Minecraft-free durable model. */
public final class MinecraftSectionCapture {
    private final Map<BlockState, String> encodedStates = new IdentityHashMap<>();

    public SectionBlob capture(ServerLevel level, LevelChunk chunk, int sectionY) throws IOException {
        int sectionIndex = sectionIndex(level, chunk, sectionY);
        List<String> states = captureStates(chunk.getSection(sectionIndex));
        return new SectionBlob(states, captureBlockEntities(level, chunk, sectionY));
    }

    public boolean matches(
            ServerLevel level,
            LevelChunk chunk,
            int sectionY,
            SectionBlob source,
            DecodedSection target) throws IOException {
        int sectionIndex = sectionIndex(level, chunk, sectionY);
        if (!matchesStates(chunk.getSection(sectionIndex), target.blockStates())) {
            return false;
        }
        return source.blockEntities().equals(
                captureBlockEntities(level, chunk, sectionY));
    }

    private static Map<Integer, CanonicalNbt> captureBlockEntities(
            ServerLevel level, LevelChunk chunk, int sectionY) throws IOException {
        Map<Integer, CanonicalNbt> blockEntities = new HashMap<>();
        for (var entry : chunk.getBlockEntities().entrySet()) {
            BlockPos position = entry.getKey();
            if (SectionPos.blockToSectionCoord(position.getY()) == sectionY) {
                CompoundTag saved = entry.getValue().saveWithFullMetadata(level.registryAccess());
                blockEntities.put(localIndex(position), canonicalBlockEntityNbt(saved));
            }
        }
        return Map.copyOf(blockEntities);
    }

    public static SectionKey key(BlockPos position) {
        return new SectionKey(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getY()),
                SectionPos.blockToSectionCoord(position.getZ()));
    }

    public static int localIndex(BlockPos position) {
        int x = SectionPos.sectionRelative(position.getX());
        int y = SectionPos.sectionRelative(position.getY());
        int z = SectionPos.sectionRelative(position.getZ());
        return (y << 8) | (z << 4) | x;
    }

    static CanonicalNbt canonicalBlockEntityNbt(CompoundTag saved) throws IOException {
        CompoundTag canonical = saved.copy();
        canonical.remove("x");
        canonical.remove("y");
        canonical.remove("z");
        return MinecraftNbtCodec.encode(canonical);
    }

    static boolean matchesStates(LevelChunkSection section, List<BlockState> expected) {
        if (expected.size() != SectionBlob.BLOCK_COUNT) {
            throw new IllegalArgumentException("Expected section must contain 4096 states");
        }
        section.acquire();
        try {
            for (int index = 0; index < expected.size(); index++) {
                if (!expected.get(index).equals(section.getBlockState(
                        index & 15, (index >>> 8) & 15, (index >>> 4) & 15))) {
                    return false;
                }
            }
            return true;
        } finally {
            section.release();
        }
    }

    private static int sectionIndex(ServerLevel level, LevelChunk chunk, int sectionY) {
        if (chunk.getLevel() != level) {
            throw new IllegalArgumentException("Chunk belongs to another level");
        }
        int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            throw new IllegalArgumentException("Section is outside the dimension build height");
        }
        return sectionIndex;
    }

    private List<String> captureStates(LevelChunkSection section) {
        List<String> states = new ArrayList<>(SectionBlob.BLOCK_COUNT);
        section.acquire();
        try {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        states.add(encodedStates.computeIfAbsent(
                                state, BlockStateParser::serialize));
                    }
                }
            }
        } finally {
            section.release();
        }
        return states;
    }
}
