package io.github.luma.client.update;

public record UpdateCheckResult(Status status, UpdateRelease release, String detail) {

    public static UpdateCheckResult available(UpdateRelease release) {
        return new UpdateCheckResult(Status.AVAILABLE, release, "");
    }

    public static UpdateCheckResult noneAvailable() {
        return new UpdateCheckResult(Status.UP_TO_DATE, null, "");
    }

    public static UpdateCheckResult unavailable(String detail) {
        return new UpdateCheckResult(Status.UNAVAILABLE, null, detail == null ? "" : detail);
    }

    public boolean available() {
        return this.status == Status.AVAILABLE && this.release != null;
    }

    public boolean upToDate() {
        return this.status == Status.UP_TO_DATE;
    }

    public enum Status {
        AVAILABLE,
        UP_TO_DATE,
        UNAVAILABLE
    }
}
