package dev.jefe.mailsorter.testsupport;

import dev.jefe.mailsorter.application.exception.MailAccessException;
import dev.jefe.mailsorter.application.port.out.MailOperation;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.domain.Email;
import dev.jefe.mailsorter.domain.EmailContent;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static dev.jefe.mailsorter.application.Fakes.fakeEmail;


public class FakeMailProvider implements MailProvider {
    final List<Email> inbox = new ArrayList<>();
    final List<String> archived = new ArrayList<>();
    final List<String> trashed = new ArrayList<>();
    final List<String> processed = new ArrayList<>();
    final List<String> restored = new ArrayList<>();
    final Map<String, List<String>> labels = new HashMap<>();
    final Set<String> failOn = new HashSet<>();
    final Set<String> failing = new HashSet<>();
    final List<List<MailOperation>> applyCalls = new ArrayList<>();

    final AtomicInteger closeCalls = new AtomicInteger();
    volatile RuntimeException closeFailure;

    void withEmails(Email... emails) {
        inbox.addAll(List.of(emails));
    }

    FakeMailProvider failingOn(String... ids) {
        failing.addAll(List.of(ids));
        return this;
    }

    private void maybeFail(String id) {
        if (failing.contains(id)) {
            throw new MailAccessException("simulated failure for " + id);
        }
    }

    boolean isClosed() {
        return closeCalls.get() > 0;
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        if (closeFailure != null) throw closeFailure;
    }

    @Override
    public String accountId() {
        return "you@fakemail.com";
    }

    ;

    @Override
    public EmailContent read(String emailId) {
        return fakeEmail(emailId);
    }

    @Override
    public List<Email> listUnprocessed(int limit) {
        return inbox.stream().filter(e -> !processed.contains(e.id())).limit(limit).toList();
    }

    @Override
    public void markProcessed(String id) {
        processed.add(id);
    }

    @Override
    public void restoreToInbox(String id) {
        restored.add(id);
    }

    @Override
    public void removeLabel(String id, String label) {
        labels.computeIfPresent(id, (k, v) -> {
            v.remove(label);
            return v;
        });
    }

    @Override
    public void archive(String id) {
        maybeFail(id);
        archived.add(id);
    }

    @Override
    public void moveToTrash(String id) {
        maybeFail(id);
        trashed.add(id);
    }

    @Override
    public void addLabel(String id, String label) {
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
                case KEEP -> {
                }
            }
        }
        return failed;
    }
}
