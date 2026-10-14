package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.WorldDataCompatibility;
import java.io.IOException;
import net.minecraft.SharedConstants;

/** Reads the running game's version, including a newer hotfix in a shared artifact group. */
final class MinecraftWorldDataVersion {
    private MinecraftWorldDataVersion() { }

    static WorldDataVersion current() {
        return new WorldDataVersion(SharedConstants.getCurrentVersion().dataVersion().version());
    }

    static void requireCurrent(WorldDataVersion source) throws IOException {
        WorldDataCompatibility.requireCurrent(source, current());
    }
}
