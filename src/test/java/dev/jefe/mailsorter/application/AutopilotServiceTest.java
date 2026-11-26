package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.in.ListAccountsUseCase;
import dev.jefe.mailsorter.application.port.in.dto.AccountStatus;
import dev.jefe.mailsorter.application.port.in.dto.AccountView;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.port.out.AutopilotSettingsStore;
import dev.jefe.mailsorter.application.port.out.Scheduler;
import dev.jefe.mailsorter.application.port.out.dto.AutopilotSettings;
import dev.jefe.mailsorter.application.service.ApplyPlanService;
import dev.jefe.mailsorter.application.service.AutopilotService;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.policy.PolicyEngine;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryActivityRepository;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryPlanStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.jefe.mailsorter.application.Fakes.email;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutopilotServiceTest {

    private static final String ACCOUNT = "gmx|me@gmx.com";
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private FakeMailProvider mailbox;
    private Fakes.FakeClassifier classifier;
    private Fakes.InMemoryConnectionStore connections;
    private ClassifyService classify;
    private ApplyPlanService apply;
    private FakeAccounts accounts;
    private FakeSettingsStore settings;
    private FakeScheduler scheduler;
    private final List<String> events = new ArrayList<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        mailbox = new FakeMailProvider();
        classifier = new Fakes.FakeClassifier();
        connections = new Fakes.InMemoryConnectionStore();
        connections.save(mailbox);
        var plans = new InMemoryPlanStore();
        var policy = new PolicyEngine(Map.of(Category.NEWSLETTER, Action.ARCHIVE), 0.9);
        classify = new ClassifyService(connections, classifier, policy, plans);
        apply = new ApplyPlanService(connections, plans, new Fakes.InMemoryAuditLog(),
                new InMemoryActivityRepository(), clock);
        accounts = new FakeAccounts(ACCOUNT, () -> connections.save(mailbox));
        settings = new FakeSettingsStore();
        scheduler = new FakeScheduler();
    }

    private AutopilotService autopilot() {
        return autopilot(classify);
    }

    private AutopilotService autopilot(ClassifyUseCase classifyUseCase) {
        return new AutopilotService(classifyUseCase, apply, accounts, settings, scheduler,
                (type, message) -> events.add(type + ": " + message), clock);
    }

    // -- status & configuration -------------------------------------------------

    @Test
    void isDisabledWithDefaultsWhenNothingWasConfigured() {
        var status = autopilot().getStatus();

        assertThat(status.enabled()).isFalse();
        assertThat(status.intervalSeconds()).isEqualTo(AutopilotSettings.defaults().intervalSeconds());
        assertThat(status.limit()).isEqualTo(AutopilotSettings.defaults().limit());
        assertThat(status.lastRunAt()).isNull();
    }

    @Test
    void enablingPersistsSettingsForTheActiveAccountAndStartsTheSchedule() {
        var status = autopilot().setEnabled(true, 60, 25);

        assertThat(status.enabled()).isTrue();
        assertThat(settings.saved).isEqualTo(new AutopilotSettings(true, 60, 25, ACCOUNT));
        assertThat(scheduler.interval).isEqualTo(Duration.ofSeconds(60));
        assertThat(scheduler.isRunning()).isTrue();
    }

    @Test
    void enablingAgainReplacesTheScheduleWithTheNewInterval() {
        var autopilot = autopilot();
        autopilot.setEnabled(true, 60, 25);

        autopilot.setEnabled(true, 300, 25);

        assertThat(scheduler.interval).isEqualTo(Duration.ofSeconds(300));
        assertThat(scheduler.isRunning()).isTrue();
    }

    @Test
    void disablingStopsTheScheduleAndRemembersTheAccount() {
        var autopilot = autopilot();
        autopilot.setEnabled(true, 60, 25);

        var status = autopilot.setEnabled(false, 60, 25);

        assertThat(status.enabled()).isFalse();
        assertThat(scheduler.isRunning()).isFalse();
        assertThat(settings.saved).isEqualTo(new AutopilotSettings(false, 60, 25, ACCOUNT));
    }

    @Test
    void cannotBeEnabledWithoutAConnectedAccount() {
        accounts.active = null;

        assertThatThrownBy(() -> autopilot().setEnabled(true, 60, 25))
                .isInstanceOf(NotConnectedException.class);
        assertThat(settings.saved).isNull();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void rejectsOutOfRangeSettingsWithoutChangingAnything() {
        var autopilot = autopilot();

        assertThatThrownBy(() -> autopilot.setEnabled(true, 1, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> autopilot.setEnabled(true, 60, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.saved).isNull();
        assertThat(scheduler.isRunning()).isFalse();
    }

    // -- resume on startup ------------------------------------------------------

    @Test
    void resumesTheScheduleOnStartupWhenItWasLeftEnabled() {
        settings.saved = new AutopilotSettings(true, 90, 5, ACCOUNT);

        autopilot().resumeIfEnabled();

        assertThat(scheduler.isRunning()).isTrue();
        assertThat(scheduler.interval).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void staysOffOnStartupWhenItWasDisabled() {
        settings.saved = new AutopilotSettings(false, 90, 5, ACCOUNT);

        autopilot().resumeIfEnabled();

        assertThat(scheduler.isRunning()).isFalse();
    }

    // -- a run ------------------------------------------------------------------

    @Test
    void aRunClassifiesAndAppliesTheNextBatch() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.NEWSLETTER, 0.97).answer("2", Category.NEWSLETTER, 0.97);
        var autopilot = autopilot();
        autopilot.setEnabled(true, 60, 25);

        var status = autopilot.runNow();

        assertThat(mailbox.archived).containsExactlyInAnyOrder("1", "2");
        assertThat(mailbox.processed).containsExactlyInAnyOrder("1", "2");
        assertThat(status.lastRunAt()).isEqualTo(NOW);
        assertThat(status.lastResult()).contains("2");
        assertThat(status.lastError()).isNull();
    }

    @Test
    void theScheduledTaskPerformsARun() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        autopilot().setEnabled(true, 60, 25);

        scheduler.fire();

        assertThat(mailbox.archived).containsExactly("1");
    }

    @Test
    void aRunUsesTheConfiguredBatchLimit() {
        var limits = new ArrayList<Integer>();
        var autopilot = autopilot(cmd -> { limits.add(cmd.limit()); return new Plan("p", List.of()); });
        autopilot.setEnabled(true, 60, 7);

        autopilot.runNow();

        assertThat(limits).containsExactly(7);
    }

    @Test
    void anEmptyInboxTouchesNothing() {
        var autopilot = autopilot();
        autopilot.setEnabled(true, 60, 25);

        var status = autopilot.runNow();

        assertThat(mailbox.applyCalls).isEmpty();
        assertThat(status.lastRunAt()).isEqualTo(NOW);
        assertThat(status.lastError()).isNull();
    }

    @Test
    void aFailingRunIsReportedAndDoesNotThrow() {
        var autopilot = autopilot(cmd -> { throw new MailAccessException("IMAP down"); });
        autopilot.setEnabled(true, 60, 25);

        assertThatCode(autopilot::runNow).doesNotThrowAnyException();

        var status = autopilot.getStatus();
        assertThat(status.lastError()).contains("IMAP down");
        assertThat(status.lastRunAt()).isEqualTo(NOW);
        assertThat(status.enabled()).isTrue();
    }

    @Test
    void aSuccessfulRunClearsThePreviousError() {
        var fail = new AtomicInteger(1);
        var autopilot = autopilot(cmd -> {
            if (fail.getAndDecrement() > 0) throw new MailAccessException("IMAP down");
            return new Plan("p", List.of());
        });
        autopilot.setEnabled(true, 60, 25);
        autopilot.runNow();

        var status = autopilot.runNow();

        assertThat(status.lastError()).isNull();
    }

    @Test
    void reconnectsTheSavedAccountWhenNoSessionIsOpen() {
        settings.saved = new AutopilotSettings(true, 60, 25, ACCOUNT);
        connections.save(null);                       // e.g. after an app restart
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);

        var status = autopilot().runNow();

        assertThat(accounts.reconnects).containsExactly(ACCOUNT);
        assertThat(mailbox.archived).containsExactly("1");
        assertThat(status.lastError()).isNull();
    }

    @Test
    void runsNeverOverlap() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var autopilot = autopilot(cmd -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return new Plan("p", List.of());
        });
        autopilot.setEnabled(true, 60, 25);

        Thread first = Thread.ofVirtual().start(autopilot::runNow);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        autopilot.runNow();                           // must return immediately, not run again
        release.countDown();
        first.join(2_000);

        assertThat(calls).hasValue(1);
    }

    // -- events -------------------------------------------------------------------

    @Test
    void aRunPublishesItsResult() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        var autopilot = autopilot();
        autopilot.setEnabled(true, 60, 25);

        var status = autopilot.runNow();

        assertThat(events).containsExactly("autopilot: " + status.lastResult());
    }

    @Test
    void aFailedRunPublishesTheError() {
        var autopilot = autopilot(cmd -> { throw new MailAccessException("IMAP down"); });
        autopilot.setEnabled(true, 60, 25);

        autopilot.runNow();

        assertThat(events).singleElement().asString().startsWith("autopilot: ").contains("IMAP down");
    }

    @Test
    void aRunWithNothingToDoStaysQuiet() {
        var autopilot = autopilot();
        autopilot.setEnabled(true, 60, 25);

        autopilot.runNow();

        assertThat(events).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -- fakes --------------------------------------------------------------------

    static final class FakeSettingsStore implements AutopilotSettingsStore {
        AutopilotSettings saved;
        @Override public Optional<AutopilotSettings> load() { return Optional.ofNullable(saved); }
        @Override public void save(AutopilotSettings s) { this.saved = s; }
    }

    static final class FakeScheduler implements Scheduler {
        Runnable task;
        Duration interval;
        @Override public void start(Runnable task, Duration interval) { this.task = task; this.interval = interval; }
        @Override public void stop() { this.task = null; }
        boolean isRunning() { return task != null; }
        void fire() { task.run(); }
    }

    static final class FakeAccounts implements ListAccountsUseCase {
        String active;
        final Runnable onConnect;
        final List<String> reconnects = new ArrayList<>();

        FakeAccounts(String active, Runnable onConnect) { this.active = active; this.onConnect = onConnect; }

        @Override public List<AccountView> list() {
            return active == null ? List.of()
                    : List.of(new AccountView(active, "gmx", "me@gmx.com", AccountStatus.ACTIVE));
        }

        @Override public void connectSaved(String accountId) {
            reconnects.add(accountId);
            onConnect.run();
        }
    }
}
