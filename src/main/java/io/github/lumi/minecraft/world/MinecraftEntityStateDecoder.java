package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Resolves durable entity types and canonical NBT before tick-time apply. */
public final class MinecraftEntityStateDecoder {
    private final Registry<EntityType<?>> types;

    public MinecraftEntityStateDecoder(Registry<EntityType<?>> types) {
        this.types = Objects.requireNonNull(types, "types");
    }

    public DecodedEntityChunk decode(EntityChunkBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        var decoded = new ArrayList<DecodedEntity>(source.entities().size());
        for (var entity : source.entities()) {
            final Identifier identifier;
            try {
                identifier = Identifier.parse(entity.type());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid persistent entity type: " + entity.type(), invalid);
            }
            EntityType<?> type = types.getOptional(identifier).orElseThrow(
                    () -> new IOException("Missing persistent entity type: " + entity.type()));
            var nbt = MinecraftNbtCodec.decode(entity.nbt());
            nbt.putString("id", entity.type());
            nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(entity.id()));
            decoded.add(new DecodedEntity(entity.id(), type, nbt));
        }
        return new DecodedEntityChunk(decoded);
    }
}
