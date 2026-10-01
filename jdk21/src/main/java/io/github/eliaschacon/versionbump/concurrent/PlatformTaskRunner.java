package io.github.eliaschacon.versionbump.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * Java 21 implementation (stored under {@code META-INF/versions/21} of the Multi-Release JAR): runs every task
 * on its own virtual thread. Planning a bump is I/O bound (one read per file), so virtual threads let large
 * file sets be read concurrently without sizing a thread pool.
 *
 * <p>Results and failures follow the {@link TaskRunner} contract: task order, first failure in task order.</p>
 */
public final class PlatformTaskRunner implements TaskRunner {

	@Override
	public String name() {
		return "virtual-threads";
	}

	@Override
	public <T> List<T> runAll(List<Task<T>> tasks) throws BumpException {
		// Checked up front (see TaskRunner): Future.get() only notices an interrupt while it waits.
		TaskRunner.failIfInterrupted();
		List<Future<T>> futures = new ArrayList<>(tasks.size());
		// close() waits for every task, so no work outlives this call.
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Task<T> task : tasks) {
				futures.add(executor.submit(task::call));
			}
			List<T> results = new ArrayList<>(tasks.size());
			for (Future<T> future : futures) {
				results.add(await(future));
			}
			return results;
		}
	}

	private static <T> T await(Future<T> future) throws BumpException {
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BumpException(INTERRUPTED_MESSAGE, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof BumpException bump) {
				throw bump;
			}
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new BumpException("Unexpected failure while planning the bump: " + cause, cause);
		}
	}
}
