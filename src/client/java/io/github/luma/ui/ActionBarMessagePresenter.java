package io.github.luma.ui;

import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builds short, low-noise messages for Minecraft's action bar surface.
 */
public final class ActionBarMessagePresenter {

    private static final int PROGRESS_BAR_UNITS_THRESHOLD = 128;
    private static final int PROGRESS_SEGMENTS = 10;

    private ActionBarMessagePresenter() {
    }

    public static Component operation(OperationSnapshot snapshot) {
        if (snapshot == null || snapshot.handle() == null) {
            return Component.empty();
        }

        MutableComponent message = baseMessage()
                .append(operationLabel(snapshot.handle().label()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable(stageKey(snapshot.stage())).withStyle(stageColor(snapshot.stage())));

        if (shouldShowProgressBar(snapshot)) {
            int percent = OperationProgressPresenter.displayPercent(snapshot);
            message.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(progressBarComponent(percent))
                    .append(Component.literal(" " + percent + "%").withStyle(ChatFormatting.AQUA));
        }

        return message;
    }

    public static Component info(String key) {
        return status(key, ChatFormatting.GRAY);
    }

    public static Component success(String key) {
        return status(key, ChatFormatting.GREEN);
    }

    public static Component warning(String key) {
        return status(key, ChatFormatting.GOLD);
    }

    public static Component error(String key) {
        return status(key, ChatFormatting.RED);
    }

    public static Component selection(String key) {
        if ("luma.selection.no_project".equals(key) || "luma.selection.no_target".equals(key)) {
            return warning(key);
        }
        if ("luma.selection.corner_a".equals(key)
                || "luma.selection.corner_b".equals(key)
                || "luma.selection.reset".equals(key)
                || "luma.selection.cleared".equals(key)) {
            return success(key);
        }
        return info(key);
    }

    public static boolean shouldShowProgressBar(OperationSnapshot snapshot) {
        if (snapshot == null || snapshot.terminal()) {
            return false;
        }
        OperationProgress progress = snapshot.progress();
        if (progress == null || progress.totalUnits() < PROGRESS_BAR_UNITS_THRESHOLD) {
            return false;
        }
        return snapshot.stage() == OperationStage.PREPARING
                || snapshot.stage() == OperationStage.WRITING
                || snapshot.stage() == OperationStage.APPLYING;
    }

    public static String asciiProgressBar(int percent) {
        int filled = filledSegments(percent);
        return "[" + "#".repeat(filled) + ".".repeat(PROGRESS_SEGMENTS - filled) + "]";
    }

    private static Component status(String key, ChatFormatting messageColor) {
        return baseMessage().append(Component.translatable(key).withStyle(messageColor));
    }

    private static MutableComponent baseMessage() {
        return Component.empty()
                .append(Component.literal("Lumi").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component progressBarComponent(int percent) {
        int filled = filledSegments(percent);
        int empty = PROGRESS_SEGMENTS - filled;
        return Component.empty()
                .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("#".repeat(filled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(".".repeat(empty)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static int filledSegments(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        return Math.max(0, Math.min(PROGRESS_SEGMENTS, (int) Math.round((clamped / 100.0D) * PROGRESS_SEGMENTS)));
    }

    private static MutableComponent operationLabel(String label) {
        String key = operationLabelKey(label);
        if (key != null) {
            return Component.translatable(key);
        }
        return Component.literal(humanLabel(label));
    }

    private static String operationLabelKey(String label) {
        return switch (label == null ? "" : label) {
            case "save-version" -> "luma.actionbar.operation.save";
            case "amend-version" -> "luma.actionbar.operation.amend";
            case "restore-version" -> "luma.actionbar.operation.restore";
            case "partial-restore" -> "luma.actionbar.operation.partial_restore";
            case "restore-draft" -> "luma.actionbar.operation.restore_draft";
            case "undo-action" -> "luma.actionbar.operation.undo";
            case "redo-action" -> "luma.actionbar.operation.redo";
            case "merge-variant" -> "luma.actionbar.operation.merge";
            default -> null;
        };
    }

    private static String stageKey(OperationStage stage) {
        return switch (stage) {
            case QUEUED -> "luma.actionbar.stage.queued";
            case PREPARING -> "luma.actionbar.stage.preparing";
            case PRELOADING -> "luma.actionbar.stage.preloading";
            case WRITING -> "luma.actionbar.stage.writing";
            case APPLYING -> "luma.actionbar.stage.applying";
            case FINALIZING -> "luma.actionbar.stage.finalizing";
            case COMPLETED -> "luma.actionbar.stage.completed";
            case FAILED -> "luma.actionbar.stage.failed";
        };
    }

    private static ChatFormatting stageColor(OperationStage stage) {
        return switch (stage) {
            case COMPLETED -> ChatFormatting.GREEN;
            case FAILED -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }

    private static String humanLabel(String label) {
        if (label == null || label.isBlank()) {
            return "Operation";
        }

        String[] parts = label.split("[-_\\s]+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
