package io.github.lumi.minecraft.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Converts persistent strings and canonical NBT into Minecraft-native apply data. */
public final class MinecraftBlockStateDecoder {
    private final HolderLookup<Block> blocks;
    private final ConcurrentHashMap<String, BlockState> decodedStates =
            new ConcurrentHashMap<>();

    public MinecraftBlockStateDecoder(HolderLookup<Block> blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    public DecodedSection decode(SectionBlob source) throws IOException {
        DecodedPayload decoded = decodePayload(source);
        return new DecodedSection(decoded.states(), decoded.blockEntities());
    }

    DecodedSection decodeAgainst(SectionBlob source, SectionBlob before)
            throws IOException {
        DecodedPayload target = decodePayload(source);
        validateBlockEntities(before);
        return new DecodedSection(target.states(), target.blockEntities())
                .prepareAgainst(source, before, this);
    }

    private DecodedPayload decodePayload(SectionBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        var states = new ArrayList<BlockState>(SectionBlob.BLOCK_COUNT);
        for (String encoded : source.blockStates()) {
            states.add(decodeState(encoded));
        }
        return new DecodedPayload(states, decodeBlockEntities(source));
    }

    private static HashMap<Integer, net.minecraft.nbt.CompoundTag> decodeBlockEntities(
            SectionBlob source) throws IOException {
        var blockEntities = new HashMap<Integer, net.minecraft.nbt.CompoundTag>();
        for (var entry : source.blockEntities().entrySet()) {
            var decoded = MinecraftNbtCodec.decode(entry.getValue());
            validateBlockEntityType(decoded);
            blockEntities.put(entry.getKey(), decoded);
        }
        return blockEntities;
    }

    /** Validates persistent types without allocating a native section container. */
    public void validate(SectionBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        for (String encoded : new HashSet<>(source.blockStates())) {
            decodeState(encoded);
        }
        validateBlockEntities(source);
    }

    private static void validateBlockEntities(SectionBlob source) throws IOException {
        for (var encoded : source.blockEntities().values()) {
            validateBlockEntityType(MinecraftNbtCodec.decode(encoded));
        }
    }

    private static void validateBlockEntityType(
            net.minecraft.nbt.CompoundTag blockEntity) throws IOException {
        String encoded = blockEntity.getStringOr("id", "");
        Identifier id = Identifier.tryParse(encoded);
        if (id == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id)) {
            throw new IOException("Unknown persistent block entity type: " + encoded);
        }
    }

    BlockState decodeState(String encoded) throws IOException {
        BlockState cached = decodedStates.get(encoded);
        if (cached != null) {
            return cached;
        }
        try {
            BlockState decoded = BlockStateParser.parseForBlock(
                    blocks, encoded, false).blockState();
            BlockState raced = decodedStates.putIfAbsent(encoded, decoded);
            return raced == null ? decoded : raced;
        } catch (CommandSyntaxException invalid) {
            throw new IOException("Invalid persistent block state: " + encoded, invalid);
        }
    }

    int cachedStateCount() {
        return decodedStates.size();
    }

    private record DecodedPayload(
            java.util.List<BlockState> states,
            java.util.Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities) { }
}
