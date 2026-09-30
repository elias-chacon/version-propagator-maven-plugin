package io.github.eliaschacon.versionbump.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eliaschacon.versionbump.BumpException;

class PlatformTaskRunnerTest extends TaskRunnerContract {

	@Override
	protected TaskRunner runner() {
		return new PlatformTaskRunner();
	}

	@Test
	void runsSequentiallyInTheCallingThread() throws BumpException {
		Thread caller = Thread.currentThread();
		List<TaskRunner.Task<Thread>> tasks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			tasks.add(Thread::currentThread);
		}

		List<Thread> threads = new PlatformTaskRunner().runAll(tasks);

		assertEquals(Collections.nCopies(5, caller), threads);
		assertEquals("sequential", new PlatformTaskRunner().name());
	}
}
