package dev.jefe.mailsorter.application;

import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.domain.*;

import java.util.*;
import java.util.stream.Collectors;

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
        final Set<String> failOn = new HashSet<>();
        final Set<String> failing = new HashSet<>();
        final List<List<MailOperation>> applyCalls = new ArrayList<>();

        FakeMailProvider withEmails(Email... emails) { inbox.addAll(List.of(emails)); return this; }

        FakeMailProvider failingOn(String... ids) {
            failing.addAll(List.of(ids));
            return this;
        }

        private void maybeFail(String id) {
            if (failing.contains(id)) {
                throw new MailAccessException("simulated failure for " + id);
            }
        }

        @Override public String accountId(){ return "you@fakemail.com";};
        @Override public List<Email> listUnprocessed(int limit) {
            return inbox.stream().filter(e -> !processed.contains(e.id())).limit(limit).toList();
        }
        @Override public void markProcessed(String id) { processed.add(id); }
        @Override public void restoreToInbox(String id) { restored.add(id); }
        @Override public void removeLabel(String id, String label) {
            labels.computeIfPresent(id, (k, v) -> { v.remove(label); return v; });
        }
        @Override public void archive(String id) {
            maybeFail(id);
            archived.add(id);
        }

        @Override public void moveToTrash(String id) {
            maybeFail(id);
            trashed.add(id);
        }

        @Override public void addLabel(String id, String label) {
            maybeFail(id);
            labels.computeIfAbsent(id, k -> new ArrayList<>()).add(label);
        }

        @Override
        public Set<String> applyAll(List<MailOperation> ops, String reviewLabel) {
            applyCalls.add(List.copyOf(ops));
            Set<String> failed = new HashSet<>();
            Set<String> inInbox = inbox.stream().map(Email::id).collect(Collectors.toSet());

            for (MailOperation op : ops) {
                String id = op.emailId();
                if (failOn.contains(id) || !inInbox.contains(id)) {
                    failed.add(id);
                    continue;
                }
                markProcessed(id);
                switch (op.action()) {
                    case ARCHIVE -> archive(id);
                    case MOVE_TO_TRASH -> moveToTrash(id);
                    case FLAG_FOR_REVIEW -> addLabel(id, reviewLabel);
                    case KEEP -> { }
                }
            }
            return failed;
        }
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
