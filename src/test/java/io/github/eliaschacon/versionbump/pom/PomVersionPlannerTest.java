package io.github.eliaschacon.versionbump.pom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.eliaschacon.versionbump.io.FileChange;

class PomVersionPlannerTest {

	private final PomVersionPlanner planner = new PomVersionPlanner(new SystemStreamLog());
	@TempDir
	Path dir;

	static String pom(String parent, String groupId, String artifactId, String version, String extra) {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n"
			+ "  <modelVersion>4.0.0</modelVersion>\n"
			+ (parent == null ? "" : "  <parent>\n    " + parent + "\n  </parent>\n")
			+ (groupId == null ? "" : "  <groupId>" + groupId + "</groupId>\n")
			+ "  <artifactId>" + artifactId + "</artifactId>\n"
			+ (version == null ? "" : "  <version>" + version + "</version>\n")
			+ (extra == null ? "" : extra)
			+ "</project>\n";
	}

	static String parent(String groupId, String artifactId, String version) {
		return "<groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>" + version + "</version>";
	}

	private static String text(FileChange change) {
		return new String(change.getNewBytes(), StandardCharsets.UTF_8);
	}

	private static FileChange find(List<FileChange> changes, PomFile pom) {
		for (FileChange change : changes) {
			if (change.getPath().equals(pom.getPath())) {
				return change;
			}
		}
		throw new AssertionError("No change for " + pom.getPath());
	}

	private PomFile write(String relative, String content) throws IOException, BumpException {
		Path file = dir.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return PomFile.load(file);
	}

	@Test
	void updatesOnlyProjectVersion() throws Exception {
		String deps = "  <dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId>"
			+ "<version>1.2.3</version></dependency></dependencies>\n"
			+ "  <build><plugins><plugin><groupId>io.github.eliaschacon</groupId>"
			+ "<artifactId>version-propagator-maven-plugin</artifactId><version>1.2.3</version></plugin></plugins></build>\n";
		String original = pom(null, "g", "app", "1.2.3", deps);
		PomFile root = write("pom.xml", original);

		List<FileChange> changes = planner.plan(root, Collections.<PomFile>emptyList(), "1.2.3", "1.2.4", true);

		assertEquals(1, changes.size());
		assertEquals(original.replace("<artifactId>app</artifactId>\n  <version>1.2.3</version>",
			"<artifactId>app</artifactId>\n  <version>1.2.4</version>"), text(changes.get(0)));
		assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), changes.get(0).getOriginalBytes());
		// the POM on disk is untouched by planning
		assertEquals(original, new String(Files.readAllBytes(root.getPath()), StandardCharsets.UTF_8));
	}

	@Test
	void rejectsVersionInheritedFromParent() throws Exception {
		PomFile root = write("pom.xml", pom(parent("org.p", "parent", "3.0.0"), null, "child", null, null));
		BumpException e = assertThrows(BumpException.class, () -> PomVersionPlanner.explicitVersion(root));
		assertTrue(e.getMessage().contains("inherited from the parent org.p:parent:3.0.0"), e.getMessage());
		assertTrue(e.getMessage().contains("declare <version> explicitly"), e.getMessage());
	}

	@Test
	void rejectsPropertyVersion() throws Exception {
		PomFile root = write("pom.xml", pom(null, "g", "app", "${revision}", null));
		BumpException e = assertThrows(BumpException.class, () -> PomVersionPlanner.explicitVersion(root));
		assertTrue(e.getMessage().contains("${revision}"));
	}

	@Test
	void rejectsMismatchBetweenPomAndProjectVersion() throws Exception {
		PomFile root = write("pom.xml", pom(null, "g", "app", "1.0.0", null));
		assertThrows(BumpException.class,
			() -> planner.plan(root, Collections.<PomFile>emptyList(), "2.0.0", "2.0.1", true));
	}

	@Test
	void preservesDeclaredEncoding() throws Exception {
		Path file = dir.resolve("pom.xml");
		String content = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<project><name>Ação</name>"
			+ "<groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version></project>";
		Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
		PomFile root = PomFile.load(file);

		FileChange change = planner.plan(root, Collections.<PomFile>emptyList(), "1.0.0", "1.1.0", true).get(0);

		assertArrayEquals(content.replace("1.0.0", "1.1.0").getBytes(StandardCharsets.ISO_8859_1), change.getNewBytes());
	}

	@Test
	void appliesModuleRuleTransitively() throws Exception {
		String dep = "  <dependencies><dependency><groupId>g</groupId><artifactId>b</artifactId>"
			+ "<version>1.0.0</version></dependency></dependencies>\n";
		PomFile root = write("pom.xml", pom(null, "g", "root", "1.0.0", null));
		PomFile a = write("a/pom.xml", pom(parent("g", "root", "1.0.0"), null, "a", null, dep));
		PomFile b = write("b/pom.xml", pom(parent("g", "root", "1.0.0"), null, "b", "1.0.0", null));
		PomFile c = write("c/pom.xml", pom(parent("g", "root", "1.0.0"), null, "c", "2.0.0", null));
		PomFile d = write("c/d/pom.xml", pom(parent("g", "c", "2.0.0"), null, "d", null, null));
		PomFile e = write("a/e/pom.xml", pom(parent("g", "a", "1.0.0"), null, "e", null, null));
		PomFile f = write("f/pom.xml", pom(parent("org.other", "root", "1.0.0"), "g", "f", "1.0.0", null));
		PomFile old = write("old/pom.xml", pom(parent("g", "root", "0.9.0"), null, "old", null, null));

		// modules deliberately listed before their parents
		List<FileChange> changes = planner.plan(root, Arrays.asList(e, d, a, b, c, f, old), "1.0.0", "1.1.0", true);

		assertEquals(5, changes.size());
		assertTrue(text(find(changes, root)).contains("<version>1.1.0</version>"));
		// a: parent updated, dependency untouched
		String aText = text(find(changes, a));
		assertTrue(aText.contains(parent("g", "root", "1.1.0")));
		assertTrue(aText.contains("<artifactId>b</artifactId><version>1.0.0</version>"));
		// b: parent and own version
		assertEquals(pom(parent("g", "root", "1.1.0"), null, "b", "1.1.0", null), text(find(changes, b)));
		// c: parent only, own version kept
		assertEquals(pom(parent("g", "root", "1.1.0"), null, "c", "2.0.0", null), text(find(changes, c)));
		// e: grandchild through a
		assertEquals(pom(parent("g", "a", "1.1.0"), null, "e", null, null), text(find(changes, e)));
		// d (parent c keeps 2.0.0), f (external parent) and old (old parent version) are untouched
		for (FileChange change : changes) {
			assertTrue(!change.getPath().equals(d.getPath()) && !change.getPath().equals(f.getPath())
				&& !change.getPath().equals(old.getPath()));
		}
	}

	@Test
	void updatesOnlyRootWhenModuleUpdatesDisabled() throws Exception {
		PomFile root = write("pom.xml", pom(null, "g", "root", "1.0.0", null));
		PomFile a = write("a/pom.xml", pom(parent("g", "root", "1.0.0"), null, "a", null, null));

		List<FileChange> changes = planner.plan(root, Collections.singletonList(a), "1.0.0", "2.0.0", false);

		assertEquals(1, changes.size());
		assertEquals(root.getPath(), changes.get(0).getPath());
	}
}
