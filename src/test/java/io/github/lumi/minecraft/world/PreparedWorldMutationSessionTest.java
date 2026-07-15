package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreparedWorldMutationSessionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void appliesBlocksIncrementallyThenVerifiesExactSection() throws Exception {
        SectionKey key = new SectionKey(1, 2, 3);
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        PreparedMinecraftState target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(key, source), Map.of()),
                Map.of(key, decoded), Map.of());
        AtomicLong clock = new AtomicLong();
        FakeWorld world = new FakeWorld(clock, source);
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, clock::get);

        assertFalse(session.applyUntil(3));
        assertEquals(3, world.blockWrites);
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(SectionBlob.BLOCK_COUNT, world.blockWrites);
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
    }

    private static final class FakeWorld implements PreparedWorldAccess {
        private final AtomicLong clock;
        private final SectionBlob captured;
        private int blockWrites;

        private FakeWorld(AtomicLong clock, SectionBlob captured) {
            this.clock = clock;
            this.captured = captured;
        }

        @Override public void setBlock(
                SectionKey key, int localIndex,
                net.minecraft.world.level.block.state.BlockState state) {
            blockWrites++;
            clock.incrementAndGet();
        }
        @Override public List<Integer> blockEntityIndexes(SectionKey key) { return List.of(); }
        @Override public void removeBlockEntity(SectionKey key, int localIndex) { }
        @Override public void loadBlockEntity(
                SectionKey key, int localIndex, net.minecraft.nbt.CompoundTag nbt) { }
        @Override public SectionBlob captureSection(SectionKey key) { return captured; }
    }
}
