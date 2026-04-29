package io.github.luma.integration.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.AutoCheckpointService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldEditEditSessionTracker {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public boolean register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return false;
        }
        WorldEdit.getInstance().getEventBus().register(this);
        return true;
    }

    @Subscribe
    public void onEditSessionEvent(EditSessionEvent event) {
        if (event == null || event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        ServerLevel level = this.resolveServerLevel(event);
        if (level == null) {
            return;
        }
        String actor = this.actorName(event.getActor());
        String actionId = UUID.randomUUID().toString();
        AutoCheckpointService.getInstance().checkpointBeforeExternalOperation(
                level,
                WorldMutationSource.WORLDEDIT,
                actor,
                actionId
        );
        event.setExtent(new TrackingExtent(
                event.getExtent(),
                level,
                actor,
                actionId
        ));
    }

    private String actorName(Actor actor) {
        if (actor == null || actor.getName() == null || actor.getName().isBlank()) {
            return "worldedit";
        }
        return "worldedit:" + actor.getName().toLowerCase(Locale.ROOT);
    }

    private ServerLevel resolveServerLevel(EditSessionEvent event) {
        try {
            Level level = FabricAdapter.adapt(event.getWorld());
            return level instanceof ServerLevel serverLevel ? serverLevel : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static final class TrackingExtent extends AbstractDelegateExtent {

        private final ServerLevel level;
        private final String actor;
        private final String actionId;

        private TrackingExtent(Extent extent, ServerLevel level, String actor, String actionId) {
            super(extent);
            this.level = level;
            this.actor = actor;
            this.actionId = actionId;
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 location, B block) throws WorldEditException {
            BlockPos pos = FabricAdapter.toBlockPos(location);
            BlockState oldState = this.level.getBlockState(pos);
            CompoundTag oldBlockEntity = this.blockEntityTag(pos, oldState);
            WorldMutationContext.pushExternalSource(WorldMutationSource.WORLDEDIT, this.actor, this.actionId);
            try {
                boolean changed = super.setBlock(location, block);
                if (changed) {
                    BlockState newState = this.level.getBlockState(pos);
                    this.recordChange(pos, oldState, newState, oldBlockEntity);
                }
                return changed;
            } finally {
                WorldMutationContext.popSource();
            }
        }

        private void recordChange(BlockPos pos, BlockState oldState, BlockState newState, CompoundTag oldBlockEntity) {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    this.level,
                    pos,
                    oldState,
                    newState,
                    oldBlockEntity,
                    this.blockEntityTag(pos, newState)
            );
        }

        private CompoundTag blockEntityTag(BlockPos pos, BlockState state) {
            if (state == null || !state.hasBlockEntity()) {
                return null;
            }
            BlockEntity blockEntity = this.level.getBlockEntity(pos);
            return blockEntity == null ? null : blockEntity.saveWithFullMetadata(this.level.registryAccess());
        }
    }
}
