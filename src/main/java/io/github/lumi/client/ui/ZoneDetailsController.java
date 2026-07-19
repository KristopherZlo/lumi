package io.github.lumi.client.ui;

import io.github.lumi.domain.model.VersionTags;
import java.util.Objects;
import java.util.UUID;

/** Validates a zone-scoped Save before sending one immutable intent. */
public final class ZoneDetailsController {
    public static final int MAX_MESSAGE_LENGTH = 256;
    private final ZoneSaveSender save;
    private final ZoneSaveSender amend;

    public ZoneDetailsController(ZoneSaveSender save, ZoneSaveSender amend) {
        this.save = Objects.requireNonNull(save, "save");
        this.amend = Objects.requireNonNull(amend, "amend");
    }

    public Submission save(UUID zoneId, String value, boolean active) {
        return save(zoneId, value, "", active);
    }

    public Submission save(UUID zoneId, String value, String tags, boolean active) {
        return submit(save, zoneId, value, tags, active);
    }

    public Submission amend(UUID zoneId, String value, String tags, boolean active) {
        return submit(amend, zoneId, value, tags, active);
    }

    private Submission submit(
            ZoneSaveSender sender,
            UUID zoneId,
            String value,
            String tagValue,
            boolean active) {
        Objects.requireNonNull(zoneId, "zoneId");
        String message = Objects.requireNonNull(value, "value").trim();
        Objects.requireNonNull(tagValue, "tagValue");
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
            sender.send(zoneId, message, VersionTags.parse(tagValue));
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
        void send(UUID zoneId, String message, VersionTags tags);
    }
}
