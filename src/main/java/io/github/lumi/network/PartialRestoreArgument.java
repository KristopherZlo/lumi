package io.github.lumi.network;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;

/** Canonical compact argument for a ref-guarded partial/outside Restore command. */
public record PartialRestoreArgument(CommitId target, BlockAreaTarget area) {
    public PartialRestoreArgument {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(area, "area");
    }

    public String encode() {
        BlockBox box = area.area();
        return String.join("|",
                target.hex(),
                Integer.toString(box.minX()),
                Integer.toString(box.minY()),
                Integer.toString(box.minZ()),
                Integer.toString(box.maxX()),
                Integer.toString(box.maxY()),
                Integer.toString(box.maxZ()),
                Boolean.toString(area.outside()));
    }

    public static PartialRestoreArgument parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] fields = value.split("\\|", -1);
        if (fields.length != 8
                || (!fields[7].equals("true") && !fields[7].equals("false"))) {
            throw new IllegalArgumentException("Invalid partial Restore argument");
        }
        try {
            return new PartialRestoreArgument(
                    new CommitId(new ObjectId(fields[0])),
                    new BlockAreaTarget(new BlockBox(
                            Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2]),
                            Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4]),
                            Integer.parseInt(fields[5]),
                            Integer.parseInt(fields[6])),
                            Boolean.parseBoolean(fields[7])));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid partial Restore coordinates", invalid);
        }
    }
}
