package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.domain.config.MailboxConfig;
import dev.jefe.mailsorter.domain.model.*;
import dev.jefe.mailsorter.testsupport.FakeMailProvider;

import java.util.*;

/** In-memory test doubles for the driven ports. */
public final class Fakes {
    private Fakes() {}

    static Email email(String id) {
        return new Email(id, "sender@example.com", "subject " + id, "snippet", Map.of());
    }
    public static EmailContent fakeEmail(String emailId) {
        return new EmailContent(emailId,
                "From test",
                "Test subject",
                "Test date",
                " : some test body  :");
    }

    static final class FakeClassifier implements Classifier {
        final Map<String, Classification> answers = new HashMap<>();
        final Set<String> failing = new HashSet<>();

        FakeClassifier answer(String emailId, Category c, double confidence) {
            answers.put(emailId, new Classification(c, confidence, "fake"));
            return this;
        }

        FakeClassifier failFor(String emailId) {
            failing.add(emailId);
            return this;
        }

        @Override public Classification classify(Email email) {
            if (failing.contains(email.id())) {
                throw new ClassificationFailedException("boom");
            }
            return answers.get(email.id());
        }
    }

    static final class InMemoryAuditLog implements AuditLog {
        record Entry(String emailId, Classification classification, Action action, boolean overridden) {}
        final List<Entry> entries = new ArrayList<>();

        @Override
        public void record(String id, Classification c, Action a, boolean overridden) {
            entries.add(new Entry(id, c, a, overridden));
        }
    }

    static final class InMemoryConnectionStore implements ConnectionStore {
        private MailProvider current;
        @Override public void save(MailProvider provider) { this.current = provider; }
        @Override public Optional<MailProvider> current() { return Optional.ofNullable(current); }
    }

    static final class FakeMailProviderFactory implements MailProviderFactory {
        MailProvider toReturn = new FakeMailProvider();
        RuntimeException failure;
        MailboxConfig lastConfig;
        @Override public MailProvider connect(MailboxConfig config) {
            lastConfig = config;
            if (failure != null) throw failure;
            return toReturn;
        }
    }
}
