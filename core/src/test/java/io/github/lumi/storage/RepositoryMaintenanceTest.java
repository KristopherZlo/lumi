package io.github.lumi.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryMaintenanceTest {
    @TempDir Path root;

    @Test
    void sharesNormalizedRepositoryIdentityAndKeepsOtherDimensionsIndependent() {
        var maintenance = RepositoryMaintenance.forRepository(root);
        assertSame(maintenance, RepositoryMaintenance.forRepository(root.resolve("child/..")));
        var other = RepositoryMaintenance.forRepository(root.resolve("other"));
        var sweep = other.beginSweep();
        try (var publication = maintenance.beginPublication()) {
            assertTrue(sweep.isCurrent());
        }
    }

    @Test
    void completedPublicationPermanentlyInvalidatesAnEarlierSweep() throws Exception {
        var maintenance = RepositoryMaintenance.forRepository(root);
        var sweep = maintenance.beginSweep();
        try (var publication = maintenance.beginPublication()) {
            assertFalse(sweep.isCurrent());
        }
        assertFalse(sweep.deleteIfCurrent(() -> fail("Stale sweep must not delete")));
        assertTrue(maintenance.beginSweep().isCurrent());
    }

    @Test
    void sweepStartedDuringPublicationCannotBecomeValidAfterClose() {
        var maintenance = RepositoryMaintenance.forRepository(root);
        var publication = maintenance.beginPublication();
        var nested = maintenance.beginPublication();
        var sweep = maintenance.beginSweep();
        publication.close();
        publication.close();
        assertFalse(maintenance.beginSweep().isCurrent());
        nested.close();
        assertFalse(sweep.isCurrent());
        assertTrue(maintenance.beginSweep().isCurrent());
    }

    @Test
    void publicationCannotStartBetweenDeletingAPackIndexAndItsPayload() throws Exception {
        var maintenance = RepositoryMaintenance.forRepository(root);
        var sweep = maintenance.beginSweep();
        var deleting = new CountDownLatch(1);
        var releaseDeletion = new CountDownLatch(1);
        var attemptingPublication = new CountDownLatch(1);
        var publicationStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var deletion = workers.submit(() -> sweep.deleteIfCurrent(() -> {
                deleting.countDown();
                try {
                    assertTrue(releaseDeletion.await(5, TimeUnit.SECONDS));
                    assertEquals(1, publicationStarted.getCount());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
            }));
            assertTrue(deleting.await(5, TimeUnit.SECONDS));
            var publication = workers.submit(() -> {
                attemptingPublication.countDown();
                try (var lease = maintenance.beginPublication()) {
                    publicationStarted.countDown();
                }
            });
            assertTrue(attemptingPublication.await(5, TimeUnit.SECONDS));
            releaseDeletion.countDown();
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            publication.get(5, TimeUnit.SECONDS);
            assertFalse(sweep.isCurrent());
        } finally {
            releaseDeletion.countDown();
        }
    }
}
