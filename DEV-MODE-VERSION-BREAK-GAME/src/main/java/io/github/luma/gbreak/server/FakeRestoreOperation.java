package io.github.luma.gbreak.server;

import java.util.UUID;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

record FakeRestoreOperation(
        UUID playerId,
        RegistryKey<World> worldKey,
        BlockPos center,
        String targetLabel,
        int totalReplacements,
        int appliedReplacements
) {

    FakeRestoreOperation advance(int replacedBlocks) {
        return new FakeRestoreOperation(
                this.playerId,
                this.worldKey,
                this.center,
                this.targetLabel,
                this.totalReplacements,
                Math.min(this.totalReplacements, this.appliedReplacements + replacedBlocks)
        );
    }

    boolean complete() {
        return this.appliedReplacements >= this.totalReplacements;
    }

    int progressPercent() {
        if (this.totalReplacements <= 0) {
            return 100;
        }
        return Math.min(100, (this.appliedReplacements * 100) / this.totalReplacements);
    }
}
