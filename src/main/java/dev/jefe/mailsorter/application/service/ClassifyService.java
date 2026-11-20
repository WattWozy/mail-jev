package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.application.exception.ClassificationFailedException;
import dev.jefe.mailsorter.application.exception.NotConnectedException;
import dev.jefe.mailsorter.application.port.in.ClassifyCommand;
import dev.jefe.mailsorter.application.port.in.ClassifyUseCase;
import dev.jefe.mailsorter.application.port.in.Plan;
import dev.jefe.mailsorter.application.port.out.Classifier;
import dev.jefe.mailsorter.application.port.out.ConnectionStore;
import dev.jefe.mailsorter.application.port.out.MailProvider;
import dev.jefe.mailsorter.application.port.out.PlanStore;
import dev.jefe.mailsorter.domain.*;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Use case: classify the connected inbox and produce an immutable plan (no mailbox mutation). */
public final class ClassifyService implements ClassifyUseCase {

    private static final int DEFAULT_MAX_CONCURRENCY = 4;
    private static final String DEFAULT_CLASSIFIER_VERSION = "default";
    private static final int CACHE_MAX_ENTRIES = 2_000;
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final ConnectionStore store;
    private final Classifier classifier;
    private final PolicyEngine policy;
    private final PlanStore plans;
    private final int maxConcurrency;
    private final String classifierVersion;

    private final ClassificationCache cache = new ClassificationCache(CACHE_MAX_ENTRIES, CACHE_TTL);

    public ClassifyService(ConnectionStore store, Classifier classifier, PolicyEngine policy, PlanStore plans) {
        this(store, classifier, policy, plans, DEFAULT_MAX_CONCURRENCY, DEFAULT_CLASSIFIER_VERSION);
    }

    public ClassifyService(ConnectionStore store, Classifier classifier, PolicyEngine policy, PlanStore plans,
                           int maxConcurrency, String classifierVersion) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        this.store = store;
        this.classifier = classifier;
        this.policy = policy;
        this.plans = plans;
        this.maxConcurrency = maxConcurrency;
        this.classifierVersion = classifierVersion;
    }

    @Override
    public Plan execute(ClassifyCommand command) {
        MailProvider mailbox = store.current().orElseThrow(NotConnectedException::new);
        List<Email> emails = mailbox.listUnprocessed(command.limit());

        List<Plan.Entry> entries = emails.isEmpty()
                ? List.of()
                : evaluateAll(emails, mailbox.accountId()).stream()
                .map(e -> new Plan.Entry(
                        e.email().id(),
                        e.email().from(),
                        e.email().subject(),
                        e.classification(),
                        e.action()))
                .toList();

        Plan plan = new Plan(UUID.randomUUID().toString(), List.copyOf(entries));
        plans.save(plan);
        return plan;
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
            var ex = new RuntimeException("Classification interrupted", e);
            AppLog.error(ClassifyService.class, ex.getMessage(), ex);
            throw ex;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                AppLog.error(ClassifyService.class, re.getMessage(), re);
                throw re;
            }
            var ex = new RuntimeException("Classification failed", cause);
            AppLog.error(ClassifyService.class, ex.getMessage(), ex);
            throw ex;
        }
    }

    private EvaluatedEmail evaluateOne(Email email, String accountId, Semaphore limiter)
            throws InterruptedException {
        ClassificationCache.Key key = new ClassificationCache.Key(accountId, email.id(), classifierVersion);
        Classification classification = classifyCached(email, key, limiter);
        return new EvaluatedEmail(email, key, classification, policy.decide(classification));
    }

    private Classification classifyCached(Email email, ClassificationCache.Key key, Semaphore limiter)
            throws InterruptedException {
        Classification hit = cache.get(key);
        if (hit != null) {
            return hit;
        }

        limiter.acquire();
        try {
            Classification classification = classifier.classify(email);
            cache.put(key, classification);
            return classification;
        } catch (ClassificationFailedException e) {
            return unknown(key, e); // deliberately not cached
        } finally {
            limiter.release();
        }
    }

    private static Classification unknown(ClassificationCache.Key key, ClassificationFailedException e) {
        AppLog.warn(ClassifyService.class, "JEV classification failed for key={" + key + "}, error={" + e + "}");
        return new Classification(Category.UNKNOWN, 0.0, "classification failed: " + e.getMessage());
    }

    private record EvaluatedEmail(Email email, ClassificationCache.Key cacheKey,
                                  Classification classification, Action action) {}
}