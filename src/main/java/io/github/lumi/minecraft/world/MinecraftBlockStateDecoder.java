package io.github.lumi.minecraft.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
        return DecodedSection.fromPalette(
                decoded.palette(), decoded.indexes(), decoded.blockEntities());
    }

    DecodedSection decodeAgainst(SectionBlob source, SectionBlob before)
            throws IOException {
        DecodedPayload target = decodePayload(source);
        return DecodedSection.fromPalette(
                target.palette(), target.indexes(), target.blockEntities())
                .prepareAgainst(source, before, this);
    }

    private DecodedPayload decodePayload(SectionBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        List<BlockState> palette = decodePalette(source);
        return new DecodedPayload(
                palette, source.palette().copyIndexes(),
                decodeBlockEntities(source, palette));
    }

    private HashMap<Integer, CompoundTag> decodeBlockEntities(
            SectionBlob source, java.util.List<BlockState> palette) throws IOException {
        var blockEntities = new HashMap<Integer, CompoundTag>();
        for (var entry : source.blockEntities().entrySet()) {
            var decoded = MinecraftNbtCodec.decode(entry.getValue());
            validateBlockEntityState(
                    palette.get(source.palette().paletteIndex(entry.getKey())),
                    decoded);
            blockEntities.put(entry.getKey(), decoded);
        }
        return blockEntities;
    }

    /** Validates persistent types without allocating a native section container. */
    public void validate(SectionBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        List<BlockState> palette = decodePalette(source);
        validateBlockEntities(source, palette);
    }

    void validateBlockEntities(
            SectionBlob source, List<BlockState> palette) throws IOException {
        for (var entry : source.blockEntities().entrySet()) {
            validateBlockEntityState(
                    palette.get(source.palette().paletteIndex(entry.getKey())),
                    MinecraftNbtCodec.decode(entry.getValue()));
        }
    }

    List<BlockState> decodePalette(SectionBlob source) throws IOException {
        var decoded = new ArrayList<BlockState>(source.palette().palette().size());
        for (String encoded : source.palette().palette()) {
            decoded.add(decodeState(encoded));
        }
        return List.copyOf(decoded);
    }

    void validateBlockEntityState(
            BlockState state, CompoundTag blockEntity) throws IOException {
        BlockEntityType<?> type = blockEntityType(blockEntity);
        if (!type.isValid(Objects.requireNonNull(state, "state"))) {
            throw new IOException("Persistent block entity "
                    + blockEntity.getStringOr("id", "")
                    + " is incompatible with " + state);
        }
    }

    private static BlockEntityType<?> blockEntityType(
            CompoundTag blockEntity) throws IOException {
        String encoded = blockEntity.getStringOr("id", "");
        Identifier id = Identifier.tryParse(encoded);
        BlockEntityType<?> type = id == null ? null
                : BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            throw new IOException("Unknown persistent block entity type: " + encoded);
        }
        return type;
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
            java.util.List<BlockState> palette,
            short[] indexes,
            java.util.Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities) { }
}
