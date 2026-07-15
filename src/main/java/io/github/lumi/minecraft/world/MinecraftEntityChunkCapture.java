package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.storage.TagValueOutput;

/** Copies durable non-player entities into Lumi's canonical chunk payload. */
public final class MinecraftEntityChunkCapture {
    public EntityChunkBlob capture(
            ServerLevel level, Stream<? extends EntityAccess> entityStream) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entityStream, "entityStream");
        var captured = new ArrayList<EntityState>();
        try (entityStream) {
            for (EntityAccess access : entityStream.toList()) {
                if (!(access instanceof Entity entity)
                        || entity instanceof Player || !entity.shouldBeSaved()) {
                    continue;
                }
                TagValueOutput output = TagValueOutput.createWithContext(
                        ProblemReporter.DISCARDING, level.registryAccess());
                if (!entity.save(output)) {
                    continue;
                }
                captured.add(new EntityState(
                        entity.getUUID(), EntityType.getKey(entity.getType()).toString(),
                        canonicalEntityNbt(output.buildResult())));
            }
        }
        return new EntityChunkBlob(captured);
    }

    static CanonicalNbt canonicalEntityNbt(CompoundTag saved) throws IOException {
        CompoundTag payload = Objects.requireNonNull(saved, "saved").copy();
        payload.remove("id");
        payload.remove("UUID");
        return MinecraftNbtCodec.encode(payload);
    }
}
