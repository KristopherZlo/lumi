package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.lumi.domain.model.WorldDataVersion;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MinecraftHistoryStartupTest {
    @Test
    void permitsOnlyCertifiedForwardPairs() {
        var routes = Map.of(4786, Set.of(4671), 4788, Set.of(4671, 4786),
                4790, Set.of(4671, 4786, 4788), 4903, Set.of(4671), 5023, Set.of(4671));
        for (var entry : routes.entrySet()) {
            var target = new WorldDataVersion(entry.getKey());
            var permitted = MinecraftHistoryStartup.permittedSources(target);
            assertEquals(entry.getValue(), permitted.stream().map(WorldDataVersion::value)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            assertFalse(permitted.contains(target));
        }
        assertEquals(Set.of(), MinecraftHistoryStartup.permittedSources(new WorldDataVersion(4671)));
        assertEquals(Set.of(), MinecraftHistoryStartup.permittedSources(new WorldDataVersion(5024)));
    }
}
