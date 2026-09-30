package io.github.eliaschacon.versionbump.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Writes a file through a temporary sibling that replaces the target only after it was fully written
 * and flushed, so a failure never leaves a partially written file behind.
 */
public class AtomicFileWriter {

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
			move(tmp, absolute);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	protected void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
