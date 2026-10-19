package io.github.lumi.minecraft.runtime;

import com.moulberry.axiom.world_modification.BiomeBuffer;
import io.github.lumi.domain.model.BiomePosition;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Captures accepted native biome writes; it does not expose a Lumi biome editing tool. */
public final class AxiomBiomeCapture {
    private AxiomBiomeCapture() { }

    public static void run(BiomeBuffer buffer, FabricDimensionRuntime runtime, UUID actor, Runnable apply) {
        if (!runtime.freeze().isMutationAllowed()) return;
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), actor)) {
            UUID action = DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
            AxiomBufferDispatchContext.actor().ifPresent(player -> AxiomBufferDispatchContext.current(player)
                    .ifPresent(dispatch -> runtime.axiomBufferActions().attach(player.connection, actor, dispatch, action)));
            var before = new LinkedHashMap<BiomePosition, String>();
            buffer.forEachEntry((x, y, z, biome) -> {
                var position = new BiomePosition(x, y, z);
                var value = read(runtime.level(), position);
                if (value != null) before.put(position, value);
            });
            try { apply.run(); }
            finally {
                before.forEach((position, value) -> {
                    var after = read(runtime.level(), position);
                    if (after == null) throw new IllegalStateException("Axiom biome chunk disappeared during apply");
                    runtime.liveActions().recordBiome(action, position, value, after);
                });
            }
        }
    }

    public static void prepareReplay(FabricDimensionRuntime runtime, LiveActionJournal.Plan plan) throws IOException {
        var sections = new java.util.HashSet<io.github.lumi.domain.model.SectionKey>();
        for (var entry : plan.biomes().entrySet()) {
            String expected = plan.direction() == LiveActionJournal.Direction.UNDO ? entry.getValue().after() : entry.getValue().before();
            if (!expected.equals(read(runtime.level(), entry.getKey()))) throw new IOException("Biome action conflicts at " + entry.getKey());
            sections.add(entry.getKey().section());
        }
        var capture = new MinecraftSectionCapture();
        for (var key : sections) runtime.mutations().registerSectionMutation(key, () -> {
            try { return capture.capture(runtime.level(), runtime.level().getChunk(key.chunkX(), key.chunkZ()), key.sectionY()); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
    }

    private static String read(ServerLevel level, BiomePosition position) {
        if (level.getChunkSource().getChunkNow(position.x() >> 2, position.z() >> 2) == null
                || (position.y() >> 2) < level.getMinSectionY() || (position.y() >> 2) >= level.getMaxSectionY()) return null;
        return level.getNoiseBiome(position.x(), position.y(), position.z()).unwrapKey().orElseThrow().identifier().toString();
    }
}
