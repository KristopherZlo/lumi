package io.github.luma.minecraft.bootstrap;

import net.minecraft.nbt.CompoundTag;

final class WorldChunkActivityClassifier {

    static final String NAME = "conservative-player-activity-v1";

    boolean shouldBackup(CompoundTag chunkTag) {
        if (chunkTag == null) {
            return false;
        }
        if (chunkTag.getLongOr("InhabitedTime", 0L) > 0L) {
            return true;
        }
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
}
