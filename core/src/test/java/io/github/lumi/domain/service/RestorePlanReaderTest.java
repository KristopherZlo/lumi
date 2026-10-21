package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestorePlanReaderTest {
    @TempDir
    Path repository;

    @Test
    void reusesDecodedPayloadByObjectId() throws IOException {
        var objects = new WorldObjectRepository(repository);
        ObjectId id = objects.write(section(0));
        try (var reader = new RestorePlanReader(objects)) {
            SectionBlob first = reader.readSection(id);

            assertSame(first, reader.readSection(id));
            assertEquals(1, reader.cachedSectionCount());
        }
    }

    @Test
    void evictsLeastRecentlyUsedPayloadAtTheBound() throws IOException {
        var objects = new WorldObjectRepository(repository);
        List<ObjectId> ids = new ArrayList<>();
        for (int index = 0; index <= RestorePlanReader.MAX_CACHED_SECTIONS; index++) {
            ids.add(objects.write(section(index)));
        }
        try (var reader = new RestorePlanReader(objects)) {
            List<SectionBlob> decoded = new ArrayList<>();
            for (ObjectId id : ids.subList(0, RestorePlanReader.MAX_CACHED_SECTIONS)) {
                decoded.add(reader.readSection(id));
            }
            reader.readSection(ids.getFirst());
            reader.readSection(ids.getLast());

            assertSame(decoded.getFirst(), reader.readSection(ids.getFirst()));
            assertNotSame(decoded.get(1), reader.readSection(ids.get(1)));
            assertEquals(RestorePlanReader.MAX_CACHED_SECTIONS,
                    reader.cachedSectionCount());
        }
    }

    @Test
    void closeIsIdempotentAndPreventsReopeningTheSession() throws IOException {
        var objects = new WorldObjectRepository(repository);
        ObjectId id = objects.write(section(0));
        var reader = new RestorePlanReader(objects);

        reader.close();
        reader.close();

        assertThrows(IOException.class, () -> reader.readSection(id));
    }

    @Test
    void concurrentPlanConsumersShareOneSerializedSession() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var reads = new AtomicInteger();
        SectionBlob expected = section(0);
        try (var reader = new RestorePlanReader(id -> {
            if (reads.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for concurrent consumer");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            return expected;
        }, () -> { }); var executor = Executors.newFixedThreadPool(2)) {
            ObjectId id = new ObjectId("0".repeat(64));
            var first = executor.submit(() -> reader.readSection(id));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return reader.readSection(id);
                });
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertSame(expected, first.get(1, TimeUnit.SECONDS));
                assertSame(expected, second.get(1, TimeUnit.SECONDS));
                assertEquals(1, reads.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closeDefersResourceReleaseWithoutWaitingForAnActiveRead() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resourceClosed = new AtomicBoolean();
        SectionBlob expected = section(0);
        var reader = new RestorePlanReader(
                ignored -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IOException(
                                    "Timed out waiting to finish the test read");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted test read", interrupted);
                    }
                    return expected;
                },
                () -> resourceClosed.set(true));
        ObjectId id = new ObjectId("0".repeat(64));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reading = executor.submit(() -> reader.readSection(id));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var closing = executor.submit(() -> {
                reader.close();
                return null;
            });
            try {
                closing.get(1, TimeUnit.SECONDS);
                assertFalse(resourceClosed.get());
            } finally {
                release.countDown();
            }
            assertSame(expected, reading.get(1, TimeUnit.SECONDS));
        }
        assertTrue(resourceClosed.get());
        assertThrows(IOException.class, () -> reader.readSection(id));
    }

    private static SectionBlob section(int marker) {
        List<String> states = new ArrayList<>(
                java.util.Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air"));
        states.set(0, "marker:" + marker);
        return new SectionBlob(states, Map.of());
    }
}
