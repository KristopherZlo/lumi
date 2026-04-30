package io.github.luma.minecraft.world;

import java.util.BitSet;
import java.util.function.IntConsumer;

public final class SectionChangeMask {

    public static final int ENTRY_COUNT = 16 * 16 * 16;
    public static final int WORD_COUNT = ENTRY_COUNT / Long.SIZE;

    private final long[] words;
    private final int cardinality;

    public SectionChangeMask(long[] words) {
        this.words = normalize(words);
        this.cardinality = count(this.words);
    }

    public static SectionChangeMask empty() {
        return new SectionChangeMask(new long[WORD_COUNT]);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static int localIndex(int localX, int localY, int localZ) {
        return ((localY & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
    }

    public static int localX(int localIndex) {
        return localIndex & 15;
    }

    public static int localY(int localIndex) {
        return (localIndex >>> 8) & 15;
    }

    public static int localZ(int localIndex) {
        return (localIndex >>> 4) & 15;
    }

    public boolean isEmpty() {
        return this.cardinality == 0;
    }

    public int cardinality() {
        return this.cardinality;
    }

    public boolean contains(int localIndex) {
        if (localIndex < 0 || localIndex >= ENTRY_COUNT) {
            return false;
        }
        return (this.words[localIndex >>> 6] & (1L << (localIndex & 63))) != 0L;
    }

    public long[] words() {
        return this.words.clone();
    }

    public BitSet toBitSet() {
        BitSet bitSet = new BitSet(ENTRY_COUNT);
        this.forEachSetCell(bitSet::set);
        return bitSet;
    }

    public void forEachSetCell(IntConsumer consumer) {
        if (consumer == null) {
            return;
        }
        for (int wordIndex = 0; wordIndex < this.words.length; wordIndex++) {
            long word = this.words[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                consumer.accept((wordIndex << 6) + bit);
                word &= word - 1L;
            }
        }
    }

    private static long[] normalize(long[] words) {
        long[] normalized = new long[WORD_COUNT];
        if (words != null) {
            System.arraycopy(words, 0, normalized, 0, Math.min(words.length, normalized.length));
        }
        return normalized;
    }

    private static int count(long[] words) {
        int total = 0;
        for (long word : words) {
            total += Long.bitCount(word);
        }
        return total;
    }

    public static final class Builder {

        private final long[] words = new long[WORD_COUNT];

        public Builder set(int localIndex) {
            if (localIndex < 0 || localIndex >= ENTRY_COUNT) {
                throw new IllegalArgumentException("localIndex out of section range: " + localIndex);
            }
            this.words[localIndex >>> 6] |= 1L << (localIndex & 63);
            return this;
        }

        public Builder set(int localX, int localY, int localZ) {
            return this.set(localIndex(localX, localY, localZ));
        }

        public SectionChangeMask build() {
            return new SectionChangeMask(this.words);
        }
    }
}
