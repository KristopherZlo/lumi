package io.github.luma.integration.common;

import java.util.List;

public interface SchematicBridge extends ExternalToolAdapter {

    List<String> supportedSchematicFormats();
}
