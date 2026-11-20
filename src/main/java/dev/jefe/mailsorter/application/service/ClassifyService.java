package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassificationReport;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.out.*;
import dev.jefe.mailsorter.domain.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** Use case: run the classifier against the connected inbox and apply (or preview) the policy. */
public final class ClassifyService implements ClassifyUseCase {

    public static final String REVIEW_LABEL = "mail-sorter/review";

    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    private static final String DEFAULT_CLASSIFIER_VERSION = "default";
    private static final int CACHE_MAX_ENTRIES = 2_000;
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final ConnectionStore store;
    private final Classifier classifier;
    private final PolicyEngine policy;
    private final AuditLog audit;
    private final int maxConcurrency;
    private final String classifierVersion; // model + prompt version: changing it invalidates cached entries

    private final ClassificationCache cache = new ClassificationCache(CACHE_MAX_ENTRIES, CACHE_TTL);

    public ClassifyService(ConnectionStore store, Classifier classifier, PolicyEngine policy, AuditLog audit) {
        this(store, classifier, policy, audit, DEFAULT_MAX_CONCURRENCY, DEFAULT_CLASSIFIER_VERSION);
    }

    public ClassifyService(ConnectionStore store, Classifier classifier, PolicyEngine policy, AuditLog audit,
                           int maxConcurrency, String classifierVersion) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        this.store = store;
        this.classifier = classifier;
        this.policy = policy;
        this.audit = audit;
        this.maxConcurrency = maxConcurrency;
        this.classifierVersion = classifierVersion;
    }

    @Override
    public ClassificationReport execute(ClassifyCommand command) {
        MailProvider mailbox = store.current().orElseThrow(NotConnectedException::new);
        long t0 = System.nanoTime();
        List<Email> emails = mailbox.listUnprocessed(command.limit());
        long t1 = System.nanoTime();

        if (emails.isEmpty()) {
            return new ClassificationReport(List.of(), command.dryRun());
        }

        List<EvaluatedEmail> evaluated = evaluateAll(emails, mailbox.accountId());
        long t2 = System.nanoTime();
        System.out.printf("list=%dms classify=%dms%n", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);

        // Mailbox access stays on the calling thread: IMAP Folder/Store are not thread-safe.
        List<ClassificationReport.Entry> entries = new ArrayList<>(evaluated.size());

        Set<String> failed = Set.of();
        if (!command.dryRun()) {
            long a = System.nanoTime();
            failed = mailbox.applyAll(
                    evaluated.stream()
                            .map(i -> new MailOperation(i.email().id(), i.action()))
                            .toList(),
                    REVIEW_LABEL);
            System.out.printf("apply=%dms failed=%d%n", (System.nanoTime() - a) / 1_000_000, failed.size());
        }

        for (EvaluatedEmail item : evaluated) {
            Email email = item.email();
            boolean applied = command.dryRun() || !failed.contains(email.id());
            if (applied) {
                audit.record(email.id(), item.classification(), item.action(), command.dryRun());
                if (!command.dryRun()) {
                    cache.remove(item.cacheKey());
                }
            }
            entries.add(new ClassificationReport.Entry(
                    email.id(), email.from(), email.subject(), item.classification(), item.action()
            ));
        }

        return new ClassificationReport(List.copyOf(entries), command.dryRun());
    }

    private List<EvaluatedEmail> evaluateAll(List<Email> emails, String accountId) {
        Semaphore limiter = new Semaphore(maxConcurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EvaluatedEmail>> futures = emails.stream()
                    .map(email -> executor.submit(() -> evaluateOne(email, accountId, limiter)))
                    .toList();

            List<EvaluatedEmail> result = new ArrayList<>(futures.size());
            for (Future<EvaluatedEmail> future : futures) {
                result.add(future.get());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Classification interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("Classification failed", cause);
        }
    }

    private EvaluatedEmail evaluateOne(Email email, String accountId, Semaphore limiter)
            throws InterruptedException {
        ClassificationCache.Key key = new ClassificationCache.Key(accountId, email.id(), classifierVersion);
        Classification classification = classifyCached(email, key, limiter);
        return new EvaluatedEmail(email, key, classification, policy.decide(classification));
    }

    /** No lock is held during the classifier call; only successful results are cached. */
    private Classification classifyCached(Email email, ClassificationCache.Key key, Semaphore limiter)
            throws InterruptedException {
        Classification hit = cache.get(key);
        if (hit != null) {
            return hit;
        }

        limiter.acquire(); // only real classifier calls count against the concurrency limit
        try {
            Classification classification = classifier.classify(email);
            cache.put(key, classification);
            return classification;
        } catch (ClassificationFailedException e) {
            return unknown(e); // transient failure: deliberately NOT cached
        } finally {
            limiter.release();
        }
    }

    private static Classification unknown(ClassificationFailedException e) {
        return new Classification(Category.UNKNOWN, 0.0, "classification failed: " + e.getMessage());
    }

    private static void perform(MailProvider mailbox, String id, Action action) {
        switch (action) {
            case ARCHIVE -> mailbox.archive(id);
            case MOVE_TO_TRASH -> mailbox.moveToTrash(id);
            case FLAG_FOR_REVIEW -> mailbox.addLabel(id, REVIEW_LABEL);
            case KEEP -> { }
        }
    }

    private record EvaluatedEmail(Email email, ClassificationCache.Key cacheKey,
                                  Classification classification, Action action) {}
}