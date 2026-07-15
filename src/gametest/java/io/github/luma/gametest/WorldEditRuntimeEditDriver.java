package io.github.luma.gametest;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.fabric.FabricAdapter;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Applies test mutations through the real WorldEdit edit-session pipeline. */
final class WorldEditRuntimeEditDriver {

    void apply(ServerLevel level, Map<BlockPos, BlockState> changes) {
        try (EditSession session = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(FabricAdapter.adapt(level))
                .build()) {
            for (Map.Entry<BlockPos, BlockState> change : changes.entrySet()) {
                session.setBlock(
                        FabricAdapter.adapt(change.getKey()),
                        FabricAdapter.adapt(change.getValue())
                );
            }
        } catch (WorldEditException exception) {
            throw new IllegalStateException("WorldEdit runtime mutation failed", exception);
        }
    }
}
