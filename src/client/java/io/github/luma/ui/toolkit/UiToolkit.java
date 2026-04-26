package io.github.luma.ui.toolkit;

public enum UiToolkit {
    LDLIB2("LDLib2"),
    OWO("owo-lib");

    private final String displayName;

    UiToolkit(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
