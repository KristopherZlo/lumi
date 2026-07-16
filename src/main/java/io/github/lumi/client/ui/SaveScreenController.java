package io.github.lumi.client.ui;

import java.util.Objects;

/** Validates the Save form and sends one immutable intent through its narrow port. */
public final class SaveScreenController {
    public static final int MAX_NAME_LENGTH = 256;
    private final SaveIntentSender save;
    private final SaveIntentSender amend;

    public SaveScreenController(SaveIntentSender save, SaveIntentSender amend) {
        this.save = Objects.requireNonNull(save, "save");
        this.amend = Objects.requireNonNull(amend, "amend");
    }

    public Submission submit(String value, Intent intent) {
        String message = Objects.requireNonNull(value, "value").trim();
        Objects.requireNonNull(intent, "intent");
        if (message.isEmpty()) {
            return new Submission(false, "luma.status.save_name_required");
        }
        if (message.length() > MAX_NAME_LENGTH) {
            return new Submission(false, "Save name is too long");
        }
        try {
            (intent == Intent.SAVE ? save : amend).send(message);
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi save could not start" : failed.getMessage());
        }
    }

    public enum Intent { SAVE, AMEND }

    public record Submission(boolean accepted, String error) {
        public Submission {
            Objects.requireNonNull(error, "error");
        }
    }

    @FunctionalInterface
    public interface SaveIntentSender {
        void send(String message);
    }
}
