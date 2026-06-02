package io.github.luma.mixin;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TntBlockMixinTest {

    @Test
    void wrapsWasExplodedForChainedTntCausality() {
        assertTrue(
                Arrays.stream(TntBlockMixin.class.getDeclaredMethods())
                        .anyMatch(method -> "luma$wrapWasExploded".equals(method.getName())),
                "TntBlockMixin must wrap TntBlock.wasExploded so TNT chains inherit the original action"
        );
    }
}
