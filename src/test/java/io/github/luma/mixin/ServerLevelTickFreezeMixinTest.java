package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLevelTickFreezeMixinTest {

    @Test
    void tntReplayFreezeDoesNotCancelOrdinaryServerLevelTick() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertFalse(mixins.contains("\"ServerLevelTickFreezeMixin\""));
        assertFalse(Files.exists(Path.of("src/main/java/io/github/luma/mixin/ServerLevelTickFreezeMixin.java")));
    }
}
