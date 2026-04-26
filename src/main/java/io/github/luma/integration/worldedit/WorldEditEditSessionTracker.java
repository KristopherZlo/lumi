package io.github.luma.integration.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
        event.setExtent(new TrackingExtent(
                event.getExtent(),
                this.actorName(event.getActor()),
                UUID.randomUUID().toString()
        ));
    }

    private String actorName(Actor actor) {
        if (actor == null || actor.getName() == null || actor.getName().isBlank()) {
            return "worldedit";
        }
        return "worldedit:" + actor.getName().toLowerCase(Locale.ROOT);
    }

    private static final class TrackingExtent extends AbstractDelegateExtent {

        private final String actor;
        private final String actionId;

        private TrackingExtent(Extent extent, String actor, String actionId) {
            super(extent);
            this.actor = actor;
            this.actionId = actionId;
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 location, B block) throws WorldEditException {
            WorldMutationContext.pushExternalSource(WorldMutationSource.WORLDEDIT, this.actor, this.actionId);
            try {
                return super.setBlock(location, block);
            } finally {
                WorldMutationContext.popSource();
            }
        }
    }
}
