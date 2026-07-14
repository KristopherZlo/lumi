package io.github.luma.gametest;

/** Selects one client GameTest suite for repeatable focused runs. */
final class ClientGameTestSuiteSelection {

    private static final String PROPERTY = "lumi.client.gametest.suite";

    private ClientGameTestSuiteSelection() {
    }

    static boolean includes(String suite) {
        String selected = System.getProperty(PROPERTY, "all").trim();
        if ("all".equals(selected)) {
            return true;
        }
        if (!"core".equals(selected) && !"screens".equals(selected) && !"overlays".equals(selected)) {
            throw new IllegalArgumentException("Unknown client GameTest suite: " + selected);
        }
        return selected.equals(suite);
    }
}
