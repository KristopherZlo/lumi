package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.CanonicalNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class MinecraftNbtCodecTest {
    @Test
    void sortsEveryCompoundAndRoundTripsAllContainerLevels() throws Exception {
        CompoundTag first = new CompoundTag();
        first.putString("z", "last");
        first.putIntArray("numbers", new int[] {3, 1, 4});
        CompoundTag firstNested = new CompoundTag();
        firstNested.putLong("b", 2L);
        firstNested.putByte("a", (byte) 1);
        ListTag firstList = new ListTag();
        firstList.add(firstNested);
        first.put("list", firstList);

        CompoundTag second = new CompoundTag();
        ListTag secondList = new ListTag();
        CompoundTag secondNested = new CompoundTag();
        secondNested.putByte("a", (byte) 1);
        secondNested.putLong("b", 2L);
        secondList.add(secondNested);
        second.put("list", secondList);
        second.putIntArray("numbers", new int[] {3, 1, 4});
        second.putString("z", "last");

        CanonicalNbt firstBytes = MinecraftNbtCodec.encode(first);
        CanonicalNbt secondBytes = MinecraftNbtCodec.encode(second);

        assertArrayEquals(firstBytes.bytes(), secondBytes.bytes());
        assertEquals(first, MinecraftNbtCodec.decode(firstBytes));
    }
}
