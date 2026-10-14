package io.github.lumi.minecraft.world;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.WorldDataCompatibility;
import io.github.lumi.domain.service.WorldPayloadConverter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.StringJoiner;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/** Worker-only DataFixer adaptation; it never accesses or mutates a live world. */
public final class MinecraftWorldPayloadConverter implements WorldPayloadConverter {
    private final HolderLookup<Block> blocks;
    private final Registry<EntityType<?>> entityTypes;

    public MinecraftWorldPayloadConverter(HolderLookup<Block> blocks, Registry<EntityType<?>> entityTypes) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.entityTypes = Objects.requireNonNull(entityTypes, "entityTypes");
    }

    @Override
    public SectionBlob convert(SectionBlob source, WorldDataVersion target) throws IOException {
        requireRoute(source.dataVersion(), target);
        // Keep decoding caches bounded to the current leaf, rather than the history generation.
        var decoder = new MinecraftBlockStateDecoder(blocks);
        var translated = new HashMap<String, String>();
        for (String state : source.distinctBlockStates()) {
            CompoundTag fixed = fix(References.BLOCK_STATE, blockStateTag(state), source.dataVersion(), target);
            translated.put(state, BlockStateParser.serialize(decoder.decodeState(blockStateText(fixed))));
        }
        var states = new ArrayList<String>(SectionBlob.BLOCK_COUNT);
        for (String state : source.blockStates()) states.add(translated.get(state));
        var blockEntities = new HashMap<Integer, io.github.lumi.domain.model.CanonicalNbt>();
        for (var entry : source.blockEntities().entrySet()) {
            CompoundTag fixed = fix(References.BLOCK_ENTITY, MinecraftNbtCodec.decode(entry.getValue()),
                    source.dataVersion(), target);
            blockEntities.put(entry.getKey(), MinecraftNbtCodec.encode(fixed));
        }
        SectionBlob result = new SectionBlob(states, blockEntities, target);
        decoder.validate(result);
        return result;
    }

    @Override
    public EntityChunkBlob convert(EntityChunkBlob source, WorldDataVersion target) throws IOException {
        requireRoute(source.dataVersion(), target);
        var entities = new ArrayList<EntityState>(source.entities().size());
        var canonicalizer = new MinecraftEntityNbtCanonicalizer();
        for (EntityState entity : source.entities()) {
            CompoundTag payload = MinecraftNbtCodec.decode(entity.nbt());
            payload.putString("id", entity.type());
            payload.putIntArray("UUID", UUIDUtil.uuidToIntArray(entity.id()));
            CompoundTag fixed = fix(References.ENTITY_TREE, payload, source.dataVersion(), target);
            validateEntityTree(fixed);
            if (!entity.id().equals(fixed.read("UUID", UUIDUtil.CODEC).orElse(null))) {
                throw new IOException("Conversion changed or removed entity UUID: " + entity.id());
            }
            String type = fixed.getString("id").orElseThrow(() -> new IOException("Converted entity has no type"));
            entities.add(new EntityState(entity.id(), type, MinecraftNbtCodec.encode(canonicalizer.normalize(fixed))));
        }
        var decoder = new MinecraftEntityStateDecoder(entityTypes);
        EntityChunkBlob result = decoder.normalize(new EntityChunkBlob(entities, target));
        decoder.decodeNormalized(result);
        return result;
    }

    private void validateEntityTree(CompoundTag entity) throws IOException {
        Identifier id = Identifier.tryParse(entity.getStringOr("id", ""));
        if (id == null || entityTypes.getOptional(id).isEmpty()) {
            throw new IOException("Missing converted entity type: " + entity.getStringOr("id", ""));
        }
        var passengers = entity.getListOrEmpty("Passengers");
        for (Tag tag : passengers) {
            if (!(tag instanceof CompoundTag passenger)) throw new IOException("Invalid converted passenger");
            validateEntityTree(passenger);
        }
    }

    private static void requireRoute(WorldDataVersion source, WorldDataVersion target) throws IOException {
        WorldDataCompatibility.requireForward(source, target);
        MinecraftWorldDataVersion.requireCurrent(target);
    }

    private static CompoundTag fix(DSL.TypeReference type, CompoundTag payload,
            WorldDataVersion source, WorldDataVersion target) throws IOException {
        try {
            Tag fixed = DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, payload),
                    source.value(), target.value()).getValue();
            if (!(fixed instanceof CompoundTag compound)) throw new IOException("DataFixer returned a non-compound");
            return compound;
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot convert " + type.typeName() + " to " + target.value(), invalid);
        }
    }

    private static CompoundTag blockStateTag(String text) throws IOException {
        int opening = text.indexOf('[');
        String name = opening < 0 ? text : text.substring(0, opening);
        Identifier id = Identifier.tryParse(name);
        if (id == null) throw new IOException("Invalid source block name: " + name);
        var result = new CompoundTag();
        result.putString("Name", id.toString());
        if (opening >= 0) {
            if (!text.endsWith("]")) throw new IOException("Invalid source block properties: " + text);
            var properties = new CompoundTag();
            for (String property : text.substring(opening + 1, text.length() - 1).split(",", -1)) {
                String[] pair = property.split("=", -1);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || properties.contains(pair[0])) {
                    throw new IOException("Invalid source block property: " + property);
                }
                properties.putString(pair[0], pair[1]);
            }
            result.put("Properties", properties);
        }
        return result;
    }

    private static String blockStateText(CompoundTag state) throws IOException {
        String name = state.getString("Name").orElseThrow(() -> new IOException("Converted block has no name"));
        if (!state.contains("Properties")) return name;
        CompoundTag properties = state.getCompound("Properties").orElseThrow(() ->
                new IOException("Converted block properties are not a compound"));
        if (properties.isEmpty()) return name;
        var text = new StringJoiner(",", name + "[", "]");
        for (String key : properties.keySet().stream().sorted().toList()) {
            text.add(key + "=" + properties.getString(key).orElseThrow(() ->
                    new IOException("Converted block property is not a string: " + key)));
        }
        return text.toString();
    }
}
