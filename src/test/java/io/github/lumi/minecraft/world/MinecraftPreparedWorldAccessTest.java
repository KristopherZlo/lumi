package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.PlayerSpawn;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MinecraftPreparedWorldAccessTest {
    @Test
    void treatsAbsentSavedSpawnAsExactState() {
        PlayerSpawn spawn = new PlayerSpawn(4, 70, -2, 90.0F, 0.0F, false);

        assertTrue(MinecraftPreparedWorldAccess.matchesSpawn(null, Optional.empty()));
        assertFalse(MinecraftPreparedWorldAccess.matchesSpawn(null, Optional.of(spawn)));
        assertTrue(MinecraftPreparedWorldAccess.matchesSpawn(spawn, Optional.of(spawn)));
    }
}
