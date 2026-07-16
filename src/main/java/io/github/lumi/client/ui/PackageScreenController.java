package io.github.lumi.client.ui;

import io.github.lumi.domain.model.PackageName;
import java.util.Objects;

/** Validates a logical package name before sending export or inspect intent. */
public final class PackageScreenController {
    public static final int MAX_NAME_LENGTH = 96;
    private final IntentSender export;
    private final IntentSender inspect;

    public PackageScreenController(IntentSender export, IntentSender inspect) {
        this.export = Objects.requireNonNull(export, "export");
        this.inspect = Objects.requireNonNull(inspect, "inspect");
    }

    public Submission submit(String value, Action action) {
        Objects.requireNonNull(action, "action");
        String name = Objects.requireNonNull(value, "value").trim();
        try {
            PackageName valid = new PackageName(name);
            (action == Action.EXPORT ? export : inspect).send(valid.value());
            return new Submission(true, "");
        } catch (RuntimeException failed) {
            return new Submission(false, failed.getMessage() == null
                    ? "Lumi package request could not start" : failed.getMessage());
        }
    }

    public enum Action { EXPORT, INSPECT }

    public record Submission(boolean accepted, String error) {
        public Submission {
            Objects.requireNonNull(error, "error");
        }
    }

    @FunctionalInterface
    public interface IntentSender {
        void send(String name);
    }
}
