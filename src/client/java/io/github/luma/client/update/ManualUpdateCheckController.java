package io.github.luma.client.update;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ManualUpdateCheckController {

    private final Supplier<CompletableFuture<UpdateCheckResult>> check;

    public ManualUpdateCheckController() {
        this(UpdateCheckService.getInstance());
    }

    public ManualUpdateCheckController(UpdateCheckService updateCheckService) {
        this(() -> updateCheckService.requestCheckNow());
    }

    ManualUpdateCheckController(Supplier<CompletableFuture<UpdateCheckResult>> check) {
        this.check = Objects.requireNonNull(check, "check");
    }

    public CompletableFuture<Result> checkNow() {
        return this.check.get().handle((result, exception) -> {
            if (exception != null) {
                return Result.unavailable(exception.getClass().getSimpleName());
            }
            return Result.from(result);
        });
    }

    public record Result(Status status, Optional<UpdateProjectNotice> notice, String detail) {

        public Result {
            status = status == null ? Status.UNAVAILABLE : status;
            notice = notice == null ? Optional.empty() : notice;
            detail = detail == null ? "" : detail;
        }

        static Result from(UpdateCheckResult result) {
            if (result == null) {
                return unavailable("");
            }
            if (result.available()) {
                return UpdateProjectNotice.from(result.release())
                        .map(Result::available)
                        .orElseGet(() -> unavailable("missing-update-url"));
            }
            if (result.upToDate()) {
                return upToDate();
            }
            return unavailable(result.detail());
        }

        static Result available(UpdateProjectNotice notice) {
            return new Result(Status.UPDATE_AVAILABLE, Optional.of(notice), "");
        }

        static Result upToDate() {
            return new Result(Status.UP_TO_DATE, Optional.empty(), "");
        }

        static Result unavailable(String detail) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), detail);
        }
    }

    public enum Status {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        UNAVAILABLE
    }
}
