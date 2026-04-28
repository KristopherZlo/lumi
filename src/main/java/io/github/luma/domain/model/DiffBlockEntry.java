package io.github.luma.domain.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DiffBlockEntry(
        BlockPoint pos,
        String leftState,
        String rightState,
        ChangeType changeType,
        String leftBlockId,
        String rightBlockId
) {

    private static final Pattern BLOCK_NAME_PATTERN = Pattern.compile("Name:\\s*\"([^\"]+)\"");

    public DiffBlockEntry(BlockPoint pos, String leftState, String rightState, ChangeType changeType) {
        this(pos, leftState, rightState, changeType, blockIdFromSnbt(leftState), blockIdFromSnbt(rightState));
    }

    public DiffBlockEntry {
        leftState = leftState == null ? "" : leftState;
        rightState = rightState == null ? "" : rightState;
        leftBlockId = normalizeBlockId(leftBlockId);
        rightBlockId = normalizeBlockId(rightBlockId);
    }

    public static String blockIdFromSnbt(String state) {
        if (state == null || state.isBlank()) {
            return "minecraft:air";
        }
        if (!state.contains("{") && state.contains(":")) {
            return state;
        }

        Matcher matcher = BLOCK_NAME_PATTERN.matcher(state);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return "minecraft:unknown";
    }

    private static String normalizeBlockId(String blockId) {
        return blockId == null || blockId.isBlank() ? "minecraft:air" : blockId;
    }
}
