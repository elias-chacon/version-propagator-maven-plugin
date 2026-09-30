package io.github.eliaschacon.versionbump.concurrent;

import java.util.List;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * Runs independent, read-only planning tasks (one per file) and returns their results in the order of the
 * tasks, whatever the execution strategy.
 *
 * <p>The implementation used at runtime is {@code PlatformTaskRunner}, packaged in a Multi-Release JAR:
 * sequential on Java 8 to 20 ({@code jdk8} module) and on virtual threads on Java 21+ ({@code jdk21} module).</p>
 *
 * <p>Contract, identical for every implementation:</p>
 * <ul>
 *     <li>results are returned in task order;</li>
 *     <li>if tasks fail, the failure of the first failing task (in task order) is thrown, so errors are
 *     deterministic;</li>
 *     <li>tasks must not write files: writing stays sequential and transactional.</li>
 * </ul>
 */
public interface TaskRunner {

	/**
	 * A unit of work that may fail with a {@link BumpException}.
	 *
	 * @param <T> result type
	 */
	interface Task<T> {
		T call() throws BumpException;
	}

	/**
	 * @return short name of the strategy, for logs (e.g. {@code sequential}, {@code virtual-threads})
	 */
	String name();

	/**
	 * Runs all tasks and returns their results in task order.
	 *
	 * @throws BumpException the failure of the first failing task, in task order
	 */
	<T> List<T> runAll(List<Task<T>> tasks) throws BumpException;
}
