package io.github.lumi.client.onboarding;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/** Atomic local persistence for onboarding completion and dismissed help hints. */
public final class ClientOnboardingStateRepository {
    private final Path file;

    public ClientOnboardingStateRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve("lumi-onboarding"));
    }

    public ClientOnboardingStateRepository(Path file) {
        this.file = file;
    }

    public boolean completed() {
        return load().completed();
    }

    public void markCompleted() {
        State current = load();
        save(new State(true, current.dismissedHintIds()));
    }

    public Set<String> dismissedHintIds() {
        return load().dismissedHintIds();
    }

    public void dismissHint(String hintId) {
        String normalized = normalizeHint(hintId);
        if (normalized == null) {
            return;
        }
        State current = load();
        LinkedHashSet<String> dismissed = new LinkedHashSet<>(current.dismissedHintIds());
        dismissed.add(normalized);
        save(new State(current.completed(), dismissed));
    }

    public void resetHints() {
        State current = load();
        save(new State(current.completed(), Set.of()));
    }

    private State load() {
        if (!Files.exists(file)) {
            return State.empty();
        }
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean completed = !lines.isEmpty() && lines.getFirst().trim().equals("1");
            LinkedHashSet<String> dismissed = new LinkedHashSet<>();
            for (int index = 1; index < lines.size(); index++) {
                String hint = normalizeHint(lines.get(index));
                if (hint != null) {
                    dismissed.add(hint);
                }
            }
            return new State(completed, dismissed);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not read Lumi onboarding state", failed);
            return State.empty();
        }
    }

    private void save(State state) {
        Path temporary = null;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = Files.createTempFile(
                    parent, ".lumi-onboarding-", ".tmp");
            StringBuilder encoded = new StringBuilder(state.completed() ? "1\n" : "0\n");
            state.dismissedHintIds().stream().sorted()
                    .forEach(hint -> encoded.append(hint).append('\n'));
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi onboarding state", failed);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a client preference temporary.
                }
            }
        }
    }

    private static String normalizeHint(String hintId) {
        if (hintId == null) {
            return null;
        }
        String normalized = hintId.trim();
        return normalized.matches("[a-z0-9_]+") ? normalized : null;
    }

    private record State(boolean completed, Set<String> dismissedHintIds) {
        private State {
            dismissedHintIds = Set.copyOf(dismissedHintIds);
        }

        private static State empty() {
            return new State(false, Set.of());
        }
    }
}
