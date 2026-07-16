package io.github.lumi.client.ui;

import java.util.Objects;

/** Validates the Save form and sends one immutable intent through its narrow port. */
public final class SaveScreenController {
    public static final int MAX_NAME_LENGTH = 256;
    private final SaveIntentSender sender;

    public SaveScreenController(SaveIntentSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public Submission submit(String value) {
        String message = Objects.requireNonNull(value, "value").trim();
        if (message.isEmpty()) {
            return new Submission(false, "luma.status.save_name_required");
        }
        if (message.length() > MAX_NAME_LENGTH) {
            return new Submission(false, "Save name is too long");
        }
        try {
            sender.send(message);
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi save could not start" : failed.getMessage());
        }
    }

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
