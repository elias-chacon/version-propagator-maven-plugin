package io.github.eliaschacon.versionbump.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.eliaschacon.versionbump.BumpException;

class ChangeApplierTest {

	@TempDir
	Path dir;

	private static FileChange change(Path file, String oldContent, String newContent) {
		return FileChange.builder().path(file).originalBytes(oldContent.getBytes(StandardCharsets.UTF_8))
			.newBytes(newContent.getBytes(StandardCharsets.UTF_8)).replacements(1).description("test").build();
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static ChangeApplier applier(AtomicFileWriter writer, boolean backup, boolean overwriteBackups) {
		return ChangeApplier.builder().writer(writer).createBackup(backup).overwriteBackups(overwriteBackups)
			.log(new SystemStreamLog()).build();
	}

	private Path file(String name, String content) throws IOException {
		Path file = dir.resolve(name);
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	@Test
	void writesAllFilesWithoutLeavingTemporaryFiles() throws Exception {
		Path a = file("a.txt", "1.0.0");
		Path b = file("b.txt", "v 1.0.0");

		applier(new AtomicFileWriter(), false, false).apply(Arrays.asList(change(a, "1.0.0", "1.1.0"),
			change(b, "v 1.0.0", "v 1.1.0")));

		assertEquals("1.1.0", read(a));
		assertEquals("v 1.1.0", read(b));
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			assertEquals(2, files.count());
		}
	}

	@Test
	void createsBackupsWithOriginalContent() throws Exception {
		Path a = file("a.txt", "1.0.0");

		applier(new AtomicFileWriter(), true, false).apply(Collections.singletonList(change(a, "1.0.0", "2.0.0")));

		assertEquals("2.0.0", read(a));
		assertEquals("1.0.0", read(dir.resolve("a.txt.bak")));
	}

	@Test
	void refusesToOverwriteExistingBackupUnlessAllowed() throws Exception {
		Path a = file("a.txt", "1.0.0");
		file("a.txt.bak", "old backup");
		List<FileChange> changes = Collections.singletonList(change(a, "1.0.0", "2.0.0"));

		BumpException e = assertThrows(BumpException.class,
			() -> applier(new AtomicFileWriter(), true, false).apply(changes));
		assertTrue(e.getMessage().contains("Backup already exists"));
		assertEquals("1.0.0", read(a));
		assertEquals("old backup", read(dir.resolve("a.txt.bak")));

		applier(new AtomicFileWriter(), true, true).apply(changes);
		assertEquals("1.0.0", read(dir.resolve("a.txt.bak")));
	}

	@Test
	void rollsBackWrittenFilesWhenAWriteFails() throws Exception {
		Path a = file("a.txt", "1.0.0");
		Path b = file("b.txt", "1.0.0");
		Path c = file("c.txt", "1.0.0");
		AtomicFileWriter failingOnB = new AtomicFileWriter() {
			@Override
			protected void move(Path source, Path target) throws IOException {
				if (target.getFileName().toString().equals("b.txt")) {
					throw new IOException("disk full");
				}
				super.move(source, target);
			}
		};

		BumpException e = assertThrows(BumpException.class, () -> applier(failingOnB, false, false).apply(
			Arrays.asList(change(a, "1.0.0", "2.0.0"), change(b, "1.0.0", "2.0.0"), change(c, "1.0.0", "2.0.0"))));

		assertTrue(e.getMessage().contains("disk full"), e.getMessage());
		assertTrue(e.getMessage().contains("Restored 1 of 1"), e.getMessage());
		assertEquals("1.0.0", read(a));
		assertEquals("1.0.0", read(b));
		assertEquals("1.0.0", read(c));
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			assertEquals(3, files.count(), "no temporary file left behind");
		}
	}

	@Test
	void failsWhenFileChangedAfterPlanning() throws Exception {
		Path a = file("a.txt", "edited");
		assertThrows(BumpException.class,
			() -> applier(new AtomicFileWriter(), false, false).apply(Collections.singletonList(change(a, "1.0.0", "2.0.0"))));
		assertEquals("edited", read(a));
	}

	@Test
	void failsOnReadOnlyFileBeforeWritingAnything() throws Exception {
		Path a = file("a.txt", "1.0.0");
		Path b = file("b.txt", "1.0.0");
		assumeTrue(b.toFile().setWritable(false), "cannot make file read-only");
		try {
			assumeTrue(!Files.isWritable(b), "read-only flag not honoured (running as root?)");
			BumpException e = assertThrows(BumpException.class, () -> applier(new AtomicFileWriter(), false, false)
				.apply(Arrays.asList(change(a, "1.0.0", "2.0.0"), change(b, "1.0.0", "2.0.0"))));
			assertTrue(e.getMessage().contains("not writable"));
			assertEquals("1.0.0", read(a));
		} finally {
			b.toFile().setWritable(true);
		}
	}

	@Test
	void failsOnMissingFile() {
		Path missing = dir.resolve("missing.txt");
		assertThrows(BumpException.class, () -> applier(new AtomicFileWriter(), false, false)
			.apply(Collections.singletonList(change(missing, "1", "2"))));
		assertFalse(Files.exists(missing));
	}

	@Test
	void rejectsDuplicatedChanges() throws Exception {
		Path a = file("a.txt", "1.0.0");
		assertThrows(BumpException.class, () -> applier(new AtomicFileWriter(), false, false)
			.apply(Arrays.asList(change(a, "1.0.0", "2.0.0"), change(a, "1.0.0", "2.0.0"))));
	}
}
