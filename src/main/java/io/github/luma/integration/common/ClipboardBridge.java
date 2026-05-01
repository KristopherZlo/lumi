package io.github.luma.integration.common;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;

public interface ClipboardBridge extends ExternalToolAdapter {

    default boolean clipboardAvailable(ServerPlayer player) {
        return false;
    }

    boolean clipboardAvailable(String actor);

    List<String> supportedClipboardFormats();
}
