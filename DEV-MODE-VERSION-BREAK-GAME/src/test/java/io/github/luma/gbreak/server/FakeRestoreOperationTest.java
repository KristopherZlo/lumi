package io.github.luma.gbreak.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class FakeRestoreOperationTest {

    @Test
    void advanceCapsProgressAtOperationTotal() {
        FakeRestoreOperation operation = new FakeRestoreOperation(
                UUID.randomUUID(),
                net.minecraft.registry.RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                BlockPos.ORIGIN,
                "a3f91d2",
                8,
                6
        );

        FakeRestoreOperation advanced = operation.advance(4);

        assertEquals(8, advanced.appliedReplacements());
        assertEquals(100, advanced.progressPercent());
        assertTrue(advanced.complete());
    }
}
