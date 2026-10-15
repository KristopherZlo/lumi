package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.service.HistoryGenerationConversionService.Phase;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.HistoryGenerationRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HistoryGenerationProcessCrashTest {
    @TempDir Path root;

    @ParameterizedTest
    @EnumSource(Phase.class)
    void killedJvmRetainsOriginalHistoryAndRestartCompletesExactlyOneGeneration(Phase phase) throws Exception {
        var fixture = HistoryGenerationConversionServiceTest.fixture(root);
        assertEquals(86, launch(phase.name()));
        assertEquals(phase.name(), Files.readString(root.resolve("crash-cut.txt")));
        var restarted = new HistoryGenerationRepository(root);
        if (phase == Phase.PUBLISHED || phase == Phase.COMPLETED) {
            assertEquals(new WorldDataVersion(4786), restarted.active().dataVersion());
        } else {
            assertEquals(HistoryGeneration.LEGACY, restarted.active());
        }
        assertEquals(fixture.main(), new BranchRefRepository(root).read(fixture.main().name()).orElseThrow());
        assertEquals(0, launch("resume"));
        var generation = new HistoryGenerationRepository(root).active();
        assertEquals(new WorldDataVersion(4786), generation.dataVersion());
        assertTrue(new HistoryGenerationRepository(root).pending().isEmpty());
        Path target = restarted.directory(generation);
        assertEquals(2, new BranchRefRepository(target).list().size());
        assertEquals(1, new TombstoneRepository(target).list().size());
        assertEquals(new WorkingIndexRepository(root).readState(), new WorkingIndexRepository(target).readState());
        try (var directories = Files.list(root.resolve("generations"))) {
            assertEquals(1, directories.filter(Files::isDirectory).count());
        }
    }

    private int launch(String phase) throws Exception {
        return launch(phase, 4786);
    }

    @Test
    void nextGameUpgradeCompletesAfterThePreviousProcessDiedAtPublication() throws Exception {
        var fixture = HistoryGenerationConversionServiceTest.fixture(root);
        assertEquals(86, launch(Phase.PUBLISHED.name()));
        var generations = new HistoryGenerationRepository(root);
        assertEquals(new WorldDataVersion(4786), generations.active().dataVersion());
        assertTrue(generations.pending().isPresent());
        assertEquals(0, launch("resume", 4903));
        assertEquals(new WorldDataVersion(4903), generations.active().dataVersion());
        assertTrue(generations.pending().isEmpty());
        assertEquals(fixture.main(), new BranchRefRepository(root).read(fixture.main().name()).orElseThrow());
    }

    private int launch(String phase, int targetVersion) throws Exception {
        Path log = root.resolve("process-" + phase + ".log");
        String classpath = System.getProperty("lumi.test.runtime.classpath");
        assertNotNull(classpath, "Gradle must supply the child JVM's test classpath");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", classpath, HistoryConversionCrashWorker.class.getName(), root.toString(), phase,
                Integer.toString(targetVersion))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Conversion process timed out: " + phase);
            int result = child.exitValue();
            assertTrue(result == 0 || result == 86, "Child JVM failed: " + Files.readString(log));
            return result;
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
