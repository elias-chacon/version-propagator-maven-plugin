package io.github.eliaschacon.versionbump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.eliaschacon.versionbump.concurrent.SequentialTestRunner;
import io.github.eliaschacon.versionbump.concurrent.TaskRunner;
import io.github.eliaschacon.versionbump.files.FileReplacer;
import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.version.Version;

/**
 * End-to-end tests of the bump workflow on temporary projects.
 */
class BumpServiceTest {

	static final String POM = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
		+ "<project>\n"
		+ "  <!-- the project -->\n"
		+ "  <modelVersion>4.0.0</modelVersion>\n"
		+ "  <groupId>com.acme</groupId>\n"
		+ "  <artifactId>app</artifactId>\n"
		+ "  <version>1.2.3</version>\n"
		+ "  <build><plugins><plugin>\n"
		+ "    <groupId>io.github.eliaschacon</groupId>\n"
		+ "    <artifactId>version-propagator-maven-plugin</artifactId>\n"
		+ "    <version>1.2.3</version>\n"
		+ "  </plugin></plugins></build>\n"
		+ "</project>\n";
	private final BumpService service = new BumpService(new SystemStreamLog(), new SequentialTestRunner());
	@TempDir
	Path base;

	@BeforeEach
	void createProject() throws IOException {
		write("pom.xml", POM);
		write("config/app.yaml", "app:\n  version: 1.2.3\n  other: 11.2.30\n");
		write("config/app.properties", "version=1.2.3\n");
		write("config/none.yaml", "nothing here\n");
		write("config/secret/keys.yaml", "version: 1.2.3\n");
		write("target/classes/app.yaml", "version: 1.2.3\n");
		write("README.md", "Use version 1.2.3 (not 1.2.3-SNAPSHOT)\n");
		Files.write(base.resolve("config/logo.bin"), new byte[]{'1', '.', '2', '.', '3', 0, 0});
	}

	private void write(String relative, String content) throws IOException {
		Path file = base.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String relative) throws IOException {
		return new String(Files.readAllBytes(base.resolve(relative)), StandardCharsets.UTF_8);
	}

	private Map<String, byte[]> snapshot() throws IOException {
		Map<String, byte[]> contents = new HashMap<>();
		try (Stream<Path> files = Files.walk(base)) {
			for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
				contents.put(base.relativize(file).toString(), Files.readAllBytes(file));
			}
		}
		return contents;
	}

	private void assertUnchanged(Map<String, byte[]> before) throws IOException {
		Map<String, byte[]> after = snapshot();
		assertEquals(before.keySet(), after.keySet());
		for (Map.Entry<String, byte[]> entry : before.entrySet()) {
			assertTrue(Arrays.equals(entry.getValue(), after.get(entry.getKey())), entry.getKey() + " changed");
		}
	}

	private BumpRequest.BumpRequestBuilder request(String part) {
		return BumpRequest.builder().baseDir(base).rootPom(base.resolve("pom.xml")).projectVersion("1.2.3").part(part);
	}

	private BumpResult run(BumpRequest.BumpRequestBuilder request) throws BumpException {
		return service.execute(request.build());
	}

	@Test
	void updatesPomOnly() throws Exception {
		BumpResult result = run(request("minor"));

		assertEquals("1.3.0", result.getNewVersion().toString());
		assertTrue(result.isWritten());
		assertEquals(POM.replace("<artifactId>app</artifactId>\n  <version>1.2.3</version>",
			"<artifactId>app</artifactId>\n  <version>1.3.0</version>"), read("pom.xml"));
		assertTrue(read("pom.xml").contains("<artifactId>version-propagator-maven-plugin</artifactId>\n    <version>1.2.3</version>"),
			"the plugin version must not change");
		assertEquals("version=1.2.3\n", read("config/app.properties"));
	}

	@Test
	void updatesPomAndFilesWithIncludesAndExcludes() throws Exception {
		BumpResult result = run(request("patch").updateFiles(true)
			.includes(Arrays.asList("**/*.yaml", "**/*.properties", "**/*.bin", "*.xml"))
			.excludes(Collections.singletonList("**/secret/**")));

		assertEquals("1.2.4", result.getNewVersion().toString());
		assertEquals("app:\n  version: 1.2.4\n  other: 11.2.30\n", read("config/app.yaml"));
		assertEquals("version=1.2.4\n", read("config/app.properties"));
		assertEquals("version: 1.2.3\n", read("config/secret/keys.yaml"), "excluded");
		assertEquals("version: 1.2.3\n", read("target/classes/app.yaml"), "target/ is excluded by default");
		assertEquals("Use version 1.2.3 (not 1.2.3-SNAPSHOT)\n", read("README.md"), "not included");
		// pom.xml matched *.xml but is only changed by the POM update (plugin version untouched)
		assertTrue(read("pom.xml").contains("<artifactId>version-propagator-maven-plugin</artifactId>\n    <version>1.2.3</version>"));
		assertTrue(read("pom.xml").contains("<artifactId>app</artifactId>\n  <version>1.2.4</version>"));

		Map<String, FileReplacer.Status> statuses = new HashMap<>();
		for (FileReplacer.Outcome outcome : result.getFileOutcomes()) {
			statuses.put(base.relativize(outcome.getPath()).toString().replace('\\', '/'), outcome.getStatus());
		}
		assertEquals(FileReplacer.Status.CHANGED, statuses.get("config/app.yaml"));
		assertEquals(FileReplacer.Status.NO_MATCH, statuses.get("config/none.yaml"));
		assertEquals(FileReplacer.Status.SKIPPED_BINARY, statuses.get("config/logo.bin"));
		assertEquals(3, result.getChanges().size());
	}

	@Test
	void dryRunWritesNothing() throws Exception {
		Map<String, byte[]> before = snapshot();

		BumpResult result = run(request("major").dryRun(true).createBackup(true).updateFiles(true)
			.includes(Arrays.asList("**/*.yaml", "**/*.md")));

		assertUnchanged(before);
		assertFalse(result.isWritten());
		assertEquals("2.0.0", result.getNewVersion().toString());
		assertTrue(result.changes(base.resolve("pom.xml")));
		assertTrue(result.changes(base.resolve("config/app.yaml")));
		assertTrue(result.changes(base.resolve("README.md")));
	}

	@Test
	void explicitFilesAreProcessed() throws Exception {
		run(request("build").updatePom(false).updateFiles(true)
			.files(Arrays.asList(Paths.of(base, "README.md"), Paths.of(base, "config/app.properties"))));

		assertEquals("Use version 1.2.3.1 (not 1.2.3-SNAPSHOT)\n", read("README.md"));
		assertEquals("version=1.2.3.1\n", read("config/app.properties"));
		assertEquals(POM, read("pom.xml"), "updatePom=false");
	}

	@Test
	void relativeExplicitFilesAndDirectoriesAreResolvedAgainstBaseDir() throws Exception {
		run(request("patch").updatePom(false).updateFiles(true)
			.files(Collections.singletonList(Paths.of(null, "README.md")))
			.directories(Collections.singletonList(Paths.of(null, "config")))
			.includes(Collections.singletonList("*.properties")));
		assertTrue(read("README.md").contains("1.2.4"));
		assertEquals("version=1.2.4\n", read("config/app.properties"));
	}

	@Test
	void createsBackups() throws Exception {
		run(request("patch").createBackup(true).updateFiles(true)
			.includes(Collections.singletonList("config/*.properties")));

		assertEquals(POM, read("pom.xml.bak"));
		assertEquals("version=1.2.3\n", read("config/app.properties.bak"));
		assertEquals("version=1.2.4\n", read("config/app.properties"));
	}

	@Test
	void existingBackupPreventsAnyWrite() throws Exception {
		write("config/app.properties.bak", "precious");
		Map<String, byte[]> before = snapshot();

		BumpException e = assertThrows(BumpException.class, () -> run(request("patch").createBackup(true)
			.updateFiles(true).includes(Collections.singletonList("config/*.properties"))));

		assertTrue(e.getMessage().contains("Backup already exists"));
		assertUnchanged(before);
	}

	@Test
	void failOnNoMatchStopsBeforeWritingThePom() throws Exception {
		Map<String, byte[]> before = snapshot();

		BumpException e = assertThrows(BumpException.class, () -> run(request("patch").updateFiles(true)
			.failOnNoMatch(true).files(Collections.singletonList(base.resolve("config/none.yaml")))));

		assertTrue(e.getMessage().contains("No occurrence of version 1.2.3"));
		assertUnchanged(before);
	}

	@Test
	void noMatchIsNotAnErrorByDefault() throws Exception {
		BumpResult result = run(request("patch").updateFiles(true)
			.files(Collections.singletonList(base.resolve("config/none.yaml"))));
		assertEquals(1, result.getChanges().size());
	}

	@Test
	void missingDirectoryFails() throws Exception {
		Map<String, byte[]> before = snapshot();
		BumpException e = assertThrows(BumpException.class, () -> run(request("patch").updateFiles(true)
			.directories(Collections.singletonList(base.resolve("nope")))
			.includes(Collections.singletonList("**/*.yaml"))));
		assertTrue(e.getMessage().contains("does not exist"));
		assertUnchanged(before);
	}

	@Test
	void missingFileFails() {
		BumpException e = assertThrows(BumpException.class, () -> run(request("patch").updateFiles(true)
			.files(Collections.singletonList(base.resolve("nope.txt")))));
		assertTrue(e.getMessage().contains("does not exist"));
	}

	@Test
	void filesOutsideTheProjectAreRejected() throws Exception {
		Path outside = base.getParent().resolve(base.getFileName() + "-outside.txt");
		Files.write(outside, "1.2.3".getBytes(StandardCharsets.UTF_8));
		try {
			BumpException e = assertThrows(BumpException.class, () -> run(request("patch").updateFiles(true)
				.files(Collections.singletonList(outside))));
			assertTrue(e.getMessage().contains("outside the project"));
			assertEquals("1.2.3", new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
		} finally {
			Files.delete(outside);
		}
	}

	@Test
	void invalidConfigurationsFailClearly() {
		assertThrows(BumpException.class, () -> run(request("patch").updateFiles(true)));
		assertThrows(BumpException.class, () -> run(request("patch").updateFiles(true)
			.directories(Collections.singletonList(base.resolve("config")))));
		assertThrows(BumpException.class, () -> run(request("patch").updatePom(false)));
		assertThrows(BumpException.class, () -> run(request("micro")));
		assertThrows(BumpException.class, () -> run(request("patch").encoding("NOPE-42")));
		assertThrows(BumpException.class, () -> run(request("patch").qualifierPolicy("keep")));
	}

	@Test
	void customRulesWithTokens() throws Exception {
		write("config/build.properties", "app.version=1.2.3\napp.major=1\nlink=https://x/1.2.3/\n");

		run(request("major").updatePom(false).updateFiles(true)
			.files(Collections.singletonList(base.resolve("config/build.properties")))
			.replacements(Arrays.asList(
				new ReplacementRule("(app\\.version=)${oldVersion}", "$1${newVersion}"),
				new ReplacementRule("app\\.major=@oldMajor@", "app.major=@newMajor@"))));

		assertEquals("app.version=2.0.0\napp.major=2\nlink=https://x/1.2.3/\n", read("config/build.properties"));
	}

	@Test
	void perFileRulesSelectTheirOwnFiles() throws Exception {
		ReplacementRule yaml = new ReplacementRule("(version: )@oldVersion@", "$1@newVersion@ # yaml");
		yaml.setFiles(Collections.singletonList("config/app.yaml"));
		ReplacementRule readme = new ReplacementRule(); // default search, scoped to README.md
		readme.setFiles(Collections.singletonList("README.md"));

		BumpResult result = run(request("patch").updateFiles(true).replacements(Arrays.asList(yaml, readme)));

		assertEquals("app:\n  version: 1.2.4 # yaml\n  other: 11.2.30\n", read("config/app.yaml"));
		assertEquals("Use version 1.2.4 (not 1.2.3-SNAPSHOT)\n", read("README.md"));
		assertEquals("version=1.2.3\n", read("config/app.properties"), "not selected by any rule");
		assertEquals(3, result.getChanges().size(), "pom.xml + 2 files");
	}

	@Test
	void scopedAndGlobalRulesCombine() throws Exception {
		ReplacementRule everywhere = new ReplacementRule(); // unscoped default rule
		ReplacementRule yamlOnly = new ReplacementRule("other: 11\\.2\\.30", "other: @newMajor@.@newMinor@.@newPatch@");
		yamlOnly.setIncludes(Collections.singletonList("config/*.yaml"));
		yamlOnly.setExcludes(Collections.singletonList("config/none.yaml"));

		BumpResult result = run(request("patch").updatePom(false).updateFiles(true)
			.includes(Arrays.asList("config/*.yaml", "config/*.properties"))
			.replacements(Arrays.asList(everywhere, yamlOnly)));

		assertEquals("app:\n  version: 1.2.4\n  other: 1.2.4\n", read("config/app.yaml"));
		assertEquals("version=1.2.4\n", read("config/app.properties"));
		assertEquals(2, result.getChanges().size());
	}

	@Test
	void filesOutsideEveryRuleScopeAreReportedAsNoRule() throws Exception {
		ReplacementRule yaml = new ReplacementRule();
		yaml.setFiles(Collections.singletonList("config/app.yaml"));

		BumpResult result = run(request("patch").updatePom(false).updateFiles(true)
			.includes(Collections.singletonList("config/*.properties")).replacements(Collections.singletonList(yaml)));

		Map<String, FileReplacer.Status> statuses = new HashMap<>();
		for (FileReplacer.Outcome outcome : result.getFileOutcomes()) {
			statuses.put(base.relativize(outcome.getPath()).toString().replace('\\', '/'), outcome.getStatus());
		}
		assertEquals(FileReplacer.Status.CHANGED, statuses.get("config/app.yaml"));
		assertEquals(FileReplacer.Status.NO_RULE, statuses.get("config/app.properties"));
		assertEquals("version=1.2.3\n", read("config/app.properties"));
	}

	@Test
	void ruleIncludesSelectFilesThemselves() throws Exception {
		write("docs/install.md", "Install 1.2.3\n");
		write("docs/old/legacy.md", "Install 1.2.3\n");
		write("target/docs/copy.md", "Install 1.2.3\n");
		ReplacementRule docs = new ReplacementRule(); // default search, only docs
		docs.setIncludes(Collections.singletonList("docs/**/*.md"));
		docs.setExcludes(Collections.singletonList("docs/old/**"));

		run(request("patch").updatePom(false).updateFiles(true).replacements(Collections.singletonList(docs)));

		assertEquals("Install 1.2.4\n", read("docs/install.md"));
		assertEquals("Install 1.2.3\n", read("docs/old/legacy.md"), "rule exclude");
		assertEquals("Install 1.2.3\n", read("target/docs/copy.md"), "default exclude");
		assertEquals("Use version 1.2.3 (not 1.2.3-SNAPSHOT)\n", read("README.md"), "outside the rule");
	}

	@Test
	void missingRuleFileFailsBeforeWriting() throws Exception {
		ReplacementRule rule = new ReplacementRule();
		rule.setFiles(Collections.singletonList("config/missing.yaml"));
		Map<String, byte[]> before = snapshot();

		BumpException e = assertThrows(BumpException.class,
			() -> run(request("patch").updateFiles(true).replacements(Collections.singletonList(rule))));

		assertTrue(e.getMessage().contains("rule file does not exist"), e.getMessage());
		assertUnchanged(before);
	}

	@Test
	void ruleFilesDoNotBypassThePomProtection() throws Exception {
		ReplacementRule rule = new ReplacementRule();
		rule.setFiles(Collections.singletonList("pom.xml"));

		run(request("patch").updateFiles(true).replacements(Collections.singletonList(rule)));

		assertTrue(read("pom.xml").contains("<artifactId>version-propagator-maven-plugin</artifactId>\n    <version>1.2.3</version>"));
		assertTrue(read("pom.xml").contains("<artifactId>app</artifactId>\n  <version>1.2.4</version>"));
	}

	@Test
	void plansEveryFileThroughTheTaskRunnerAndKeepsItsOrder() throws Exception {
		List<Integer> batches = new ArrayList<>();
		TaskRunner reversing = new TaskRunner() {
			@Override
			public String name() {
				return "reversing";
			}

			@Override
			public <T> List<T> runAll(List<Task<T>> tasks) throws BumpException {
				batches.add(tasks.size());
				// Executes in reverse order, returns results in task order (as the contract requires).
				Object[] results = new Object[tasks.size()];
				for (int i = tasks.size() - 1; i >= 0; i--) {
					results[i] = tasks.get(i).call();
				}
				List<T> ordered = new ArrayList<>();
				for (Object result : results) {
					@SuppressWarnings("unchecked")
					T value = (T) result;
					ordered.add(value);
				}
				return ordered;
			}
		};

		BumpResult result = new BumpService(new SystemStreamLog(), reversing).execute(request("patch")
			.updatePom(false).updateFiles(true).includes(Collections.singletonList("config/*.yaml")).build());

		assertEquals(Collections.singletonList(2), batches);
		List<String> names = new ArrayList<>();
		for (FileReplacer.Outcome outcome : result.getFileOutcomes()) {
			names.add(base.relativize(outcome.getPath()).toString().replace('\\', '/'));
		}
		assertEquals(Arrays.asList("config/app.yaml", "config/none.yaml"), names);
		assertEquals("app:\n  version: 1.2.4\n  other: 11.2.30\n", read("config/app.yaml"));
	}

	@Test
	void setsAnExplicitVersionOrAReleaseCandidate() throws Exception {
		BumpResult rc = run(request("minor").qualifier("RC1").updateFiles(true)
			.files(Collections.singletonList(base.resolve("config/app.properties"))));
		assertEquals("1.3.0-RC1", rc.getNewVersion().toString());
		assertTrue(read("pom.xml").contains("<artifactId>app</artifactId>\n  <version>1.3.0-RC1</version>"));
		assertEquals("version=1.3.0-RC1\n", read("config/app.properties"));

		BumpResult snapshot = run(request(null).projectVersion("1.3.0-RC1").newVersion("1.3.0-SNAPSHOT"));
		assertEquals("1.3.0-SNAPSHOT", snapshot.getNewVersion().toString());
		assertTrue(read("pom.xml").contains("<artifactId>app</artifactId>\n  <version>1.3.0-SNAPSHOT</version>"));

		BumpResult release = run(request("release").projectVersion("1.3.0-SNAPSHOT"));
		assertEquals("1.3.0", release.getNewVersion().toString());
		BumpResult next = run(request("patch").projectVersion("1.3.0").snapshot(true));
		assertEquals("1.3.1-SNAPSHOT", next.getNewVersion().toString());
		assertTrue(read("pom.xml").contains("<artifactId>app</artifactId>\n  <version>1.3.1-SNAPSHOT</version>"));
	}

	private static Map<Version, Version> change(String... versions) throws BumpException {
		Map<Version, Version> changes = new LinkedHashMap<>();
		for (int i = 0; i < versions.length; i += 2) {
			changes.put(Version.parse(versions[i]), Version.parse(versions[i + 1]));
		}
		return changes;
	}

	@Test
	void propagatesToTheConfiguredFilesWithoutTouchingThePoms() throws Exception {
		BumpResult result = service.propagate(request(null).updatePom(false).updateFiles(true)
			.includes(Collections.singletonList("config/*.yaml")).build(), change("1.2.3", "2.0.0-SNAPSHOT"));

		assertTrue(result.isWritten());
		assertEquals("app:\n  version: 2.0.0-SNAPSHOT\n  other: 11.2.30\n", read("config/app.yaml"));
		assertEquals(POM, read("pom.xml"));
		assertEquals("version=1.2.3\n", read("config/app.properties"), "not configured");
	}

	@Test
	void propagatesEveryDistinctPair() throws Exception {
		write("config/modules.properties", "core=1.2.3\napi=0.9.0\nother=0.8.0\n");

		BumpResult result = service.propagate(request(null).updateFiles(true)
				.files(Collections.singletonList(base.resolve("config/modules.properties"))).build(),
			change("1.2.3", "1.3.0", "0.9.0", "0.10.0", "0.8.0", "0.8.0"));

		assertEquals("core=1.3.0\napi=0.10.0\nother=0.8.0\n", read("config/modules.properties"));
		assertEquals("1.2.3", result.getOldVersion().toString());
	}

	@Test
	void propagationDoesNothingWithoutChangesOrConfiguredFiles() throws Exception {
		Map<String, byte[]> before = snapshot();

		assertNull(service.propagate(request(null).updateFiles(true)
			.includes(Collections.singletonList("**/*.yaml")).build(), change("1.2.3", "1.2.3")));
		assertNull(service.propagate(request(null).updateFiles(true).build(), change("1.2.3", "1.2.4")));
		assertNull(service.propagate(request(null).updateFiles(true)
			.replacements(Collections.singletonList(ReplacementRule.defaultRule())).build(), change("1.2.3", "1.2.4")));

		assertUnchanged(before);
	}

	@Test
	void propagationRejectsChainedChanges() throws Exception {
		Map<String, byte[]> before = snapshot();
		BumpRequest request = request(null).updateFiles(true).includes(Collections.singletonList("**/*.yaml")).build();
		Map<Version, Version> chained = change("1.2.3", "1.2.4", "1.2.4", "1.2.5");

		BumpException e = assertThrows(BumpException.class, () -> service.propagate(request, chained));

		assertTrue(e.getMessage().contains("Ambiguous"), e.getMessage());
		assertUnchanged(before);
	}

	@Test
	void propagationHonoursDryRunAndFailOnNoMatch() throws Exception {
		Map<String, byte[]> before = snapshot();
		BumpResult dryRun = service.propagate(request(null).dryRun(true).updateFiles(true)
			.includes(Collections.singletonList("**/*.yaml")).build(), change("1.2.3", "1.2.4"));
		assertFalse(dryRun.isWritten());
		assertTrue(dryRun.changes(base.resolve("config/app.yaml")));
		assertUnchanged(before);

		BumpRequest noMatch = request(null).updateFiles(true).failOnNoMatch(true)
			.files(Collections.singletonList(base.resolve("config/none.yaml"))).build();
		Map<Version, Version> changes = change("1.2.3", "1.2.4");
		BumpException e = assertThrows(BumpException.class, () -> service.propagate(noMatch, changes));
		assertTrue(e.getMessage().contains("No occurrence of version 1.2.3"), e.getMessage());
	}

	@Test
	void snapshotVersionsAreBumpedAndPreserved() throws Exception {
		write("pom.xml", POM.replace("<artifactId>app</artifactId>\n  <version>1.2.3</version>",
			"<artifactId>app</artifactId>\n  <version>1.2.3-SNAPSHOT</version>"));

		run(request("patch").projectVersion("1.2.3-SNAPSHOT").updateFiles(true)
			.files(Collections.singletonList(base.resolve("README.md"))));

		assertTrue(read("pom.xml").contains("<version>1.2.4-SNAPSHOT</version>"));
		assertEquals("Use version 1.2.3 (not 1.2.4-SNAPSHOT)\n", read("README.md"));
	}

	@Test
	void qualifiedVersionsAreRejectedByDefault() throws Exception {
		write("pom.xml", POM.replace("<version>1.2.3</version>\n  <build>", "<version>1.2.3-RC1</version>\n  <build>"));
		BumpException e = assertThrows(BumpException.class,
			() -> run(request("patch").projectVersion("1.2.3-RC1")));
		assertTrue(e.getMessage().contains("1.2.3-RC1"));
		run(request("patch").projectVersion("1.2.3-RC1").qualifierPolicy("remove"));
		assertTrue(read("pom.xml").contains("<version>1.2.4</version>\n  <build>"));
	}

	@Test
	void inheritedVersionFailsWithHint() throws Exception {
		write("pom.xml", "<project><parent><groupId>g</groupId><artifactId>p</artifactId><version>1.2.3</version>"
			+ "</parent><artifactId>child</artifactId></project>");
		BumpException e = assertThrows(BumpException.class, () -> run(request("patch")));
		assertTrue(e.getMessage().contains("inherited"));

		// files can still be bumped using the effective version
		run(request("patch").updatePom(false).updateFiles(true)
			.files(Collections.singletonList(base.resolve("config/app.properties"))));
		assertEquals("version=1.2.4\n", read("config/app.properties"));
	}

	@Test
	void multiModuleReactor() throws Exception {
		write("pom.xml", "<project><groupId>g</groupId><artifactId>root</artifactId><version>1.2.3</version>"
			+ "<packaging>pom</packaging><modules><module>core</module></modules></project>");
		write("core/pom.xml", "<project>\n  <parent><groupId>g</groupId><artifactId>root</artifactId>"
			+ "<version>1.2.3</version></parent>\n  <artifactId>core</artifactId>\n"
			+ "  <dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1.2.3</version>"
			+ "</dependency></dependencies>\n</project>");

		List<Path> modules = Collections.singletonList(base.resolve("core/pom.xml"));
		run(request("minor").modulePoms(modules).updateFiles(true).includes(Collections.singletonList("**/*.xml")));

		assertTrue(read("pom.xml").contains("<version>1.3.0</version>"));
		assertEquals("<project>\n  <parent><groupId>g</groupId><artifactId>root</artifactId>"
			+ "<version>1.3.0</version></parent>\n  <artifactId>core</artifactId>\n"
			+ "  <dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1.2.3</version>"
			+ "</dependency></dependencies>\n</project>", read("core/pom.xml"));
	}

	/**
	 * Small helper to build absolute or relative paths in tests.
	 */
	private static final class Paths {
		static Path of(Path base, String relative) {
			return base == null ? java.nio.file.Paths.get(relative) : base.resolve(relative);
		}
	}
}
