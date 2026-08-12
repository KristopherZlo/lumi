package io.github.lumi.storage.repository;

import io.github.lumi.domain.service.SurvivalOptInStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Atomic world-wide set of operators who explicitly enabled Lumi in Survival. */
public final class SurvivalOptInRepository implements SurvivalOptInStore {
    private static final int MAGIC = 0x4c535032; // LSP2
    private static final int MAX_PLAYERS = 100_000;
    private static final int MAX_FILE_BYTES =
            2 * Integer.BYTES + MAX_PLAYERS * 2 * Long.BYTES;
    private final Path file;

    public SurvivalOptInRepository(Path worldRoot) {
        file = worldRoot.toAbsolutePath().normalize()
                .resolve("lumi").resolve("history").resolve("server")
                .resolve("survival-opt-in.bin");
    }

    @Override
    public synchronized boolean isEnabled(UUID playerId) throws IOException {
        return read().contains(playerId);
    }

    @Override
    public synchronized void setEnabled(UUID playerId, boolean enabled) throws IOException {
        TreeSet<UUID> players = new TreeSet<>(read());
        boolean changed = enabled ? players.add(playerId) : players.remove(playerId);
        if (changed) {
            AtomicFileWriter.replace(file, encode(players));
        }
    }

    Path file() {
        return file;
    }

    private Set<UUID> read() throws IOException {
        if (!Files.exists(file)) {
            return Collections.emptySet();
        }
        byte[] content = RepositoryFileReader.read(file, MAX_FILE_BYTES);
        try (var input = new DataInputStream(new ByteArrayInputStream(content))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid Survival opt-in magic");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_PLAYERS) {
                throw new IOException("Invalid Survival opt-in count");
            }
            TreeSet<UUID> players = new TreeSet<>();
            UUID previous = null;
            for (int index = 0; index < count; index++) {
                UUID player = new UUID(input.readLong(), input.readLong());
                if (previous != null && previous.compareTo(player) >= 0) {
                    throw new IOException("Survival opt-ins are not canonical");
                }
                players.add(player);
                previous = player;
            }
            if (input.read() != -1) {
                throw new IOException("Trailing Survival opt-in data");
            }
            return players;
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated Survival opt-in data", truncated);
        }
    }

    private static byte[] encode(Set<UUID> players) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(players.size());
            for (UUID player : players) {
                output.writeLong(player.getMostSignificantBits());
                output.writeLong(player.getLeastSignificantBits());
            }
        }
        return bytes.toByteArray();
    }
}
