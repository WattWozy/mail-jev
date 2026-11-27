package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.exception.PlanNotFoundException;
import dev.jefe.mailsorter.application.port.in.dto.ApplyCommand;
import dev.jefe.mailsorter.application.port.in.dto.ApplyResult;
import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.service.ApplyPlanService;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.policy.PolicyEngine;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryActivityRepository;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryPlanStore;
import dev.jefe.mailsorter.application.port.out.dto.ActivityEntry;
import dev.jefe.mailsorter.application.port.out.dto.MailOperation;
import dev.jefe.mailsorter.domain.model.ClassificationSource;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static dev.jefe.mailsorter.application.Fakes.email;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

class ApplyPlanServiceTest {

    private FakeMailProvider mailbox;
    private Fakes.FakeClassifier classifier;
    private InMemoryPlanStore plans;
    private Fakes.InMemoryAuditLog audit;
    private Fakes.InMemoryConnectionStore store;
    private ClassifyService classify;
    private ApplyPlanService apply;
    private InMemoryActivityRepository activity;
    private static final Instant NOW = Instant.parse("2026-11-26T20:00:00Z");

    @BeforeEach
    void setUp() {
        mailbox = new FakeMailProvider();
        classifier = new Fakes.FakeClassifier();
        plans = new InMemoryPlanStore();
        audit = new Fakes.InMemoryAuditLog();
        store = new Fakes.InMemoryConnectionStore();
        store.save(mailbox);
        var policy = new PolicyEngine(Map.of(
                Category.NEWSLETTER, Action.ARCHIVE, Category.PROMOTION, Action.MOVE_TO_TRASH), 0.9);
        classify = new ClassifyService(store, classifier, policy, plans);
        activity = new InMemoryActivityRepository();
        apply = new ApplyPlanService(store, plans, audit, activity, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Plan plan() { return classify.execute(new ClassifyCommand(10)); }

    @Test
    void executesExactlyWhatWasPlannedEvenIfTheClassifierWouldNowAnswerDifferently() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        var plan = plan();
        classifier.answer("1", Category.PROMOTION, 0.97);

        apply.execute(ApplyCommand.of(plan.id()));

        assertThat(mailbox.archived).containsExactly("1");
        assertThat(mailbox.trashed).isEmpty();
    }

    @Test
    void anOverrideReplacesTheRecommendedActionAndIsAudited() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.PROMOTION, 0.97); // plan says MOVE_TO_TRASH
        var plan = plan();

        apply.execute(new ApplyCommand(plan.id(), Map.of("1", Action.ARCHIVE)));

        assertThat(mailbox.trashed).isEmpty();
        assertThat(mailbox.archived).containsExactly("1");
        assertThat(audit.entries).extracting(Fakes.InMemoryAuditLog.Entry::overridden).containsExactly(true);
    }

    @Test
    void everyHandledEmailIsMarkedProcessedIncludingKeptAndFlagged() {
        mailbox.withEmails(email("1"), email("2"), email("3"));
        classifier.answer("1", Category.NEWSLETTER, 0.97)
                .answer("2", Category.PERSONAL, 0.99)
                .answer("3", Category.PROMOTION, 0.2);

        apply.execute(ApplyCommand.of(plan().id()));

        assertThat(mailbox.processed).containsExactly("1", "2", "3");
        assertThat(mailbox.labels.get("3")).containsExactly(ApplyPlanService.REVIEW_LABEL);
    }

    @Test
    void aPlanCanOnlyBeAppliedOnce() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        var plan = plan();
        apply.execute(ApplyCommand.of(plan.id()));

        assertThatThrownBy(() -> apply.execute(ApplyCommand.of(plan.id())))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void anUnknownPlanIsRejected() {
        assertThatThrownBy(() -> apply.execute(ApplyCommand.of("nope")))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void overridesForEmailsOutsideThePlanAreRejectedAndNothingIsTouched() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        var plan = plan();

        assertThatThrownBy(() -> apply.execute(new ApplyCommand(plan.id(), Map.of("zzz", Action.KEEP))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mailbox.archived).isEmpty();
        assertThat(plans.find(plan.id())).isPresent(); // still applicable
    }

    @Test
    void oneFailureDoesNotStopTheRestAndIsReported() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.NEWSLETTER, 0.97).answer("2", Category.NEWSLETTER, 0.97);
        mailbox.failing.add("1");

        var result = apply.execute(ApplyCommand.of(plan().id()));

        assertThat(result.failures()).extracting(ApplyResult.Failure::emailId).containsExactly("1");
        assertThat(mailbox.archived).containsExactly("2");
        assertThat(mailbox.processed).containsExactly("2"); // the failed one is retried next run
    }

    @Test
    void failsWhenNoMailboxIsConnected() {
        var disconnected = new ApplyPlanService(new Fakes.InMemoryConnectionStore(), plans, audit,
                activity, Clock.systemUTC());

        assertThatThrownBy(() -> disconnected.execute(ApplyCommand.of("x")))
                .isInstanceOf(NotConnectedException.class);
    }

    // -- activity -----------------------------------------------------------------

    @Test
    void everyAppliedEmailIsRecordedAsActivity() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);

        apply.execute(ApplyCommand.of(plan().id()));

        assertThat(activity.recent(10)).containsExactly(new ActivityEntry(
                NOW, "you@fakemail.com", "1", "sender@example.com", "subject 1",
                Category.NEWSLETTER, 0.97, "fake", Action.ARCHIVE, ClassificationSource.MODEL, false));
    }

    @Test
    void anOverrideIsRecordedAsOverriddenWithTheFinalAction() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.PROMOTION, 0.97);   // plan says MOVE_TO_TRASH

        apply.execute(new ApplyCommand(plan().id(), Map.of("1", Action.KEEP)));

        var entry = activity.recent(10).get(0);
        assertThat(entry.action()).isEqualTo(Action.KEEP);
        assertThat(entry.overridden()).isTrue();
        assertThat(activity.stats().manualOverrides()).isEqualTo(1);
    }

    @Test
    void failedEmailsAreNotRecordedAsActivity() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.NEWSLETTER, 0.97).answer("2", Category.NEWSLETTER, 0.97);
        mailbox.failing.add("1");

        apply.execute(ApplyCommand.of(plan().id()));

        assertThat(activity.recent(10)).extracting(ActivityEntry::emailId).containsExactly("2");
    }

    // -- user folders -------------------------------------------------------------

    @Test
    void anEmailRoutedToAUserFolderIsMovedThere() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answerFolder("1", "Ikea").answer("2", Category.NEWSLETTER, 0.97);

        apply.execute(ApplyCommand.of(plan().id()));

        assertThat(mailbox.filed).containsExactlyEntriesOf(Map.of("1", "Ikea"));
        assertThat(mailbox.archived).containsExactly("2");
        assertThat(mailbox.applyCalls.get(0)).contains(new MailOperation("1", Action.MOVE_TO_FOLDER, "Ikea"));
    }

    @Test
    void theFolderIsRecordedInTheActivityFeed() {
        mailbox.withEmails(email("1"));
        classifier.answerFolder("1", "Travel promotions");

        apply.execute(ApplyCommand.of(plan().id()));

        var entry = activity.recent(10).get(0);
        assertThat(entry.action()).isEqualTo(Action.MOVE_TO_FOLDER);
        assertThat(entry.folder()).isEqualTo("Travel promotions");
    }

    @Test
    void overridingAFolderMoveFallsBackToTheChosenActionWithoutAFolder() {
        mailbox.withEmails(email("1"));
        classifier.answerFolder("1", "Ikea");

        apply.execute(new ApplyCommand(plan().id(), Map.of("1", Action.KEEP)));

        assertThat(mailbox.filed).isEmpty();
        assertThat(mailbox.applyCalls.get(0)).containsExactly(new MailOperation("1", Action.KEEP, null));
        assertThat(activity.recent(10).get(0).folder()).isNull();
    }

    @Test
    void anOverrideToMoveToFolderNeedsAFolderFromThePlanAndNothingIsTouchedOtherwise() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        var plan = plan();

        assertThatThrownBy(() -> apply.execute(new ApplyCommand(plan.id(), Map.of("1", Action.MOVE_TO_FOLDER))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("folder");
        assertThat(mailbox.applyCalls).isEmpty();
        assertThat(plans.find(plan.id())).isPresent();
    }

    @Test
    void aMailOperationOnlyCarriesAFolderWhenMovingToOne() {
        assertThatThrownBy(() -> new MailOperation("1", Action.MOVE_TO_FOLDER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MailOperation("1", Action.ARCHIVE, "Ikea").folder()).isNull();
    }
}
