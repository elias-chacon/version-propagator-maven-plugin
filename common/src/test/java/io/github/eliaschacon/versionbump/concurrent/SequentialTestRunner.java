package io.github.eliaschacon.versionbump.concurrent;

import java.util.ArrayList;
import java.util.List;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * Test double for the modules that cannot see {@code PlatformTaskRunner} (the {@code common} module is built
 * before the platform modules). Follows the {@link TaskRunner} contract, verified by
 * {@link SequentialTestRunnerTest}.
 */
public final class SequentialTestRunner implements TaskRunner {

	@Override
	public String name() {
		return "sequential-test";
	}

	@Override
	public <T> List<T> runAll(List<Task<T>> tasks) throws BumpException {
		List<T> results = new ArrayList<>(tasks.size());
		for (Task<T> task : tasks) {
			results.add(task.call());
		}
		return results;
	}
}
