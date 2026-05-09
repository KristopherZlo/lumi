package io.github.luma.ui;

import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builds short, low-noise messages for Minecraft's action bar surface.
 */
public final class ActionBarMessagePresenter {

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

        if (shouldShowOperationPercent(snapshot)) {
            int percent = OperationProgressPresenter.displayPercent(snapshot);
            message.append(Component.literal(" " + percent + "%").withStyle(ChatFormatting.AQUA));
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

    public static Component selectionToolHint(Component actionKey) {
        return baseMessage()
                .append(Component.translatable(
                        "luma.selection.tool_hint",
                        actionKey == null ? Component.empty() : actionKey
                ).withStyle(ChatFormatting.GRAY));
    }

    public static boolean shouldShowOperationPercent(OperationSnapshot snapshot) {
        if (snapshot == null || snapshot.terminal()) {
            return false;
        }
        return snapshot.progress() != null && snapshot.progress().totalUnits() > 0;
    }

    private static Component status(String key, ChatFormatting messageColor) {
        return baseMessage().append(Component.translatable(key).withStyle(messageColor));
    }

    private static MutableComponent baseMessage() {
        return Component.empty()
                .append(Component.literal("Lumi").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
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
            case "quick-rollback" -> "luma.actionbar.operation.quick_rollback";
            case "undo-action" -> "luma.actionbar.operation.undo";
            case "redo-action" -> "luma.actionbar.operation.redo";
            case "merge-variant" -> "luma.actionbar.operation.merge";
            case "light-refresh" -> "luma.actionbar.operation.light_refresh";
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
