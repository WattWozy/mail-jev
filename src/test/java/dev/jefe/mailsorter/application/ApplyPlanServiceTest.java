package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.exception.PlanNotFoundException;
import dev.jefe.mailsorter.application.port.in.ApplyCommand;
import dev.jefe.mailsorter.application.port.in.ApplyResult;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.Plan;
import dev.jefe.mailsorter.application.service.ApplyPlanService;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.PolicyEngine;
import dev.jefe.mailsorter.infra.memory.inMemory.InMemoryPlanStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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
        apply = new ApplyPlanService(store, plans, audit);
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

    @Disabled //todo: review!
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
        var disconnected = new ApplyPlanService(new Fakes.InMemoryConnectionStore(), plans, audit);

        assertThatThrownBy(() -> disconnected.execute(ApplyCommand.of("x")))
                .isInstanceOf(NotConnectedException.class);
    }
}