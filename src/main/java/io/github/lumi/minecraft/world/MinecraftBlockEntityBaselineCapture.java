package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Captures only block-entity NBT when an untracked chunk becomes live. */
public final class MinecraftBlockEntityBaselineCapture {
    public void remember(
            ServerLevel level,
            LevelChunk chunk,
            MutationDurabilityTracker mutations,
            BlockEntityBaselineStore baselines) throws IOException {
        if (chunk.getLevel() != level) {
            throw new IllegalArgumentException("Chunk belongs to another level");
        }
        Map<SectionKey, Map<Integer, CanonicalNbt>> bySection = new HashMap<>();
        for (var entry : chunk.getBlockEntities().entrySet()) {
            BlockPos position = entry.getKey();
            SectionKey key = MinecraftSectionCapture.key(position);
            if (!mutations.needsOrigin(key)) {
                continue;
            }
            var saved = entry.getValue().saveWithFullMetadata(level.registryAccess());
            bySection.computeIfAbsent(key, ignored -> new HashMap<>())
                    .put(MinecraftSectionCapture.localIndex(position),
                            MinecraftSectionCapture.canonicalBlockEntityNbt(saved));
        }
        bySection.forEach(baselines::remember);
    }
}
