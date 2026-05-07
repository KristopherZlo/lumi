package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UndoRedoActionGroupingPolicyTest {

    private final UndoRedoActionGroupingPolicy policy = new UndoRedoActionGroupingPolicy();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void axiomSimplePlaceKeepsBatchActionId() {
        StoredBlockChange first = placeChange(new BlockPoint(1, 64, 1));
        StoredBlockChange second = placeChange(new BlockPoint(2, 64, 1));

        String firstId = this.policy.actionIdForBlockChange(WorldMutationSource.AXIOM, "axiom-buffer", first);
        String secondId = this.policy.actionIdForBlockChange(WorldMutationSource.AXIOM, "axiom-buffer", second);

        assertEquals("axiom-buffer", firstId);
        assertEquals("axiom-buffer", secondId);
    }

    @Test
    void axiomSimpleBreakKeepsBatchActionId() {
        StoredBlockChange change = new StoredBlockChange(
                new BlockPoint(4, 64, 1),
                StatePayload.capture(Blocks.STONE.defaultBlockState(), null),
                StatePayload.air()
        );

        assertEquals(
                "axiom-buffer",
                this.policy.actionIdForBlockChange(WorldMutationSource.AXIOM, "axiom-buffer", change)
        );
    }

    @Test
    void nonAxiomPlayerActionKeepsOriginalActionId() {
        StoredBlockChange change = placeChange(new BlockPoint(1, 64, 1));

        assertEquals(
                "player-action",
                this.policy.actionIdForBlockChange(WorldMutationSource.PLAYER, "player-action", change)
        );
    }

    @Test
    void axiomReplacementKeepsBatchActionId() {
        StoredBlockChange change = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                StatePayload.capture(Blocks.STONE.defaultBlockState(), null),
                StatePayload.capture(Blocks.DIRT.defaultBlockState(), null)
        );

        assertEquals(
                "axiom-replace",
                this.policy.actionIdForBlockChange(WorldMutationSource.AXIOM, "axiom-replace", change)
        );
    }

    private static StoredBlockChange placeChange(BlockPoint pos) {
        return new StoredBlockChange(
                pos,
                StatePayload.air(),
                StatePayload.capture(Blocks.STONE.defaultBlockState(), null)
        );
    }
}
