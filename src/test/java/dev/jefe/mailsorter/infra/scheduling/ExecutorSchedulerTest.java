package dev.jefe.mailsorter.infra.scheduling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorSchedulerTest {

    private static final Duration FAST = Duration.ofMillis(20);

    private final ExecutorScheduler scheduler = new ExecutorScheduler();

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    void runsTheTaskRepeatedly() throws Exception {
        var runs = new CountDownLatch(3);

        scheduler.start(runs::countDown, FAST);

        assertThat(runs.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stopHaltsFurtherRuns() throws Exception {
        var count = new AtomicInteger();
        var first = new CountDownLatch(1);
        scheduler.start(() -> { count.incrementAndGet(); first.countDown(); }, FAST);
        assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.stop();
        Thread.sleep(100);                       // let an in-flight run finish
        int afterStop = count.get();
        Thread.sleep(150);

        assertThat(count.get()).isEqualTo(afterStop);
    }

    @Test
    void startingAgainReplacesThePreviousTask() throws Exception {
        var oldTask = new AtomicInteger();
        var newRuns = new CountDownLatch(2);
        scheduler.start(oldTask::incrementAndGet, FAST);

        scheduler.start(newRuns::countDown, FAST);
        Thread.sleep(50);                        // let an in-flight old run finish
        int oldAfterSwitch = oldTask.get();

        assertThat(newRuns.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(oldTask.get()).isEqualTo(oldAfterSwitch);
    }

    @Test
    void aThrowingTaskKeepsBeingScheduled() throws Exception {
        var runs = new CountDownLatch(3);

        scheduler.start(() -> { runs.countDown(); throw new IllegalStateException("boom"); }, FAST);

        assertThat(runs.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stopWithoutStartIsHarmless() {
        scheduler.stop();
    }
}
