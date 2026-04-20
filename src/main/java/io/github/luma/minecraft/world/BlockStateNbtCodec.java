package io.github.luma.minecraft.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockStateNbtCodec {

    private BlockStateNbtCodec() {
    }

    public static CompoundTag serializeBlockStateTag(BlockState state) {
        return NbtUtils.writeBlockState(state);
    }

    public static String serializeBlockState(BlockState state) {
        return NbtUtils.structureToSnbt(serializeBlockStateTag(state));
    }

    public static BlockState deserializeBlockState(ServerLevel level, CompoundTag tag) throws IOException {
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        return NbtUtils.readBlockState(blocks, tag == null ? airTag() : tag);
    }

    public static BlockState deserializeBlockState(ServerLevel level, String snbt) throws IOException {
        try {
            CompoundTag tag = NbtUtils.snbtToStructure(snbt);
            return deserializeBlockState(level, tag);
        } catch (CommandSyntaxException exception) {
            throw new IOException("Failed to decode block state", exception);
        }
    }

    public static String serializeBlockEntity(CompoundTag tag) {
        return tag == null ? "" : NbtUtils.structureToSnbt(tag);
    }

    public static CompoundTag deserializeBlockEntity(String snbt) throws IOException {
        if (snbt == null || snbt.isBlank()) {
            return null;
        }

        try {
            return NbtUtils.snbtToStructure(snbt);
        } catch (CommandSyntaxException exception) {
            throw new IOException("Failed to decode block entity NBT", exception);
        }
    }

    private static CompoundTag airTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }
}
