package io.github.luma.minecraft.bootstrap;

import net.minecraft.nbt.CompoundTag;

final class WorldChunkActivityClassifier {

    static final String NAME = "persistent-player-payload-v2";

    boolean shouldBackup(CompoundTag chunkTag) {
        return this.classify(chunkTag) == ChunkBackupDecision.BACKUP;
    }

    ChunkBackupDecision classify(CompoundTag chunkTag) {
        if (chunkTag == null) {
            return ChunkBackupDecision.SKIP_PRISTINE;
        }
        if (this.hasPersistentPayload(chunkTag)) {
            return ChunkBackupDecision.BACKUP;
        }
        if (chunkTag.getLongOr("InhabitedTime", 0L) > 0L) {
            return ChunkBackupDecision.SKIP_VISITED_ONLY;
        }
        return ChunkBackupDecision.SKIP_PRISTINE;
    }

    private boolean hasPersistentPayload(CompoundTag chunkTag) {
        return this.hasEntries(chunkTag, "block_entities")
                || this.hasEntries(chunkTag, "entities")
                || this.hasEntries(chunkTag, "block_ticks")
                || this.hasEntries(chunkTag, "fluid_ticks")
                || this.hasEntries(chunkTag, "TileTicks")
                || this.hasEntries(chunkTag, "LiquidTicks");
    }

    private boolean hasEntries(CompoundTag tag, String key) {
        return tag.contains(key) && tag.getListOrEmpty(key).size() > 0;
    }

    enum ChunkBackupDecision {
        BACKUP,
        SKIP_PRISTINE,
        SKIP_VISITED_ONLY
    }
}
