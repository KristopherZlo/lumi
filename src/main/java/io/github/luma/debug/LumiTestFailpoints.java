package io.github.luma.debug;

import io.github.luma.LumaMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicitly opt-in failpoints for alpha crash-safety harnesses.
 *
 * <p>Failpoints are inert unless `LUMI_TEST_FAILPOINT_ENABLED=true` or
 * `-Dlumi.test.failpoint.enabled=true` is set.
 */
public final class LumiTestFailpoints {

    public static final String BEFORE_DRAFT_FREEZE = "before-draft-freeze";
    public static final String AFTER_DRAFT_FREEZE = "after-draft-freeze";
    public static final String AFTER_OPERATION_DRAFT_WRITE = "after-operation-draft-write";
    public static final String AFTER_PATCH_DATA_WRITE = "after-patch-data-write";
    public static final String BEFORE_VERSION_MANIFEST_WRITE = "before-version-manifest-write";
    public static final String BEFORE_VARIANT_METADATA_WRITE = "before-variant-metadata-write";
    public static final String BEFORE_RESTORE_METADATA_WRITE = "before-restore-metadata-write";
    public static final String MID_WORLD_OPERATION_APPLY = "mid-world-operation-apply";
    public static final String LIGHT_REFRESH_DRAIN_START = "light-refresh-drain-start";

    private static final boolean ENABLED = booleanValue(
            "lumi.test.failpoint.enabled",
            "LUMI_TEST_FAILPOINT_ENABLED",
            false
    );
    private static final Set<String> FIRED = ConcurrentHashMap.newKeySet();
    private static volatile Boolean enabledOverrideForTests;

    private LumiTestFailpoints() {
    }

    public static void hit(String name) {
        if (!enabled() || name == null || name.isBlank() || !selected(name)) {
            return;
        }
        if (once() && !FIRED.add(name)) {
            return;
        }

        String message = "LUMI TEST FAILPOINT HIT: " + name;
        LumaMod.LOGGER.warn(message);
        writeMarker(name, message);
        switch (action()) {
            case "throw" -> throw new IllegalStateException(message);
            case "halt" -> Runtime.getRuntime().halt(exitCode());
            case "sleep" -> sleep(name);
            default -> {
            }
        }
    }

    public static void clearForTests() {
        FIRED.clear();
        enabledOverrideForTests = null;
    }

    static void setEnabledForTests(boolean enabled) {
        enabledOverrideForTests = enabled;
    }

    private static boolean enabled() {
        Boolean override = enabledOverrideForTests;
        return override == null ? ENABLED : override;
    }

    private static boolean once() {
        return booleanValue("lumi.test.failpoint.once", "LUMI_TEST_FAILPOINT_ONCE", true);
    }

    private static boolean selected(String name) {
        String selected = value("lumi.test.failpoint", "LUMI_TEST_FAILPOINT", "");
        if (selected.isBlank()) {
            return false;
        }
        return Arrays.stream(selected.split(","))
                .map(String::trim)
                .anyMatch(candidate -> candidate.equals(name) || candidate.equals("*"));
    }

    private static String action() {
        return value("lumi.test.failpoint.action", "LUMI_TEST_FAILPOINT_ACTION", "throw")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static int exitCode() {
        String value = value("lumi.test.failpoint.exitCode", "LUMI_TEST_FAILPOINT_EXIT_CODE", "97");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 97;
        }
    }

    private static void sleep(String name) {
        long millis = longValue("lumi.test.failpoint.sleepMillis", "LUMI_TEST_FAILPOINT_SLEEP_MILLIS", 60_000L);
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted at Lumi test failpoint " + name, exception);
        }
    }

    private static void writeMarker(String name, String message) {
        String marker = value("lumi.test.failpoint.marker", "LUMI_TEST_FAILPOINT_MARKER", "");
        if (marker.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(marker);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, message + System.lineSeparator() + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to write Lumi test failpoint marker for {}", name, exception);
        }
    }

    private static boolean booleanValue(String property, String environment, boolean fallback) {
        String value = value(property, environment, Boolean.toString(fallback));
        return Boolean.parseBoolean(value);
    }

    private static long longValue(String property, String environment, long fallback) {
        String value = value(property, environment, Long.toString(fallback));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String value(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null ? fallback : value;
    }
}
