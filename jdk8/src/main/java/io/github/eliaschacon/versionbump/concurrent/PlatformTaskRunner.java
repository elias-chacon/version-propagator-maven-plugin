package io.github.eliaschacon.versionbump.concurrent;

import java.util.ArrayList;
import java.util.List;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * Java 8 implementation (base version of the Multi-Release JAR, used on Java 8 to 20): runs the tasks one after
 * the other in the calling thread. On Java 21+ the JVM loads the {@code jdk21} version of this class instead.
 */
public final class PlatformTaskRunner implements TaskRunner {

	@Override
	public String name() {
		return "sequential";
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
