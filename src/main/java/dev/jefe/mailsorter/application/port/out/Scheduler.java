package dev.jefe.mailsorter.application.port.out;

import java.time.Duration;

/** Driven port. Runs a task repeatedly with a fixed delay between runs. Starting again replaces the old task. */
public interface Scheduler {
    void start(Runnable task, Duration interval);
    void stop();
}
