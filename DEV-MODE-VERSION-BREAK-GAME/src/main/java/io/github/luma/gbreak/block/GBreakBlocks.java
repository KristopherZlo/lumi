package io.github.luma.gbreak.block;

import io.github.luma.gbreak.GBreakDevMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class GBreakBlocks {

    private static final Identifier MISSING_TEXTURE_ID = Identifier.of(GBreakDevMod.MOD_ID, "missing_texture");
    private static final RegistryKey<Block> MISSING_TEXTURE_KEY = RegistryKey.of(RegistryKeys.BLOCK, MISSING_TEXTURE_ID);

    public static final Block MISSING_TEXTURE = new Block(AbstractBlock.Settings.create()
            .registryKey(MISSING_TEXTURE_KEY)
            .mapColor(MapColor.MAGENTA)
            .strength(-1.0F, 3600000.0F)
            .sounds(BlockSoundGroup.STONE)
            .dropsNothing()
            .nonOpaque()
            .noCollision());

    private GBreakBlocks() {
    }

    public static void register() {
        Registry.register(Registries.BLOCK, MISSING_TEXTURE_KEY, MISSING_TEXTURE);
    }
}
