package io.github.luma.telemetry;

import java.util.regex.Pattern;

public final class TelemetrySanitizer {

    private static final Pattern WINDOWS_MINECRAFT_REGION_PATH = Pattern.compile(
            "(?i)[A-Z]:\\\\Users\\\\[^\\\\\\s]+\\\\[^\\r\\n]+?\\.mca"
    );
    private static final Pattern LINUX_MINECRAFT_REGION_PATH = Pattern.compile(
            "(?i)/(?:home|Users)/[^/\\s]+/[^\\r\\n]+?\\.mca"
    );
    private static final Pattern WINDOWS_USER_PATH = Pattern.compile(
            "(?i)[A-Z]:\\\\Users\\\\[^\\\\\\s]+\\\\[^\\r\\n\\s]+"
    );
    private static final Pattern LINUX_USER_PATH = Pattern.compile(
            "(?i)/(?:home|Users)/[^/\\s]+/[^\\r\\n\\s]+"
    );
    private static final Pattern UUID_LIKE = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
    );
    private static final Pattern BLOCK_POS = Pattern.compile(
            "(?i)BlockPos\\{\\s*x\\s*=\\s*-?\\d+\\s*,\\s*y\\s*=\\s*-?\\d+\\s*,\\s*z\\s*=\\s*-?\\d+\\s*}"
    );
    private static final Pattern XYZ = Pattern.compile(
            "(?i)\\b[xyz]\\s*=\\s*-?\\d+(?:\\s*,\\s*[xyz]\\s*=\\s*-?\\d+){2}\\b"
    );
    private static final Pattern SEED = Pattern.compile("(?i)\\bseed\\s*=\\s*-?\\d+\\b");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");

    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text;
        sanitized = WINDOWS_MINECRAFT_REGION_PATH.matcher(sanitized).replaceAll("<path>");
        sanitized = LINUX_MINECRAFT_REGION_PATH.matcher(sanitized).replaceAll("<path>");
        sanitized = WINDOWS_USER_PATH.matcher(sanitized).replaceAll("<path>");
        sanitized = LINUX_USER_PATH.matcher(sanitized).replaceAll("<path>");
        sanitized = BLOCK_POS.matcher(sanitized).replaceAll("<pos>");
        sanitized = XYZ.matcher(sanitized).replaceAll("<pos>");
        sanitized = SEED.matcher(sanitized).replaceAll("seed=<redacted>");
        sanitized = UUID_LIKE.matcher(sanitized).replaceAll("<uuid>");
        sanitized = CONTROL.matcher(sanitized).replaceAll("");
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }
}
