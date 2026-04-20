package io.github.luma.ui.state;

public enum ProjectTab {
    HISTORY("luma.tab.history"),
    VARIANTS("luma.tab.variants"),
    CHANGES("luma.tab.changes"),
    PREVIEW("luma.tab.preview"),
    MATERIALS("luma.tab.materials"),
    INTEGRATIONS("luma.tab.integrations"),
    LOG("luma.tab.log");

    private final String translationKey;

    ProjectTab(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }
}
