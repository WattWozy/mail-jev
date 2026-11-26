package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ApplyPlanUseCase;
import dev.jefe.mailsorter.application.port.in.AutopilotUseCase;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.in.ListAccountsUseCase;
import dev.jefe.mailsorter.application.port.in.dto.AccountStatus;
import dev.jefe.mailsorter.application.port.in.dto.AccountView;
import dev.jefe.mailsorter.application.port.in.dto.ApplyCommand;
import dev.jefe.mailsorter.application.port.in.dto.ApplyResult;
import dev.jefe.mailsorter.application.port.in.dto.AutopilotStatus;
import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.port.out.AutopilotSettingsStore;
import dev.jefe.mailsorter.application.port.out.EventPublisher;
import dev.jefe.mailsorter.application.port.out.Scheduler;
import dev.jefe.mailsorter.application.port.out.dto.AutopilotSettings;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Use case: keep sorting the inbox unattended. Each run is exactly "classify the next batch, then apply
 * the plan as-is", so autopilot gets the same safety as a manual run (PolicyEngine thresholds, protected
 * categories, flag-for-review on low confidence).
 */
public final class AutopilotService implements AutopilotUseCase {

    public static final String EVENT_TYPE = "autopilot";
    private static final String NOTHING_TO_DO = "No new mail";

    private final ClassifyUseCase classify;
    private final ApplyPlanUseCase apply;
    private final ListAccountsUseCase accounts;
    private final AutopilotSettingsStore settingsStore;
    private final Scheduler scheduler;
    private final EventPublisher events;
    private final Clock clock;

    private final ReentrantLock runLock = new ReentrantLock();

    private volatile Instant lastRunAt;
    private volatile String lastResult;
    private volatile String lastError;

    public AutopilotService(ClassifyUseCase classify, ApplyPlanUseCase apply, ListAccountsUseCase accounts,
                            AutopilotSettingsStore settingsStore, Scheduler scheduler,
                            EventPublisher events, Clock clock) {
        this.classify = classify;
        this.apply = apply;
        this.accounts = accounts;
        this.settingsStore = settingsStore;
        this.scheduler = scheduler;
        this.events = events;
        this.clock = clock;
    }

    // -- configuration ------------------------------------------------------------

    @Override
    public AutopilotStatus getStatus() {
        return statusOf(settings());
    }

    @Override
    public synchronized AutopilotStatus setEnabled(boolean enabled, int intervalSeconds, int limit) {
        // validate before touching anything: the record rejects out-of-range values
        new AutopilotSettings(enabled, intervalSeconds, limit, null);

        String accountId = enabled ? activeAccountId() : settings().accountId();
        var next = new AutopilotSettings(enabled, intervalSeconds, limit, accountId);
        settingsStore.save(next);

        if (enabled) {
            scheduler.start(this::runNow, Duration.ofSeconds(intervalSeconds));
        } else {
            scheduler.stop();
        }
        return statusOf(next);
    }

    @Override
    public synchronized void resumeIfEnabled() {
        var current = settings();
        if (current.enabled()) {
            scheduler.start(this::runNow, Duration.ofSeconds(current.intervalSeconds()));
        }
    }

    // -- a run ----------------------------------------------------------------------

    @Override
    public AutopilotStatus runNow() {
        if (!runLock.tryLock()) {
            return getStatus();                           // a run is already in progress
        }
        try {
            var current = settings();
            String notice = null;
            try {
                lastResult = runBatch(current);
                lastError = null;
                if (!NOTHING_TO_DO.equals(lastResult)) notice = lastResult;   // stay quiet on empty runs
            } catch (RuntimeException e) {
                lastError = describe(e);
                notice = "failed: " + lastError;
                AppLog.warn(AutopilotService.class, "Autopilot run failed: " + lastError);
            } finally {
                lastRunAt = clock.instant();
            }
            if (notice != null) publish(notice);
            return statusOf(current);
        } finally {
            runLock.unlock();
        }
    }

    private String runBatch(AutopilotSettings current) {
        Plan plan = classifyReconnecting(current);
        if (plan.entries().isEmpty()) {
            return NOTHING_TO_DO;
        }
        ApplyResult result = apply.execute(ApplyCommand.of(plan.id()));
        return summarize(result);
    }

    /** After a restart there is no open session: reconnect the remembered account once and retry. */
    private Plan classifyReconnecting(AutopilotSettings current) {
        var command = new ClassifyCommand(current.limit());
        try {
            return classify.execute(command);
        } catch (NotConnectedException e) {
            if (current.accountId() == null) {
                throw e;
            }
            accounts.connectSaved(current.accountId());
            return classify.execute(command);
        }
    }

    // -- helpers --------------------------------------------------------------------

    private void publish(String message) {
        try {
            events.publish(EVENT_TYPE, message);
        } catch (RuntimeException e) {
            AppLog.warn(AutopilotService.class, "Could not publish autopilot event: " + e);
        }
    }

    private AutopilotSettings settings() {
        return settingsStore.load().orElseGet(AutopilotSettings::defaults);
    }

    private String activeAccountId() {
        return accounts.list().stream()
                .filter(a -> a.status() == AccountStatus.ACTIVE)
                .map(AccountView::id)
                .findFirst()
                .orElseThrow(NotConnectedException::new);
    }

    private AutopilotStatus statusOf(AutopilotSettings s) {
        return new AutopilotStatus(s.enabled(), s.intervalSeconds(), s.limit(), lastRunAt, lastResult, lastError);
    }

    private static String summarize(ApplyResult result) {
        long total = result.applied().values().stream().mapToLong(Long::longValue).sum();
        String breakdown = result.applied().entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        String summary = "Sorted " + total + " email(s)" + (breakdown.isEmpty() ? "" : ": " + breakdown);
        return result.failures().isEmpty() ? summary : summary + "; " + result.failures().size() + " failed";
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
