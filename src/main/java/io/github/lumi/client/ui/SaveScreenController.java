package io.github.lumi.client.ui;

import io.github.lumi.domain.model.VersionTags;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Validates the Save form and sends one immutable intent through its narrow port. */
public final class SaveScreenController {
    public static final int MAX_NAME_LENGTH = 256;
    private final SaveIntentSender save;
    private final SaveIntentSender amend;
    private final Function<Intent, String> unavailableReason;

    public SaveScreenController(SaveIntentSender save, SaveIntentSender amend) {
        this(save, amend, ignored -> "");
    }

    public SaveScreenController(SaveIntentSender save, SaveIntentSender amend,
            Function<Intent, String> unavailableReason) {
        this.save = Objects.requireNonNull(save, "save");
        this.amend = Objects.requireNonNull(amend, "amend");
        this.unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
    }

    public String unavailableReason(Intent intent) {
        return Objects.requireNonNull(unavailableReason.apply(intent), "reason");
    }

    public Submission submit(String value, Intent intent) {
        return submit(value, "", intent);
    }

    public Submission submit(String value, String tagValue, Intent intent) {
        String message = Objects.requireNonNull(value, "value").trim();
        Objects.requireNonNull(tagValue, "tagValue");
        Objects.requireNonNull(intent, "intent");
        if (message.isEmpty()) {
            return new Submission(false, "luma.status.save_name_required", Optional.empty());
        }
        if (message.length() > MAX_NAME_LENGTH) {
            return new Submission(false, "Save name is too long", Optional.empty());
        }
        try {
            String reason = unavailableReason(intent);
            if (!reason.isEmpty()) return new Submission(false, reason, Optional.empty());
            VersionTags tags = VersionTags.parse(tagValue);
            UUID requestId = (intent == Intent.SAVE ? save : amend).send(message, tags);
            return new Submission(true, "", Optional.of(requestId));
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi save could not start" : failed.getMessage(), Optional.empty());
        }
    }

    public enum Intent { SAVE, AMEND }

    public record Submission(boolean accepted, String error, Optional<UUID> requestId) {
        public Submission {
            Objects.requireNonNull(error, "error");
            requestId = Objects.requireNonNull(requestId, "requestId");
            if (accepted != requestId.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted Save submission requires one request ID");
            }
        }
    }

    @FunctionalInterface
    public interface SaveIntentSender {
        UUID send(String message, VersionTags tags);
    }
}
