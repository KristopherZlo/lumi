package io.github.luma.minecraft.world;

import io.github.luma.domain.model.BlockChangeRecord;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockChangeApplier {

    private BlockChangeApplier() {
    }

    public static void applyChanges(ServerLevel level, List<BlockChangeRecord> changes) throws IOException {
        for (BlockChangeRecord change : changes) {
            BlockPos pos = change.pos().toBlockPos();
            BlockState state = BlockStateNbtCodec.deserializeBlockState(level, change.newState());
            CompoundTag blockEntityTag = BlockStateNbtCodec.deserializeBlockEntity(change.newBlockEntityNbt());

            level.removeBlockEntity(pos);
            level.setBlock(pos, state, 3);

            if (blockEntityTag != null) {
                BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, blockEntityTag, level.registryAccess());
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                }
            }
        }
    }
}
