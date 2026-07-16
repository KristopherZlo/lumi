package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;
import java.util.UUID;

/** One immutable zone scope and commit pair for a read-only Compare. */
public record ZoneCompareArgument(UUID zoneId, CommitId before, CommitId after) {
    public ZoneCompareArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.equals(after)) {
            throw new IllegalArgumentException("Compare commits must differ");
        }
    }

    public String encode() {
        return zoneId + "|" + before.hex() + "|" + after.hex();
    }

    public static ZoneCompareArgument parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] fields = value.split("\\|", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Invalid zone Compare argument");
        }
        return new ZoneCompareArgument(
                UUID.fromString(fields[0]),
                new CommitId(new ObjectId(fields[1])),
                new CommitId(new ObjectId(fields[2])));
    }
}
