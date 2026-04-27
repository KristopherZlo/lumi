package io.github.luma.ui.toolkit;

public enum UiToolkit {
    LDLIB2("LDLib2"),
    MINECRAFT("Lumi internal UI");

    private final String displayName;

    UiToolkit(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
