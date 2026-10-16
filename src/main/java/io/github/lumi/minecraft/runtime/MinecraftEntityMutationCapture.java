package io.github.lumi.minecraft.runtime;

import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/** Captures edited passenger graphs through the existing live journal and durable origins. */
public final class MinecraftEntityMutationCapture implements AutoCloseable {
    private final FabricDimensionRuntime runtime;
    private final Set<Entity> members = new LinkedHashSet<>();
    private final Map<UUID, MinecraftLiveEntityTracker.Pending> before = new LinkedHashMap<>();

    public MinecraftEntityMutationCapture(FabricDimensionRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    public void capture(Collection<Entity> targets) throws IOException {
        for (Entity target : targets) {
            Entity root = target.getRootVehicle();
            if (before.containsKey(root.getUUID()) || !MinecraftEntityChunkCapture.isDurableRoot(root)) continue;
            root.getSelfAndPassengers().forEach(members::add);
            runtime.entityRemoving(root);
            runtime.liveEntities().begin(root).ifPresent(pending -> before.put(root.getUUID(), pending));
        }
    }

    @Override public void close() throws IOException {
        Set<Entity> roots = new LinkedHashSet<>();
        for (Entity member : members) {
            Entity root = member.getRootVehicle();
            if (!root.isRemoved() && MinecraftEntityChunkCapture.isDurableRoot(root)) roots.add(root);
        }
        for (var pending : before.values()) runtime.liveEntities().finish(pending);
        for (Entity root : roots) {
            if (!before.containsKey(root.getUUID())) {
                runtime.entityAdded(root);
                runtime.liveEntities().added(root);
            }
        }
    }
}
