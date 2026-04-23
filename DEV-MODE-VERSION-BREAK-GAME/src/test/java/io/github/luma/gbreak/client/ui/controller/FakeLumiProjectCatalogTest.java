package io.github.luma.gbreak.client.ui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FakeLumiProjectCatalogTest {

    private final FakeLumiProjectCatalog catalog = new FakeLumiProjectCatalog();

    @Test
    void defaultCommitsExposeTwoFakeEntries() {
        assertEquals(2, this.catalog.defaultCommits().size());
        assertTrue(this.catalog.defaultCommits().getFirst().latest());
    }
}
