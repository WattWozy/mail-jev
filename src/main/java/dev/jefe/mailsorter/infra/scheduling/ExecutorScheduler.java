package dev.jefe.mailsorter.infra.scheduling;

import dev.jefe.mailsorter.application.port.out.Scheduler;
import dev.jefe.mailsorter.infra.logging.AppLog;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single background thread, fixed delay between runs (a slow run never causes runs to pile up).
 * The first run starts right away so enabling gives immediate feedback.
 * The thread is a daemon: it never keeps the JVM alive on its own.
 */
public final class ExecutorScheduler implements Scheduler, AutoCloseable {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "autopilot-scheduler");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> current;

    @Override
    public synchronized void start(Runnable task, Duration interval) {
        cancelCurrent();
        current = executor.scheduleWithFixedDelay(() -> runSafely(task), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        cancelCurrent();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void cancelCurrent() {
        if (current != null) {
            current.cancel(false);                       // let an in-flight run finish cleanly
            current = null;
        }
    }

    /** An exception escaping a scheduled task would silently cancel all future runs. */
    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            AppLog.error(ExecutorScheduler.class, "Scheduled task failed: " + e.getMessage(), e);
        }
    }
}
