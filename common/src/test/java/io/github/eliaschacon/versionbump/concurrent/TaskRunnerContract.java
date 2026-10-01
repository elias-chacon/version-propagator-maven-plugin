package io.github.eliaschacon.versionbump.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * Contract every {@link TaskRunner} implementation must satisfy, whatever its execution strategy. Shipped in the
 * {@code common} test-jar and extended by the tests of each platform module, which add the checks that depend on
 * the strategy (threads, concurrency, completion order).
 */
public abstract class TaskRunnerContract {

	/**
	 * @return the implementation under test
	 */
	protected abstract TaskRunner runner();

	@Test
	void hasAName() {
		String name = runner().name();
		assertTrue(name != null && !name.trim().isEmpty());
	}

	@Test
	void returnsNothingForNoTasks() throws BumpException {
		assertEquals(Collections.emptyList(), runner().runAll(Collections.<TaskRunner.Task<String>>emptyList()));
	}

	@Test
	void returnsResultsInTaskOrder() throws BumpException {
		List<TaskRunner.Task<Integer>> tasks = new ArrayList<>();
		List<Integer> expected = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			final int index = i;
			tasks.add(() -> index);
			expected.add(i);
		}
		assertEquals(expected, runner().runAll(tasks));
	}

	@Test
	void throwsTheFailureOfTheFirstFailingTaskInOrder() {
		BumpException first = new BumpException("first");
		BumpException second = new BumpException("second");
		List<TaskRunner.Task<String>> tasks = new ArrayList<>();
		tasks.add(() -> "ok");
		tasks.add(() -> {
			throw first;
		});
		tasks.add(() -> {
			throw second;
		});
		TaskRunner runner = runner();

		BumpException e = assertThrows(BumpException.class, () -> runner.runAll(tasks));

		assertSame(first, e);
	}

	@Test
	void failsBeforeRunningAnyTaskAndKeepsTheFlagWhenTheCallerIsInterrupted() {
		AtomicInteger calls = new AtomicInteger();
		List<TaskRunner.Task<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(() -> {
				calls.incrementAndGet();
				return "ok";
			});
		}
		TaskRunner runner = runner();
		Thread.currentThread().interrupt();
		try {
			BumpException e = assertThrows(BumpException.class, () -> runner.runAll(tasks));

			assertEquals(TaskRunner.INTERRUPTED_MESSAGE, e.getMessage());
			assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be kept");
			assertEquals(0, calls.get(), "no task may run");
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void propagatesUncheckedExceptionsUnchanged() {
		IllegalStateException failure = new IllegalStateException("boom");
		List<TaskRunner.Task<String>> tasks = new ArrayList<>();
		tasks.add(() -> {
			throw failure;
		});
		TaskRunner runner = runner();

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> runner.runAll(tasks));

		assertSame(failure, e);
	}
}
