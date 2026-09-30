package io.github.eliaschacon.versionbump.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Writes a file through a temporary sibling that replaces the target only after it was fully written
 * and flushed, so a failure never leaves a partially written file behind.
 *
 * <p>On Windows, replacing a file fails with {@link AccessDeniedException} while another process (antivirus,
 * indexer, IDE) holds it open for a moment. The replacement is therefore retried a few times with a growing
 * pause before the failure is reported.</p>
 */
public class AtomicFileWriter {

	/** Default number of replacement attempts. */
	public static final int DEFAULT_MOVE_ATTEMPTS = 10;
	/** Default pause before the second attempt; attempt n waits n times this value (total about 2.25 s). */
	public static final long DEFAULT_RETRY_DELAY_MILLIS = 50;

	private final int moveAttempts;
	private final long retryDelayMillis;

	public AtomicFileWriter() {
		this(DEFAULT_MOVE_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS);
	}

	/**
	 * @param moveAttempts     replacement attempts when access is denied (at least 1)
	 * @param retryDelayMillis base pause between attempts
	 */
	public AtomicFileWriter(int moveAttempts, long retryDelayMillis) {
		if (moveAttempts < 1 || retryDelayMillis < 0) {
			throw new IllegalArgumentException("moveAttempts must be >= 1 and retryDelayMillis >= 0");
		}
		this.moveAttempts = moveAttempts;
		this.retryDelayMillis = retryDelayMillis;
	}

	/**
	 * Temporary files are created with restrictive permissions; keep the original ones (e.g. executable scripts).
	 */
	private static void copyPermissions(Path from, Path to) throws IOException {
		if (!Files.exists(from)) {
			return;
		}
		try {
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(from);
			Files.setPosixFilePermissions(to, permissions);
		} catch (UnsupportedOperationException e) {
			// Non-POSIX file system (Windows): nothing to copy.
		}
	}

	public void write(Path target, byte[] content) throws IOException {
		Path absolute = target.toAbsolutePath();
		Path tmp = Files.createTempFile(absolute.getParent(), "." + absolute.getFileName() + ".", ".tmp");
		try {
			try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.SYNC)) {
				out.write(content);
			}
			copyPermissions(absolute, tmp);
			moveWithRetry(tmp, absolute);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private void moveWithRetry(Path source, Path target) throws IOException {
		for (int attempt = 1; ; attempt++) {
			try {
				move(source, target);
				return;
			} catch (AccessDeniedException e) {
				if (attempt >= moveAttempts) {
					throw e;
				}
				pause(attempt * retryDelayMillis);
			}
		}
	}

	private static void pause(long millis) throws InterruptedIOException {
		try {
			TimeUnit.MILLISECONDS.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while waiting to replace a file");
		}
	}

	/** One replacement attempt; overridable to simulate failures in tests. */
	protected void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
