package io.github.lumi.client.ui;

import java.util.Objects;

/** Validates zone creation before sending one immutable selection intent. */
public final class ZoneScreenController {
    public static final int MAX_NAME_LENGTH = 256;
    private final ZoneCreateSender sender;

    public ZoneScreenController(ZoneCreateSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public Submission create(String value) {
        String name = Objects.requireNonNull(value, "value").trim();
        if (name.isEmpty()) {
            return new Submission(false, "luma.status.zone_name_required");
        }
        if (name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            return new Submission(false, "Invalid zone name");
        }
        try {
            sender.send(name);
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi zone could not be created" : failed.getMessage());
        }
    }

    public record Submission(boolean accepted, String error) {
        public Submission {
            Objects.requireNonNull(error, "error");
        }
    }

    @FunctionalInterface
    public interface ZoneCreateSender {
        void send(String name);
    }
}
