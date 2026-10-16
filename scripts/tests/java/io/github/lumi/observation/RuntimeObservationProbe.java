package io.github.lumi.observation;

import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone check: real sampler completion and rejection of paused measurements. */
public final class RuntimeObservationProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("lumi.observation.warmupSeconds", "1");
        System.setProperty("lumi.observation.seconds", "3");
        for (boolean paused : new boolean[] {false, true}) {
            var directory = Files.createTempDirectory("lumi-ordinary-observation-");
            System.setProperty("lumi.observation.output", directory.toString());
            var finished = new CountDownLatch(1);
            var observation = new RuntimeObservation(new Properties(),
                    () -> new RuntimeObservation.ClientState(60, true, paused), () -> 20,
                    finished::countDown);
            observation.start();
            if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Sampler did not finish");
            var metadata = new Properties();
            try (var reader = Files.newBufferedReader(directory.resolve("observation.properties"))) {
                metadata.load(reader);
            }
            String expected = paused ? "failed" : "passed";
            if (!expected.equals(metadata.getProperty("status"))) throw new AssertionError(metadata);
            if (Files.readAllLines(directory.resolve("samples.csv")).size() < 3) {
                throw new AssertionError("Missing raw warmup/measurement samples");
            }
            System.out.println("Ordinary sampler " + expected + ": " + directory);
        }
    }
}
