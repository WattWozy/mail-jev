package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassificationReport;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.out.AuditLog;
import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.*;

import java.util.ArrayList;
import java.util.List;

/** Use case: run the classifier against the connected inbox and apply (or preview) the policy. */
public final class ClassifyService implements ClassifyUseCase {

    public static final String REVIEW_LABEL = "mail-sorter/review";

    private final ConnectionStore store;
    private final Classifier classifier;
    private final PolicyEngine policy;
    private final AuditLog audit;

    public ClassifyService(ConnectionStore store, Classifier classifier, PolicyEngine policy, AuditLog audit) {
        this.store = store;
        this.classifier = classifier;
        this.policy = policy;
        this.audit = audit;
    }

    public ClassificationReport execute(ClassifyCommand command) {
        MailProvider mailbox = store.current().orElseThrow(NotConnectedException::new);
        List<ClassificationReport.Entry> entries = new ArrayList<>();

        for (Email email : mailbox.listUnprocessed(command.limit())) {
            Classification classification = classifyOrUnknown(email);
            Action action = policy.decide(classification);

            audit.record(email.id(), classification, action, command.dryRun());
            if (!command.dryRun()) {
                perform(mailbox, email.id(), action);
                mailbox.markProcessed(email.id());
            }
            entries.add(new ClassificationReport.Entry(email.id(), email.from(), email.subject(), classification, action));        }
        return new ClassificationReport(List.copyOf(entries), command.dryRun());
    }

    private static void perform(MailProvider mailbox, String id, Action action) {
        switch (action) {
            case ARCHIVE -> mailbox.archive(id);
            case MOVE_TO_TRASH -> mailbox.moveToTrash(id);
            case FLAG_FOR_REVIEW -> mailbox.addLabel(id, REVIEW_LABEL);
            case KEEP -> { }
        }
    }

    private Classification classifyOrUnknown(Email email) {
        try {
            return classifier.classify(email);
        } catch (ClassificationFailedException e) {
            return new Classification(Category.UNKNOWN, 0.0, "classification failed: " + e.getMessage());
        }
    }
}
