package io.github.luma.domain.model;

import java.util.List;

public record ProjectIntegrityReport(
        boolean valid,
        List<String> warnings,
        List<String> errors
) {
}
