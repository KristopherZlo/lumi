package io.github.lumi.client.ui;

import java.util.Objects;
import java.util.UUID;

/** Validates a zone-scoped Save before sending one immutable intent. */
public final class ZoneDetailsController {
    public static final int MAX_MESSAGE_LENGTH = 256;
    private final ZoneSaveSender sender;

    public ZoneDetailsController(ZoneSaveSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    public Submission save(UUID zoneId, String value, boolean active) {
        Objects.requireNonNull(zoneId, "zoneId");
        String message = Objects.requireNonNull(value, "value").trim();
        if (!active) {
            return new Submission(false, "luma.zones.save_enter_first");
        }
        if (message.isEmpty()) {
            return new Submission(false, "luma.status.save_name_required");
        }
        if (message.length() > MAX_MESSAGE_LENGTH
                || message.codePoints().anyMatch(Character::isISOControl)) {
            return new Submission(false, "Invalid zone Save message");
        }
        try {
            sender.send(zoneId, message);
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi zone could not be saved" : failed.getMessage());
        }
    }

    public record Submission(boolean accepted, String error) {
        public Submission {
            Objects.requireNonNull(error, "error");
        }
    }

    @FunctionalInterface
    public interface ZoneSaveSender {
        void send(UUID zoneId, String message);
    }
}
