package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SectionRewritePreflightTest {

    private final SectionRewritePreflight preflight = new SectionRewritePreflight(new PersistentBlockStatePolicy());

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectsTargetBlockEntities() {
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(0)
                .set(0, Blocks.CHEST.defaultBlockState(), null)
                .build();

        Assertions.assertEquals(
                BlockCommitFallbackReason.REWRITE_BLOCK_ENTITY,
                this.preflight.targetRejectionReason(buffer)
        );
    }

    @Test
    void rejectsTargetPoiStates() {
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(0)
                .set(0, Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), null)
                .build();

        Assertions.assertEquals(
                BlockCommitFallbackReason.REWRITE_POI,
                this.preflight.targetRejectionReason(buffer)
        );
    }

    @Test
    void acceptsSimpleSolidTargets() {
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(0)
                .set(0, Blocks.STONE.defaultBlockState(), null)
                .build();
        PreparedSectionApplyBatch batch = new PreparedSectionApplyBatch(
                new ChunkPoint(0, 0),
                0,
                buffer,
                SectionApplySafetyProfile.sectionRewrite("test"),
                false
        );

        Assertions.assertEquals(BlockCommitFallbackReason.NONE, this.preflight.rejectionReason(null, batch));
    }
}
