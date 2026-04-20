package io.github.luma.integration.common;

import java.util.List;

public interface ExternalToolAdapter {

    String toolId();

    boolean available();

    List<String> capabilities();
}
