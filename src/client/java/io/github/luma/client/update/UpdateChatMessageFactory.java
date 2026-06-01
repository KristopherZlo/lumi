package io.github.luma.client.update;

import java.net.URI;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class UpdateChatMessageFactory {

    public Component create(UpdateRelease release) {
        MutableComponent message = Component.translatable("luma.update.chat_available", release.version())
                .append(" ");
        this.downloadUri(release).ifPresent(uri -> message.append(this.downloadLink(uri)));
        return message;
    }

    private MutableComponent downloadLink(URI uri) {
        return Component.translatable("luma.action.download_update").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(uri))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("luma.update.chat_download_hover"))));
    }

    private Optional<URI> downloadUri(UpdateRelease release) {
        if (release == null || release.downloadUrl().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(release.downloadUrl()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
