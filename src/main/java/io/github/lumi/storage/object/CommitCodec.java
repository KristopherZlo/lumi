package io.github.lumi.storage.object;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.PlayerSpawn;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class CommitCodec {
    private static final int MAGIC = 0x4C554D32;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_PLAYER_SPAWNS = 10_000;

    public byte[] encode(Commit commit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            CanonicalBytes.writeId(output, commit.tree());
            output.writeByte(commit.parents().size());
            for (CommitId parent : commit.parents()) {
                CanonicalBytes.writeId(output, parent.value());
            }
            CanonicalBytes.writeUuid(output, commit.author().id());
            CanonicalBytes.writeString(output, commit.author().name(), MAX_TEXT_BYTES, "author name");
            CanonicalBytes.writeString(output, commit.message(), MAX_TEXT_BYTES, "commit message");
            output.writeLong(commit.timestamp().getEpochSecond());
            output.writeInt(commit.timestamp().getNano());
            CanonicalBytes.writeUuid(output, commit.workspaceId());
            output.writeBoolean(commit.zoneId().isPresent());
            if (commit.zoneId().isPresent()) {
                CanonicalBytes.writeUuid(output, commit.zoneId().orElseThrow());
            }
            output.writeByte(commit.kind().code());
            output.writeInt(commit.statistics().sections());
            output.writeInt(commit.statistics().entityChunks());
            output.writeLong(commit.statistics().blocks());
            output.writeInt(commit.statistics().entities());
            output.writeInt(commit.playerSpawns().size());
            for (var entry : commit.playerSpawns().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                CanonicalBytes.writeUuid(output, entry.getKey());
                PlayerSpawn spawn = entry.getValue();
                output.writeInt(spawn.x());
                output.writeInt(spawn.y());
                output.writeInt(spawn.z());
                output.writeFloat(spawn.yaw());
                output.writeFloat(spawn.pitch());
                output.writeBoolean(spawn.forced());
            }
        }
        return bytes.toByteArray();
    }

    public Commit decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 commit");
            }
            var tree = CanonicalBytes.readId(input);
            int parentCount = input.readUnsignedByte();
            if (parentCount > 2) {
                throw new IOException("Invalid commit parent count");
            }
            var parents = new ArrayList<CommitId>(parentCount);
            for (int index = 0; index < parentCount; index++) {
                parents.add(new CommitId(CanonicalBytes.readId(input)));
            }
            var author = new CommitAuthor(
                    CanonicalBytes.readUuid(input),
                    CanonicalBytes.readString(input, MAX_TEXT_BYTES, "author name"));
            String message = CanonicalBytes.readString(input, MAX_TEXT_BYTES, "commit message");
            Instant timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt());
            var workspace = CanonicalBytes.readUuid(input);
            int zoneFlag = input.readUnsignedByte();
            if (zoneFlag > 1) {
                throw new IOException("Invalid commit zone flag");
            }
            var zone = zoneFlag == 1 ? Optional.of(CanonicalBytes.readUuid(input)) : Optional.<java.util.UUID>empty();
            int kindCode = input.readUnsignedByte();
            CommitKind kind = CommitKind.fromCode(kindCode);
            var statistics = new CommitStatistics(
                    input.readInt(), input.readInt(), input.readLong(), input.readInt());
            Map<java.util.UUID, PlayerSpawn> playerSpawns = new HashMap<>();
            if (input.available() != 0) {
                int count = input.readInt();
                if (count < 0 || count > MAX_PLAYER_SPAWNS) {
                    throw new IOException("Invalid player spawn count");
                }
                for (int index = 0; index < count; index++) {
                    var player = CanonicalBytes.readUuid(input);
                    var spawn = new PlayerSpawn(
                            input.readInt(), input.readInt(), input.readInt(),
                            input.readFloat(), input.readFloat(), input.readBoolean());
                    if (playerSpawns.put(player, spawn) != null) {
                        throw new IOException("Duplicate player spawn UUID");
                    }
                }
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in commit");
            }
            return new Commit(tree, parents, author, message, timestamp, workspace, zone,
                    kind, statistics, playerSpawns);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid commit payload", invalid);
        }
    }
}
