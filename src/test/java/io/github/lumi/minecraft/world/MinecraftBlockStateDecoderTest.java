package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftBlockStateDecoderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void decodesEveryBlockAndBlockEntityNbtBeforeApply() throws Exception {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(17, "minecraft:oak_log[axis=x]");
        var source = new SectionBlob(states, Map.of(
                17, MinecraftNbtCodec.encode(tag("custom", "value"))));

        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);

        assertEquals(Blocks.STONE.defaultBlockState(), decoded.blockStates().getFirst());
        assertEquals(Blocks.OAK_LOG.defaultBlockState().setValue(
                net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                net.minecraft.core.Direction.Axis.X), decoded.blockStates().get(17));
        assertEquals("value", decoded.blockEntities().get(17).getStringOr("custom", ""));
    }

    @Test
    void rejectsUnknownPersistentBlockStateDuringPreparation() {
        var states = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(0, "missing:not_a_block");

        assertThrows(IOException.class, () ->
                new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                        .decode(new SectionBlob(states, Map.of())));
    }

    private static net.minecraft.nbt.CompoundTag tag(String key, String value) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(key, value);
        return tag;
    }
}
