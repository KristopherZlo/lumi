package io.github.luma.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record HistoryPackageSafetyReport(
        boolean safe,
        List<String> warnings,
        List<String> dangerousBlockEntityTypes,
        List<String> dangerousEntityTypes
) {

    public HistoryPackageSafetyReport {
        warnings = copyDistinct(warnings);
        dangerousBlockEntityTypes = copyDistinct(dangerousBlockEntityTypes);
        dangerousEntityTypes = copyDistinct(dangerousEntityTypes);
        safe = warnings.isEmpty() && dangerousBlockEntityTypes.isEmpty() && dangerousEntityTypes.isEmpty();
    }

    public static HistoryPackageSafetyReport clean() {
        return new HistoryPackageSafetyReport(true, List.of(), List.of(), List.of());
    }

    public static HistoryPackageSafetyReport unsafe(
            List<String> dangerousBlockEntityTypes,
            List<String> dangerousEntityTypes
    ) {
        List<String> warnings = new ArrayList<>();
        if (dangerousBlockEntityTypes != null && !dangerousBlockEntityTypes.isEmpty()) {
            warnings.add("Imported package contains block entity payloads that can execute commands or load world data.");
        }
        if (dangerousEntityTypes != null && !dangerousEntityTypes.isEmpty()) {
            warnings.add("Imported package contains entity payloads that can execute commands or are unknown to this runtime.");
        }
        return new HistoryPackageSafetyReport(false, warnings, dangerousBlockEntityTypes, dangerousEntityTypes);
    }

    public boolean requiresTrustedConfirmation() {
        return !this.safe;
    }

    private static List<String> copyDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
