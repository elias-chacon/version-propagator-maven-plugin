package io.github.eliaschacon.versionbump.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.eliaschacon.versionbump.BumpException;

class PlatformTaskRunnerTest extends TaskRunnerContract {

	/** Upper bound for every wait below; only reached if the runner is not concurrent (the test then fails). */
	private static final long TIMEOUT_SECONDS = 10;

	private final PlatformTaskRunner runner = new PlatformTaskRunner();

	@Override
	protected TaskRunner runner() {
		return runner;
	}

	private static boolean await(CountDownLatch latch) throws BumpException {
		try {
			return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BumpException("interrupted", e);
		}
	}

	@Test
	void usesVirtualThreads() throws BumpException {
		List<TaskRunner.Task<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> Thread.currentThread().isVirtual());
		}

		assertEquals(Collections.nCopies(10, true), runner.runAll(tasks));
		assertEquals("virtual-threads", runner.name());
	}

	@Test
	void runsAllTasksAtTheSameTime() throws BumpException {
		int count = 100;
		CountDownLatch allStarted = new CountDownLatch(count);
		List<TaskRunner.Task<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			tasks.add(() -> {
				allStarted.countDown();
				// Only completes if every task is running concurrently.
				return await(allStarted);
			});
		}

		assertEquals(Collections.nCopies(count, true), runner.runAll(tasks));
	}

	@Test
	void returnsResultsInTaskOrderWhenTasksFinishInReverseOrder() throws BumpException {
		int count = 20;
		List<CountDownLatch> finished = new ArrayList<>();
		for (int i = 0; i <= count; i++) {
			finished.add(new CountDownLatch(1));
		}
		finished.get(count).countDown();
		List<TaskRunner.Task<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int index = i;
			tasks.add(() -> {
				// Task i finishes only after task i + 1: completion order is the reverse of task order.
				await(finished.get(index + 1));
				finished.get(index).countDown();
				return index;
			});
		}

		List<Integer> results = runner.runAll(tasks);

		for (int i = 0; i < count; i++) {
			assertEquals(i, results.get(i));
		}
	}

	@Test
	void reportsTheFirstFailureInTaskOrderEvenWhenALaterTaskFailsFirst() {
		BumpException first = new BumpException("first");
		BumpException second = new BumpException("second");
		CountDownLatch secondFailed = new CountDownLatch(1);
		List<TaskRunner.Task<String>> tasks = List.of(
			() -> {
				await(secondFailed);
				throw first;
			},
			() -> {
				secondFailed.countDown();
				throw second;
			});

		BumpException e = assertThrows(BumpException.class, () -> runner.runAll(tasks));

		assertSame(first, e);
	}

	@Test
	void propagatesErrorsUnchanged() {
		AssertionError failure = new AssertionError("fatal");
		List<TaskRunner.Task<String>> tasks = List.of(() -> {
			throw failure;
		});

		AssertionError e = assertThrows(AssertionError.class, () -> runner.runAll(tasks));

		assertSame(failure, e);
	}

	@Test
	void failsWhenTheCallerIsInterruptedWhileWaiting() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch never = new CountDownLatch(1);
		List<TaskRunner.Task<Boolean>> tasks = List.of(() -> {
			started.countDown();
			// Blocks until the runner cancels it (shutdownNow interrupts it when runAll ends).
			return await(never);
		});
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean interruptFlagKept = new AtomicBoolean();
		Thread caller = Thread.ofPlatform().start(() -> {
			try {
				runner.runAll(tasks);
			} catch (Throwable t) {
				failure.set(t);
			}
			interruptFlagKept.set(Thread.currentThread().isInterrupted());
		});

		assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		caller.interrupt();
		caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

		assertFalse(caller.isAlive(), "runAll must return after an interrupt");
		assertInstanceOf(BumpException.class, failure.get());
		assertTrue(failure.get().getMessage().contains("Interrupted"), failure.get().getMessage());
		assertTrue(interruptFlagKept.get());
	}
}
