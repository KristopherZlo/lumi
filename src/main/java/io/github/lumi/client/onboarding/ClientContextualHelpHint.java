package io.github.lumi.client.onboarding;

/** Builder-facing contextual tips retained from the legacy UI. */
public enum ClientContextualHelpHint {
    HISTORY("history"),
    SHORTCUTS("shortcuts"),
    BRANCHES("branches"),
    IMPORT_EXPORT("import_export"),
    SETTINGS("settings"),
    MORE("more"),
    SAVE("save"),
    CLEAN_STATE("clean_state"),
    QUICK_ROLLBACK("quick_rollback"),
    RESTORE("restore"),
    COMPARE("compare"),
    PARTIAL_RESTORE("partial_restore"),
    RECOVERY("recovery"),
    SELECTION_TOOL("selection_tool"),
    CLEANUP("cleanup"),
    DIAGNOSTICS("diagnostics");

    private final String id;

    ClientContextualHelpHint(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String titleKey() {
        return "luma.contextual_hint." + id + ".title";
    }

    public String bodyKey() {
        return "luma.contextual_hint." + id + ".body";
    }
}
