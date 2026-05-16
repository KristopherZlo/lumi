package io.github.luma.client.update;

import java.util.ArrayList;
import java.util.List;

final class UpdateVersionComparator {

    int compare(String left, String right) {
        ParsedVersion leftVersion = ParsedVersion.parse(left);
        ParsedVersion rightVersion = ParsedVersion.parse(right);
        int coreComparison = compareCore(leftVersion.core(), rightVersion.core());
        if (coreComparison != 0) {
            return coreComparison;
        }
        return comparePrerelease(leftVersion.prerelease(), rightVersion.prerelease());
    }

    private static int compareCore(List<String> left, List<String> right) {
        int max = Math.max(left.size(), right.size());
        for (int index = 0; index < max; index++) {
            int leftNumber = numberAt(left, index);
            int rightNumber = numberAt(right, index);
            int comparison = Integer.compare(leftNumber, rightNumber);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int comparePrerelease(List<String> left, List<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        if (left.isEmpty()) {
            return 1;
        }
        if (right.isEmpty()) {
            return -1;
        }

        int max = Math.max(left.size(), right.size());
        for (int index = 0; index < max; index++) {
            if (index >= left.size()) {
                return -1;
            }
            if (index >= right.size()) {
                return 1;
            }

            String leftPart = left.get(index);
            String rightPart = right.get(index);
            boolean leftNumeric = isNumeric(leftPart);
            boolean rightNumeric = isNumeric(rightPart);
            int comparison;
            if (leftNumeric && rightNumeric) {
                comparison = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
            } else if (leftNumeric) {
                comparison = -1;
            } else if (rightNumeric) {
                comparison = 1;
            } else {
                comparison = leftPart.compareToIgnoreCase(rightPart);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int numberAt(List<String> parts, int index) {
        if (index >= parts.size()) {
            return 0;
        }
        String part = parts.get(index);
        return isNumeric(part) ? Integer.parseInt(part) : 0;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private record ParsedVersion(List<String> core, List<String> prerelease) {

        private static ParsedVersion parse(String version) {
            String normalized = version == null ? "" : version.trim();
            int buildIndex = normalized.indexOf('+');
            if (buildIndex >= 0) {
                normalized = normalized.substring(0, buildIndex);
            }
            String corePart = normalized;
            String prereleasePart = "";
            int prereleaseIndex = normalized.indexOf('-');
            if (prereleaseIndex >= 0) {
                corePart = normalized.substring(0, prereleaseIndex);
                prereleasePart = normalized.substring(prereleaseIndex + 1);
            }
            return new ParsedVersion(split(corePart), split(prereleasePart));
        }

        private static List<String> split(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            String[] rawParts = value.split("[.]");
            List<String> parts = new ArrayList<>();
            for (String rawPart : rawParts) {
                String part = rawPart.trim();
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return List.copyOf(parts);
        }
    }
}
