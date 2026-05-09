package io.github.luma.minecraft.bootstrap;

public record WorldInitialBackupIdentity(String levelName, long seed) {

    public WorldInitialBackupIdentity {
        levelName = levelName == null ? "" : levelName;
    }
}
