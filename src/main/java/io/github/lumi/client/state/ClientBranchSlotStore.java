package io.github.lumi.client.state;

import io.github.lumi.LumiMod;
import io.github.lumi.network.HistorySnapshotPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/** Persistent per-workspace assignment of branches to the ten Action hotkeys. */
public final class ClientBranchSlotStore {
    public static final int SLOT_COUNT = 10;
    private static final int MAX_WORKSPACES = 64;
    private static final String MAGIC = "LBS2";
    private final Path file;
    private final LinkedHashMap<UUID, LinkedHashMap<Integer, String>> assignments =
            new LinkedHashMap<>(16, 0.75F, true);

    public ClientBranchSlotStore() {
        this(FabricLoader.getInstance().getConfigDir()
                .resolve("lumi-branch-slots"));
    }

    public ClientBranchSlotStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    public synchronized void synchronize(HistorySnapshotPayload snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        LinkedHashMap<Integer, String> slots = assignments.computeIfAbsent(
                snapshot.workspaceId(), ignored -> new LinkedHashMap<>());
        var visible = snapshot.branches().stream()
                .map(HistorySnapshotPayload.Branch::name)
                .collect(java.util.stream.Collectors.toSet());
        boolean changed = slots.entrySet().removeIf(
                entry -> !visible.contains(entry.getValue()));
        for (HistorySnapshotPayload.Branch branch : snapshot.branches()) {
            if (slots.containsValue(branch.name())) {
                continue;
            }
            int free = firstFree(slots);
            if (free < 0) {
                break;
            }
            slots.put(free, branch.name());
            changed = true;
        }
        changed |= trimWorkspaces();
        if (changed) {
            save();
        }
    }

    public synchronized Optional<HistorySnapshotPayload.Branch> branch(
            HistorySnapshotPayload snapshot, int slot) {
        validateSlot(slot);
        Map<Integer, String> slots = assignments.get(snapshot.workspaceId());
        String name = slots == null ? null : slots.get(slot);
        return name == null ? Optional.empty() : snapshot.branches().stream()
                .filter(branch -> branch.name().equals(name))
                .findFirst();
    }

    public synchronized OptionalInt slot(
            HistorySnapshotPayload snapshot, String branch) {
        Objects.requireNonNull(branch, "branch");
        Map<Integer, String> slots = assignments.get(snapshot.workspaceId());
        if (slots != null) {
            for (var entry : slots.entrySet()) {
                if (entry.getValue().equals(branch)) {
                    return OptionalInt.of(entry.getKey());
                }
            }
        }
        return OptionalInt.empty();
    }

    public synchronized void assign(
            HistorySnapshotPayload snapshot, String branch, int slot) {
        validateSlot(slot);
        if (snapshot.branches().stream()
                .noneMatch(candidate -> candidate.name().equals(branch))) {
            throw new IllegalArgumentException("Branch is not visible");
        }
        LinkedHashMap<Integer, String> slots = assignments.computeIfAbsent(
                snapshot.workspaceId(), ignored -> new LinkedHashMap<>());
        slots.entrySet().removeIf(entry -> entry.getValue().equals(branch));
        slots.put(slot, branch);
        save();
    }

    public synchronized void clear(
            HistorySnapshotPayload snapshot, String branch) {
        Map<Integer, String> slots = assignments.get(snapshot.workspaceId());
        if (slots != null && slots.entrySet().removeIf(
                entry -> entry.getValue().equals(branch))) {
            save();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !MAGIC.equals(lines.getFirst())) {
                throw new IOException("Invalid Lumi branch slot file");
            }
            for (int index = 1; index < lines.size(); index++) {
                String[] fields = lines.get(index).split("\\t", -1);
                if (fields.length != 3) {
                    throw new IOException("Invalid Lumi branch slot entry");
                }
                UUID workspace = UUID.fromString(fields[0]);
                int slot = Integer.parseInt(fields[1]);
                validateSlot(slot);
                String branch = new String(
                        Base64.getUrlDecoder().decode(fields[2]),
                        StandardCharsets.UTF_8);
                if (branch.isBlank() || branch.length() > 256) {
                    throw new IOException("Invalid Lumi branch slot branch");
                }
                assignments.computeIfAbsent(
                        workspace, ignored -> new LinkedHashMap<>())
                        .put(slot, branch);
            }
            trimWorkspaces();
        } catch (IOException | IllegalArgumentException failed) {
            assignments.clear();
            LumiMod.LOGGER.warn("Could not read Lumi branch slots", failed);
        }
    }

    private void save() {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(
                    file.getParent(), ".lumi-branch-slots-", ".tmp");
            StringBuilder encoded = new StringBuilder(MAGIC).append('\n');
            assignments.forEach((workspace, slots) ->
                    slots.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> encoded.append(workspace).append('\t')
                                    .append(entry.getKey()).append('\t')
                                    .append(Base64.getUrlEncoder().withoutPadding()
                                            .encodeToString(entry.getValue().getBytes(
                                                    StandardCharsets.UTF_8)))
                                    .append('\n')));
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Could not save Lumi branch slots", failed);
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

    private boolean trimWorkspaces() {
        boolean changed = false;
        while (assignments.size() > MAX_WORKSPACES) {
            assignments.remove(assignments.keySet().iterator().next());
            changed = true;
        }
        return changed;
    }

    private static int firstFree(Map<Integer, String> slots) {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (!slots.containsKey(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("Branch slot must be 0-9");
        }
    }

}
