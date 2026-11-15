package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.domain.*;
import java.util.*;

/** In-memory test doubles for the driven ports. */
final class Fakes {
    private Fakes() {}

    static Email email(String id) {
        return new Email(id, "sender@example.com", "subject " + id, "snippet", Map.of());
    }

    static final class FakeMailProvider implements MailProvider {
        final List<Email> inbox = new ArrayList<>();
        final List<String> archived = new ArrayList<>();
        final List<String> trashed = new ArrayList<>();
        final List<String> processed = new ArrayList<>();
        final List<String> restored = new ArrayList<>();
        final Map<String, List<String>> labels = new HashMap<>();

        FakeMailProvider withEmails(Email... emails) { inbox.addAll(List.of(emails)); return this; }

        @Override public List<Email> listUnprocessed(int limit) {
            return inbox.stream().filter(e -> !processed.contains(e.id())).limit(limit).toList();
        }
        @Override public void markProcessed(String id) { processed.add(id); }
        @Override public void restoreToInbox(String id) { restored.add(id); }
        @Override public void removeLabel(String id, String label) {
            labels.computeIfPresent(id, (k, v) -> { v.remove(label); return v; });
        }
        @Override public void archive(String id) { archived.add(id); }
        @Override public void moveToTrash(String id) { trashed.add(id); }
        @Override public void addLabel(String id, String label) { labels.computeIfAbsent(id, k -> new ArrayList<>()).add(label); }
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
        record Entry(String emailId, Classification classification, Action action, boolean dryRun) {}
        final List<Entry> entries = new ArrayList<>();
        @Override public void record(String id, Classification c, Action a, boolean dryRun) {
            entries.add(new Entry(id, c, a, dryRun));
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
