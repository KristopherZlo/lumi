package io.github.luma.integration.common;

import java.util.List;

public record IntegrationStatus(
        String toolId,
        boolean available,
        List<String> capabilities,
        String mode
) {
}
