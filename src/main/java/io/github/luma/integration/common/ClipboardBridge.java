package io.github.luma.integration.common;

import java.util.List;

public interface ClipboardBridge extends ExternalToolAdapter {

    boolean clipboardAvailable(String actor);

    List<String> supportedClipboardFormats();
}
