package io.github.luma.debug;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiTestFailpointsTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty("lumi.test.failpoint.enabled");
        System.clearProperty("lumi.test.failpoint");
        System.clearProperty("lumi.test.failpoint.action");
        System.clearProperty("lumi.test.failpoint.marker");
        System.clearProperty("lumi.test.failpoint.once");
        LumiTestFailpoints.clearForTests();
    }

    @Test
    void disabledFailpointDoesNothing() {
        LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_OPERATION_DRAFT_WRITE);
    }

    @Test
    void selectedFailpointWritesMarkerAndThrows() {
        Path marker = this.tempDir.resolve("failpoint.marker");
        LumiTestFailpoints.setEnabledForTests(true);
        System.setProperty("lumi.test.failpoint", LumiTestFailpoints.AFTER_OPERATION_DRAFT_WRITE);
        System.setProperty("lumi.test.failpoint.action", "throw");
        System.setProperty("lumi.test.failpoint.marker", marker.toString());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_OPERATION_DRAFT_WRITE));

        assertTrue(exception.getMessage().contains(LumiTestFailpoints.AFTER_OPERATION_DRAFT_WRITE));
        assertTrue(Files.exists(marker));
    }

    @Test
    void onceOnlySuppressesRepeatedHits() {
        LumiTestFailpoints.setEnabledForTests(true);
        System.setProperty("lumi.test.failpoint", LumiTestFailpoints.MID_WORLD_OPERATION_APPLY);
        System.setProperty("lumi.test.failpoint.action", "throw");

        assertThrows(IllegalStateException.class, () ->
                LumiTestFailpoints.hit(LumiTestFailpoints.MID_WORLD_OPERATION_APPLY));
        LumiTestFailpoints.hit(LumiTestFailpoints.MID_WORLD_OPERATION_APPLY);
    }

    @Test
    void unselectedFailpointDoesNothingWhenEnabled() {
        LumiTestFailpoints.setEnabledForTests(true);
        System.setProperty("lumi.test.failpoint", LumiTestFailpoints.AFTER_PATCH_DATA_WRITE);
        System.setProperty("lumi.test.failpoint.action", "throw");

        LumiTestFailpoints.hit(LumiTestFailpoints.MID_WORLD_OPERATION_APPLY);
    }

    @Test
    void sleepActionUsesConfiguredShortDelay() {
        LumiTestFailpoints.setEnabledForTests(true);
        System.setProperty("lumi.test.failpoint", "*");
        System.setProperty("lumi.test.failpoint.action", "sleep");
        System.setProperty("lumi.test.failpoint.sleepMillis", "1");

        LumiTestFailpoints.hit(LumiTestFailpoints.LIGHT_REFRESH_DRAIN_START);
    }
}
