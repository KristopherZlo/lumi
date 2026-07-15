package io.github.lumi.minecraft.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Converts persistent strings and canonical NBT into Minecraft-native apply data. */
public final class MinecraftBlockStateDecoder {
    private final HolderLookup<Block> blocks;

    public MinecraftBlockStateDecoder(HolderLookup<Block> blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    public DecodedSection decode(SectionBlob source) throws IOException {
        Objects.requireNonNull(source, "source");
        var states = new ArrayList<BlockState>(SectionBlob.BLOCK_COUNT);
        for (String encoded : source.blockStates()) {
            try {
                states.add(BlockStateParser.parseForBlock(blocks, encoded, false).blockState());
            } catch (CommandSyntaxException invalid) {
                throw new IOException("Invalid persistent block state: " + encoded, invalid);
            }
        }
        var blockEntities = new HashMap<Integer, net.minecraft.nbt.CompoundTag>();
        for (var entry : source.blockEntities().entrySet()) {
            blockEntities.put(entry.getKey(), MinecraftNbtCodec.decode(entry.getValue()));
        }
        return new DecodedSection(states, blockEntities);
    }
}
