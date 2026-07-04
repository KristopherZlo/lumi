package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLevelTickFreezeMixinTest {

    @Test
    void frozenReplayWorldSkipsOrdinaryServerLevelTick() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelTickFreezeMixin.java"));
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertTrue(mixins.contains("\"ServerLevelTickFreezeMixin\""));
        assertTrue(source.contains("@Mixin(ServerLevel.class)"));
        assertTrue(source.contains("@Inject(method = \"tick\""));
        assertTrue(source.contains("LUMA_REPLAY_TICK_SUPPRESSION.shouldFreezeWorldTick"));
        assertTrue(source.contains("callback.cancel();"));
    }
}
