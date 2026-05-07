package io.github.luma.minecraft.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HistoryDebugLogTest {

    private final HistoryDebugLog debugLog = new HistoryDebugLog();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tracesMechanismBlocksThatMatterForUndoRedoDiagnostics() {
        assertTrue(this.debugLog.shouldTrace(Blocks.STICKY_PISTON.defaultBlockState()));
        assertTrue(this.debugLog.shouldTrace(Blocks.OBSERVER.defaultBlockState()));
        assertTrue(this.debugLog.shouldTrace(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertTrue(this.debugLog.shouldTrace(Blocks.LEVER.defaultBlockState()));
    }

    @Test
    void skipsOrdinaryBlocksToKeepMechanismDiagnosticsFocused() {
        assertFalse(this.debugLog.shouldTrace(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void describesBlockStatesWithStableSortedProperties() {
        assertEquals(
                "minecraft:sticky_piston[extended=true,facing=up]",
                this.debugLog.describe(Blocks.STICKY_PISTON.defaultBlockState()
                        .setValue(PistonBaseBlock.EXTENDED, true)
                        .setValue(PistonBaseBlock.FACING, Direction.UP))
        );
    }

    @Test
    void describesEnumPropertiesWithMinecraftNames() {
        assertTrue(this.debugLog.describe(Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)).contains("face=floor"));
    }
}
