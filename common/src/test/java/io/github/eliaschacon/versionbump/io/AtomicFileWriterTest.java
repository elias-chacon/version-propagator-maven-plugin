package io.github.eliaschacon.versionbump.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {

	@TempDir
	Path dir;

	/** Writer whose first {@code denials} replacement attempts fail as if another process held the file. */
	private static AtomicFileWriter lockedFor(int denials, AtomicInteger attempts) {
		return new AtomicFileWriter(3, 1) {
			@Override
			protected void move(Path source, Path target) throws IOException {
				if (attempts.incrementAndGet() <= denials) {
					throw new AccessDeniedException(target.toString());
				}
				super.move(source, target);
			}
		};
	}

	private long filesIn(Path directory) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.count();
		}
	}

	@Test
	void replacesTheFile() throws IOException {
		Path file = dir.resolve("a.txt");
		Files.write(file, "old".getBytes(StandardCharsets.UTF_8));

		new AtomicFileWriter().write(file, "new".getBytes(StandardCharsets.UTF_8));

		assertEquals("new", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		assertEquals(1, filesIn(dir));
	}

	@Test
	void retriesWhileTheFileIsBrieflyLocked() throws IOException {
		Path file = dir.resolve("a.txt");
		Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
		AtomicInteger attempts = new AtomicInteger();

		lockedFor(2, attempts).write(file, "new".getBytes(StandardCharsets.UTF_8));

		assertEquals(3, attempts.get());
		assertEquals("new", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		assertEquals(1, filesIn(dir));
	}

	@Test
	void givesUpAfterTheLastAttemptAndLeavesNoTemporaryFile() throws IOException {
		Path file = dir.resolve("a.txt");
		Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
		AtomicInteger attempts = new AtomicInteger();
		AtomicFileWriter writer = lockedFor(Integer.MAX_VALUE, attempts);
		byte[] content = "new".getBytes(StandardCharsets.UTF_8);

		assertThrows(AccessDeniedException.class, () -> writer.write(file, content));

		assertEquals(3, attempts.get());
		assertEquals("old", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		assertEquals(1, filesIn(dir));
	}

	@Test
	void doesNotRetryOtherFailures() {
		Path file = dir.resolve("a.txt");
		AtomicInteger attempts = new AtomicInteger();
		AtomicFileWriter writer = new AtomicFileWriter(3, 1) {
			@Override
			protected void move(Path source, Path target) throws IOException {
				attempts.incrementAndGet();
				throw new IOException("disk full");
			}
		};
		byte[] content = "new".getBytes(StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> writer.write(file, content));

		assertEquals(1, attempts.get());
	}

	@Test
	void rejectsInvalidSettings() {
		assertThrows(IllegalArgumentException.class, () -> new AtomicFileWriter(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new AtomicFileWriter(1, -1));
	}
}
