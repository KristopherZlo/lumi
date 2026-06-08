package io.github.luma.client.telemetry;

import io.github.luma.telemetry.TelemetryService;
import io.github.luma.telemetry.TelemetrySettings;
import java.util.Objects;
import java.util.function.Supplier;

public final class TelemetryNoticeController {

    private final Supplier<TelemetrySettings> settingsSupplier;
    private final Runnable acknowledgeAction;

    public TelemetryNoticeController() {
        this(TelemetryService.getInstance()::settings, TelemetryService.getInstance()::markNoticeSeen);
    }

    TelemetryNoticeController(Supplier<TelemetrySettings> settingsSupplier, Runnable acknowledgeAction) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.acknowledgeAction = Objects.requireNonNull(acknowledgeAction, "acknowledgeAction");
    }

    public boolean shouldShowNotice() {
        TelemetrySettings settings = this.settingsSupplier.get();
        return settings.enabled() && !settings.noticeSeen();
    }

    public void acknowledgeNotice() {
        this.acknowledgeAction.run();
    }
}
