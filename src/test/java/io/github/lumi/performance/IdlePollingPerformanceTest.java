package io.github.lumi.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.domain.service.WorkspaceService;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorkspaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("performance")
class IdlePollingPerformanceTest {
    @TempDir Path root;

    @Test
    void measuresFreshAndCachedPollingAndRequiresZeroCachedFileReads() throws Exception {
        var service = new WorkspaceService(new WorkspaceRepository(root),
                new ActiveWorkspaceRepository(root), new CommitRepository(root),
                new BranchRefRepository(root));
        service.initializeDefault(new UUID(0, 1));
        WorkspaceSettings expected = service.active().settings();
        int polls = 1_000;
        var fresh = new PerformanceSamples("idle-fresh-metadata", polls);
        var cached = new PerformanceSamples("idle-cached-settings", polls);
        poll(service, expected, true, polls); // Both paths warm before paired measurements.
        poll(service, expected, false, polls);
        for (int sample = 0; sample < 9; sample++) {
            fresh.measure(() -> poll(service, expected, true, polls));
            cached.measure(() -> poll(service, expected, false, polls));
        }
        fresh.write();
        cached.write();
        assertTrue(cached.percentile(0.50) < fresh.percentile(0.50),
                "Cached polling must cost less than reading and decoding both metadata files");

        long freshReads = fileReads(service, expected, true);
        long cachedReads = fileReads(service, expected, false);
        Files.writeString(PerformanceSamples.reportPath("idle-file-reads.csv"),
                "path,polls,file_reads\nfresh,20," + freshReads + "\ncached,20," + cachedReads + "\n");
        assertTrue(freshReads > 0, "The recording must observe the fresh-read control");
        assertEquals(0, cachedReads, "Idle settings polling must not touch repository files");
    }

    private long fileReads(WorkspaceService service, WorkspaceSettings expected, boolean fresh)
            throws Exception {
        Path destination = PerformanceSamples.reportPath(fresh ? "idle-fresh.jfr" : "idle-cached.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            recording.start();
            poll(service, expected, fresh, 20);
            recording.stop();
            recording.dump(destination);
        }
        return RecordingFile.readAllEvents(destination).stream()
                .filter(event -> event.getEventType().getName().equals("jdk.FileRead"))
                .filter(event -> Path.of(event.getString("path")).toAbsolutePath().startsWith(root))
                .count();
    }

    private static long poll(WorkspaceService service, WorkspaceSettings expected,
            boolean fresh, int count) throws Exception {
        long matched = 0;
        for (int tick = 0; tick < count; tick++) {
            WorkspaceSettings value = fresh ? service.active().settings() : service.cachedActiveSettings();
            if (value.equals(expected)) matched++;
        }
        assertEquals(count, matched);
        return matched;
    }
}
