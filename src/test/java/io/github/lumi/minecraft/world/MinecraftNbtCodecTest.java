package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.CanonicalNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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

    @Test
    void canonicalizesAndRoundTripsHeterogeneousLists() throws Exception {
        CompoundTag firstNested = new CompoundTag();
        firstNested.putInt("z", 9);
        firstNested.putString("a", "first");
        CompoundTag wrapperShaped = new CompoundTag();
        wrapperShaped.putString("", "literal empty key");
        ListTag firstList = new ListTag();
        firstList.add(StringTag.valueOf("text"));
        firstList.add(IntTag.valueOf(7));
        firstList.add(firstNested);
        firstList.add(wrapperShaped);
        CompoundTag first = new CompoundTag();
        first.put("mixed", firstList);

        CompoundTag secondNested = new CompoundTag();
        secondNested.putString("a", "first");
        secondNested.putInt("z", 9);
        ListTag secondList = new ListTag();
        secondList.add(StringTag.valueOf("text"));
        secondList.add(IntTag.valueOf(7));
        secondList.add(secondNested);
        secondList.add(wrapperShaped.copy());
        CompoundTag second = new CompoundTag();
        second.put("mixed", secondList);

        CanonicalNbt firstBytes = MinecraftNbtCodec.encode(first);
        CanonicalNbt secondBytes = MinecraftNbtCodec.encode(second);

        assertArrayEquals(firstBytes.bytes(), secondBytes.bytes());
        assertEquals(first, MinecraftNbtCodec.decode(firstBytes));
    }
}
