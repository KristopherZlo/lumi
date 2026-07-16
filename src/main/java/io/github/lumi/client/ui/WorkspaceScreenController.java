package io.github.lumi.client.ui;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;
import java.util.Optional;

/** Validates one named workspace before sending its immutable scope. */
public final class WorkspaceScreenController {
    public static final int MAX_NAME_LENGTH = 256;
    private final WorkspaceCreateSender sender;

    public WorkspaceScreenController(WorkspaceCreateSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public Submission create(String value, Optional<BlockBox> bounds) {
        String name = Objects.requireNonNull(value, "value").trim();
        Optional<BlockBox> selected = Objects.requireNonNull(bounds, "bounds");
        if (name.isEmpty()) {
            return new Submission(false, "luma.status.project_invalid_name");
        }
        if (name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            return new Submission(false, "Invalid workspace name");
        }
        try {
            sender.send(name, selected);
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi workspace could not be created" : failed.getMessage());
        }
    }

    public record Submission(boolean accepted, String error) {
        public Submission {
            Objects.requireNonNull(error, "error");
        }
    }

    @FunctionalInterface
    public interface WorkspaceCreateSender {
        void send(String name, Optional<BlockBox> bounds);
    }
}
