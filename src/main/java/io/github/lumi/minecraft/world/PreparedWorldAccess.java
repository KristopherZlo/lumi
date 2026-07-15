package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** Minimal Minecraft mutation/readback port used by the deadline-bounded apply cursor. */
public interface PreparedWorldAccess {
    void setBlock(SectionKey key, int localIndex, BlockState state) throws IOException;

    List<Integer> blockEntityIndexes(SectionKey key) throws IOException;

    void removeBlockEntity(SectionKey key, int localIndex) throws IOException;

    void loadBlockEntity(SectionKey key, int localIndex, CompoundTag nbt) throws IOException;

    SectionBlob captureSection(SectionKey key) throws IOException;
}
