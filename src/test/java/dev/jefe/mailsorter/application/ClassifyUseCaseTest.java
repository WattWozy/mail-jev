package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.Fakes.FakeClassifier;

import dev.jefe.mailsorter.application.Fakes.InMemoryConnectionStore;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.dto.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.dto.Plan;
import dev.jefe.mailsorter.application.port.out.PlanStore;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.domain.model.Action;
import dev.jefe.mailsorter.domain.model.Category;
import dev.jefe.mailsorter.domain.model.Classification;
import dev.jefe.mailsorter.domain.model.Email;
import dev.jefe.mailsorter.domain.policy.PolicyEngine;
import dev.jefe.mailsorter.infra.persistence.memory.InMemoryPlanStore;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.jefe.mailsorter.application.Fakes.email;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassifyUseCaseTest {

    private FakeMailProvider mailbox;
    private FakeClassifier classifier;
    private PlanStore plans;
    private InMemoryConnectionStore store;
    private ClassifyService classify;

    @BeforeEach
    void setUp() {
        mailbox = new FakeMailProvider();
        classifier = new FakeClassifier();
        plans = new InMemoryPlanStore();
        store = new InMemoryConnectionStore();
        store.save(mailbox);
        var policy = new PolicyEngine(
                Map.of(
                        Category.NEWSLETTER, Action.ARCHIVE,
                        Category.PROMOTION, Action.MOVE_TO_TRASH),
                0.9);
        classify = new ClassifyService(store, classifier, policy, plans);
    }

    @Test
    void failsWhenNoMailboxIsConnected() {
        var disconnected = new ClassifyService(
                new InMemoryConnectionStore(), classifier, new PolicyEngine(Map.of(), 0.9), plans);

        assertThatThrownBy(() -> disconnected.execute(new ClassifyCommand(10)))
                .isInstanceOf(NotConnectedException.class);
    }

    @Test
    void planningNeverTouchesTheMailboxAndStoresThePlan() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.NEWSLETTER, 0.97).answer("2", Category.PROMOTION, 0.95);

        var plan = classify.execute(new ClassifyCommand(10));

        assertThat(mailbox.archived).isEmpty();
        assertThat(mailbox.trashed).isEmpty();
        assertThat(mailbox.processed).isEmpty();
        assertThat(mailbox.labels).isEmpty();
        assertThat(plan.entries()).extracting(Plan.Entry::action)
                .containsExactly(Action.ARCHIVE, Action.MOVE_TO_TRASH);
        assertThat(plans.find(plan.id())).contains(plan);
    }

    @Test
    void respectsTheLimit() {
        mailbox.withEmails(email("1"), email("2"), email("3"));
        classifier.answer("1", Category.NEWSLETTER, 0.97)
                .answer("2", Category.NEWSLETTER, 0.97)
                .answer("3", Category.NEWSLETTER, 0.97);

        var plan = classify.execute(new ClassifyCommand(2));

        assertThat(plan.entries()).hasSize(2);
    }

    @Test
    void planCountsActions() {
        mailbox.withEmails(email("1"), email("2"), email("3"));
        classifier.answer("1", Category.NEWSLETTER, 0.97)
                .answer("2", Category.NEWSLETTER, 0.95)
                .answer("3", Category.PROMOTION, 0.2);

        var plan = classify.execute(new ClassifyCommand(10));

        assertThat(plan.countOf(Action.ARCHIVE)).isEqualTo(2);
        assertThat(plan.countOf(Action.FLAG_FOR_REVIEW)).isEqualTo(1);
        assertThat(plan.countOf(Action.MOVE_TO_TRASH)).isZero();
    }

    @Test
    void emptyInboxGivesEmptyPlan() {
        var plan = classify.execute(new ClassifyCommand(10));
        assertThat(plan.entries()).isEmpty();
        assertThat(plans.find(plan.id())).contains(plan);
    }

    @Test
    void aFailedClassificationFlagsThatEmailForReviewAndTheBatchContinues() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.failFor("1").answer("2", Category.NEWSLETTER, 0.97);

        var plan = classify.execute(new ClassifyCommand(10));

        assertThat(plan.entries()).extracting(Plan.Entry::action)
                .containsExactly(Action.FLAG_FOR_REVIEW, Action.ARCHIVE);
        // still no mailbox side-effects
        assertThat(mailbox.labels).isEmpty();
        assertThat(mailbox.archived).isEmpty();
        assertThat(mailbox.processed).isEmpty();
    }

    @Test
    void aFailedClassificationCarriesTheReasonInThePlan() {
        mailbox.withEmails(email("1"));
        classifier.failFor("1");

        var plan = classify.execute(new ClassifyCommand(10));

        assertThat(plan.entries().get(0).classification().reason())
                .contains("classification failed");
        assertThat(plan.entries().get(0).classification().source())
                .isEqualTo(dev.jefe.mailsorter.domain.model.ClassificationSource.FALLBACK);
    }

    @Test
    void planEntriesCarryTheSenderAndSubject() {
        mailbox.withEmails(new Email("1", "news@shop.com", "Weekly deals", "snippet", Map.of()));
        classifier.answer("1", Category.NEWSLETTER, 0.97);

        var entry = classify.execute(new ClassifyCommand(10)).entries().get(0);

        assertThat(entry.from()).isEqualTo("news@shop.com");
        assertThat(entry.subject()).isEqualTo("Weekly deals");
    }

    // -- caching ------------------------------------------------------------------

    @Test
    void aSecondPlanReusesTheCachedClassification() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        classify.execute(new ClassifyCommand(10));
        classifier.answer("1", Category.PROMOTION, 0.97);

        var plan = classify.execute(new ClassifyCommand(10));

        assertThat(plan.entries().get(0).classification().category()).isEqualTo(Category.NEWSLETTER);
    }

    @Test
    void forgettingTheCacheMakesNewRulesApplyToMailAlreadySeen() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);
        classify.execute(new ClassifyCommand(10));
        classifier.answers.put("1", new Classification(Category.OTHER, 1.0, "rule").withFolder("Ikea"));

        classify.forgetCachedClassifications();
        var entry = classify.execute(new ClassifyCommand(10)).entries().get(0);

        assertThat(entry.classification().folder()).isEqualTo("Ikea");
        assertThat(entry.action()).isEqualTo(Action.MOVE_TO_FOLDER);
    }
}