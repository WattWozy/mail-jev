package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.Fakes.FakeClassifier;
import dev.jefe.mailsorter.application.Fakes.FakeMailProvider;
import dev.jefe.mailsorter.application.Fakes.InMemoryAuditLog;
import dev.jefe.mailsorter.application.Fakes.InMemoryConnectionStore;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassificationReport;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.service.ClassifyService;
import dev.jefe.mailsorter.domain.Action;
import dev.jefe.mailsorter.domain.Category;
import dev.jefe.mailsorter.domain.PolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.jefe.mailsorter.application.Fakes.email;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassifyUseCaseTest {

    private FakeMailProvider mailbox;
    private FakeClassifier classifier;
    private InMemoryAuditLog audit;
    private InMemoryConnectionStore store;
    private ClassifyService classify;

    @BeforeEach
    void setUp() {
        mailbox = new FakeMailProvider();
        classifier = new FakeClassifier();
        audit = new InMemoryAuditLog();
        store = new InMemoryConnectionStore();
        store.save(mailbox);
        var policy = new PolicyEngine(
                Map.of(
                        Category.NEWSLETTER, Action.ARCHIVE,
                        Category.PROMOTION, Action.MOVE_TO_TRASH),
                0.9);
        classify = new ClassifyService(store, classifier, policy, audit);
    }

    @Test
    void failsWhenNoMailboxIsConnected() {
        var disconnected = new ClassifyService(
                new InMemoryConnectionStore(), classifier, new PolicyEngine(Map.of(), 0.9), audit);

        assertThatThrownBy(() -> disconnected.execute(ClassifyCommand.preview(10)))
                .isInstanceOf(NotConnectedException.class);
    }

    @Test
    void previewNeverTouchesTheMailbox() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.NEWSLETTER, 0.97).answer("2", Category.PROMOTION, 0.95);

        var report = classify.execute(ClassifyCommand.preview(10));

        assertThat(mailbox.archived).isEmpty();
        assertThat(mailbox.trashed).isEmpty();
        assertThat(report.dryRun()).isTrue();
        assertThat(report.entries()).extracting(ClassificationReport.Entry::action)
                .containsExactly(Action.ARCHIVE, Action.MOVE_TO_TRASH);
    }

    @Test
    void applyExecutesTheDecidedActions() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.NEWSLETTER, 0.97).answer("2", Category.PROMOTION, 0.95);

        classify.execute(ClassifyCommand.apply(10));

        assertThat(mailbox.archived).containsExactly("1");
        assertThat(mailbox.trashed).containsExactly("2");
    }

    @Test
    void lowConfidenceEmailsAreLabelledForReviewAndNotMoved() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.PROMOTION, 0.4);

        classify.execute(ClassifyCommand.apply(10));

        assertThat(mailbox.trashed).isEmpty();
        assertThat(mailbox.labels.get("1")).containsExactly(ClassifyService.REVIEW_LABEL);
    }

    @Test
    void everyDecisionIsAuditedIncludingDryRuns() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);

        classify.execute(ClassifyCommand.preview(10));
        classify.execute(ClassifyCommand.apply(10));

        assertThat(audit.entries).extracting(InMemoryAuditLog.Entry::dryRun).containsExactly(true, false);
    }

    @Test
    void respectsTheLimit() {
        mailbox.withEmails(email("1"), email("2"), email("3"));
        classifier.answer("1", Category.NEWSLETTER, 0.97)
                .answer("2", Category.NEWSLETTER, 0.97)
                .answer("3", Category.NEWSLETTER, 0.97);

        var report = classify.execute(ClassifyCommand.apply(2));

        assertThat(report.entries()).hasSize(2);
    }

    @Test
    void reportCountsActions() {
        mailbox.withEmails(email("1"), email("2"), email("3"));
        classifier.answer("1", Category.NEWSLETTER, 0.97)
                .answer("2", Category.NEWSLETTER, 0.95)
                .answer("3", Category.PROMOTION, 0.2);

        var report = classify.execute(ClassifyCommand.preview(10));

        assertThat(report.countOf(Action.ARCHIVE)).isEqualTo(2);
        assertThat(report.countOf(Action.FLAG_FOR_REVIEW)).isEqualTo(1);
        assertThat(report.countOf(Action.MOVE_TO_TRASH)).isZero();
    }

    @Test
    void emptyInboxGivesEmptyReport() {
        assertThat(classify.execute(ClassifyCommand.preview(10)).entries()).isEqualTo(List.of());
    }

    @Test
    void aFailedClassificationFlagsThatEmailForReviewAndTheBatchContinues() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.failFor("1").answer("2", Category.NEWSLETTER, 0.97);

        var report = classify.execute(ClassifyCommand.apply(10));

        assertThat(report.entries()).extracting(ClassificationReport.Entry::action)
                .containsExactly(Action.FLAG_FOR_REVIEW, Action.ARCHIVE);
        assertThat(mailbox.labels.get("1")).containsExactly(ClassifyService.REVIEW_LABEL);
        assertThat(mailbox.archived).containsExactly("2");
    }

    @Test
    void aFailedClassificationIsAuditedWithItsReason() {
        mailbox.withEmails(email("1"));
        classifier.failFor("1");

        classify.execute(ClassifyCommand.preview(10));

        assertThat(audit.entries.get(0).classification().reason()).contains("classification failed");
    }

    @Test
    void applyMarksEveryHandledEmailProcessedIncludingKeptAndFlagged() {
        mailbox.withEmails(email("1"), email("2"), email("3"));
        classifier.answer("1", Category.NEWSLETTER, 0.97)   // archived
                .answer("2", Category.PERSONAL, 0.99)     // kept
                .answer("3", Category.PROMOTION, 0.2);    // flagged

        classify.execute(ClassifyCommand.apply(10));

        assertThat(mailbox.processed).containsExactly("1", "2", "3");
    }

    @Test
    void previewNeverMarksAnythingProcessed() {
        mailbox.withEmails(email("1"));
        classifier.answer("1", Category.NEWSLETTER, 0.97);

        classify.execute(ClassifyCommand.preview(10));

        assertThat(mailbox.processed).isEmpty();
    }

    @Test
    void aSecondApplyDoesNotSeeEmailsAlreadyHandled() {
        mailbox.withEmails(email("1"), email("2"));
        classifier.answer("1", Category.PERSONAL, 0.99).answer("2", Category.PERSONAL, 0.99);

        classify.execute(ClassifyCommand.apply(1));
        var second = classify.execute(ClassifyCommand.apply(1));

        assertThat(second.entries()).extracting(ClassificationReport.Entry::emailId).containsExactly("2");
    }
}
