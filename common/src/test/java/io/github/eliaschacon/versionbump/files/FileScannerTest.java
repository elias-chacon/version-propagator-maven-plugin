package io.github.eliaschacon.versionbump.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.eliaschacon.versionbump.BumpException;

class FileScannerTest {

	@TempDir
	Path base;

	private void touch(String... relatives) throws IOException {
		for (String relative : relatives) {
			Path file = base.resolve(relative);
			Files.createDirectories(file.getParent());
			Files.write(file, "x".getBytes());
		}
	}

	private List<String> scan(List<String> includes, List<String> excludes, boolean followSymlinks, String... dirs)
		throws BumpException {
		List<String> allExcludes = new ArrayList<>(FileScanner.DEFAULT_EXCLUDES);
		allExcludes.addAll(excludes);
		FileScanner scanner = new FileScanner(base, GlobMatcher.of(includes), GlobMatcher.of(allExcludes),
			followSymlinks, new SystemStreamLog());
		List<Path> directories = new ArrayList<>();
		for (String dir : dirs) {
			directories.add(base.resolve(dir));
		}
		SortedSet<Path> found = scanner.scan(directories);
		List<String> relative = new ArrayList<>();
		for (Path path : found) {
			relative.add(FileScanner.relative(base.toAbsolutePath().normalize(), path));
		}
		return relative;
	}

	@Test
	void appliesIncludesExcludesAndDefaultExcludes() throws Exception {
		touch("app.yaml", "config/db.yaml", "config/app.properties", "config/secret/keys.yaml", "notes.txt",
			"target/classes/app.yaml", "module/target/app.yaml", ".git/config.yaml");

		List<String> found = scan(Arrays.asList("**/*.yaml", "**/*.properties"),
			Collections.singletonList("**/secret/**"), false, ".");

		assertEquals(Arrays.asList("app.yaml", "config/app.properties", "config/db.yaml"), found);
	}

	@Test
	void scansOnlyConfiguredDirectoriesRelativeToThem() throws Exception {
		touch("app.yaml", "config/db.yaml", "other/x.yaml");

		List<String> found = scan(Collections.singletonList("*.yaml"), Collections.<String>emptyList(), false, "config");

		assertEquals(Collections.singletonList("config/db.yaml"), found);
	}

	@Test
	void failsOnMissingDirectory() {
		BumpException e = assertThrows(BumpException.class,
			() -> scan(Collections.singletonList("**/*"), Collections.<String>emptyList(), false, "missing"));
		assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
	}

	@Test
	void failsOnFileConfiguredAsDirectory() throws Exception {
		touch("file.txt");
		BumpException e = assertThrows(BumpException.class,
			() -> scan(Collections.singletonList("**/*"), Collections.<String>emptyList(), false, "file.txt"));
		assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
	}

	@Test
	void failsOnDirectoryOutsideProject() {
		BumpException e = assertThrows(BumpException.class,
			() -> scan(Collections.singletonList("**/*"), Collections.<String>emptyList(), false, ".."));
		assertTrue(e.getMessage().contains("outside the project"), e.getMessage());
	}

	@Test
	void doesNotFollowSymbolicLinksByDefault() throws Exception {
		touch("real/app.yaml", "outside/linked.yaml");
		Path link = base.resolve("real/link.yaml");
		Path dirLink = base.resolve("real/dirlink");
		try {
			Files.createSymbolicLink(link, base.resolve("outside/linked.yaml"));
			Files.createSymbolicLink(dirLink, base.resolve("outside"));
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "Symbolic links not available: " + e);
		}

		assertEquals(Collections.singletonList("real/app.yaml"),
			scan(Collections.singletonList("**/*.yaml"), Collections.<String>emptyList(), false, "real"));
		assertEquals(Arrays.asList("real/app.yaml", "real/dirlink/linked.yaml", "real/link.yaml"),
			scan(Collections.singletonList("**/*.yaml"), Collections.<String>emptyList(), true, "real"));
	}

	@Test
	void requireInsideAcceptsNestedAndRejectsTraversal() throws Exception {
		assertTrue(FileScanner.requireInside(base, base.resolve("a/../b.txt"), "file").endsWith("b.txt"));
		assertThrows(BumpException.class, () -> FileScanner.requireInside(base, base.resolve("../x.txt"), "file"));
	}
}
