package io.github.lumi.client.ui;

import java.util.Objects;

/** Validates one builder-facing branch name before sending its intent. */
public final class BranchNameController {
    public static final int MAX_NAME_LENGTH = 256;
    private final BranchIntentSender sender;

    public BranchNameController(BranchIntentSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public Submission submit(String value) {
        String name = Objects.requireNonNull(value, "value").trim();
        if (name.isEmpty()) {
            return new Submission(false, "luma.status.variant_name_required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return new Submission(false, "Branch name is too long");
        }
        try {
            sender.send(name);
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi branch could not be created" : failed.getMessage());
        }
    }

    public record Submission(boolean accepted, String error) {
        public Submission {
            Objects.requireNonNull(error, "error");
        }
    }

    @FunctionalInterface
    public interface BranchIntentSender {
        void send(String name);
    }
}
