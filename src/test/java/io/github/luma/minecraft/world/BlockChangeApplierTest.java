package io.github.luma.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockChangeApplierTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void entityApplyFailureIsNotReportedAsProcessed() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        EntityBatch batch = EntityBatch.replaceEntities(List.of(entity));

        assertThrows(IllegalStateException.class, () -> BlockChangeApplier.applyEntityBatch(
                null, new ChunkPoint(0, 0), batch, 1, 1, null, null
        ));
    }
}
