package io.github.lumi.gametest;

import java.util.Locale;

/** Selects focused client GameTests without changing their production paths. */
enum LumiClientTestSuite {
    ALL,
    SMOKE,
    UI,
    RECOVERY,
    BENCHMARK;

    private static final String PROPERTY = "lumi.gametest.suite";

    static boolean includes(LumiClientTestSuite suite) {
        LumiClientTestSuite selected = selected();
        return selected == ALL || selected == suite;
    }

    private static LumiClientTestSuite selected() {
        String configured = System.getProperty(PROPERTY, "").trim();
        if (configured.isEmpty()) {
            return Boolean.getBoolean("lumi.gametest.firstMinuteOnly")
                    ? SMOKE : ALL;
        }
        try {
            return valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failed) {
            throw new IllegalArgumentException(
                    "Unknown Lumi client GameTest suite: " + configured, failed);
        }
    }
}
