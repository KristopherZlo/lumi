package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;

/** Complete Minecraft WorldStateApply adapter: off-thread decode plus bounded mutation. */
public final class MinecraftWorldStateApply implements WorldStateApply {
    private final MinecraftRestorePreparation preparation;
    private final PreparedWorldAccess world;
    private final ServerLevel level;

    public MinecraftWorldStateApply(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        preparation = new MinecraftRestorePreparation(
                new MinecraftBlockStateDecoder(
                        level.registryAccess().lookupOrThrow(Registries.BLOCK)),
                new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE));
        world = new MinecraftPreparedWorldAccess(level, freeze);
    }

    @Override
    public PreparedState prepare(State target) throws IOException {
        return preparation.prepare(target);
    }

    @Override
    public ApplySession begin(PreparedState target) {
        if (!(target instanceof PreparedMinecraftState minecraft)) {
            throw new IllegalArgumentException("Restore state was not prepared for Minecraft");
        }
        return new PreparedWorldMutationSession(
                minecraft, world, System::nanoTime,
                new ChunkLoadSession(new MinecraftChunkLoadAccess(level)));
    }
}
