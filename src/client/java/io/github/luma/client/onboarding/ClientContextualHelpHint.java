package io.github.luma.client.onboarding;

public enum ClientContextualHelpHint {
    HISTORY("history"),
    BRANCHES("branches"),
    IMPORT_EXPORT("import_export"),
    SETTINGS("settings"),
    MORE("more"),
    SAVE("save"),
    RESTORE("restore"),
    COMPARE("compare"),
    PARTIAL_RESTORE("partial_restore"),
    RECOVERY("recovery");

    private final String id;
    private final String titleKey;
    private final String bodyKey;

    ClientContextualHelpHint(String id) {
        this.id = id;
        this.titleKey = "luma.contextual_hint." + id + ".title";
        this.bodyKey = "luma.contextual_hint." + id + ".body";
    }

    public String id() {
        return this.id;
    }

    public String titleKey() {
        return this.titleKey;
    }

    public String bodyKey() {
        return this.bodyKey;
    }
}
