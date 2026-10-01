package io.github.eliaschacon.versionbump.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole hook cycle on a real session: versions recorded before the goals, the POM rewritten by "the goal",
 * then the new version propagated to the configured files at the end of the session.
 */
class VersionHookParticipantTest {

	private static final String CONFIGURATION = "<configuration><files><file>README.md</file></files>"
		+ "<failOnNoMatch>FAIL</failOnNoMatch></configuration>";

	@TempDir
	Path dir;

	private static void pom(Path file, String version) throws IOException {
		Files.write(file, ("<project><groupId>g</groupId><artifactId>" + file.getParent().getFileName()
			+ "</artifactId><version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws IOException {
		return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
	}

	private MavenSession session(String failOnNoMatch, String... goals) throws Exception {
		Path pom = dir.resolve("pom.xml");
		pom(pom, "1.0.0-SNAPSHOT");
		Files.write(dir.resolve("README.md"), "Version 1.0.0-SNAPSHOT, see 0.9.0\n".getBytes(StandardCharsets.UTF_8));
		Model model = new Model();
		model.setGroupId("g");
		model.setArtifactId("demo");
		model.setVersion("1.0.0-SNAPSHOT");
		Plugin plugin = new Plugin();
		plugin.setGroupId("io.github.eliaschacon");
		plugin.setArtifactId("version-propagator-maven-plugin");
		plugin.setConfiguration(Xpp3DomBuilder.build(new StringReader(CONFIGURATION.replace("FAIL", failOnNoMatch))));
		Build build = new Build();
		build.addPlugin(plugin);
		model.setBuild(build);
		MavenProject root = new MavenProject(model);
		root.setFile(pom.toFile());
		root.setExecutionRoot(true);

		MavenExecutionRequest request = new DefaultMavenExecutionRequest();
		request.setGoals(Arrays.asList(goals));
		List<MavenProject> projects = Collections.singletonList(root);
		@SuppressWarnings("deprecation")
		MavenSession session = new MavenSession(null, request, new DefaultMavenExecutionResult(), projects);
		return session;
	}

	@Test
	void propagatesTheNewVersionAfterAMonitoredGoal() throws Exception {
		MavenSession session = session("false", "release:update-versions");
		VersionHookParticipant hook = new VersionHookParticipant();

		hook.afterProjectsRead(session);
		pom(dir.resolve("pom.xml"), "1.2.0-SNAPSHOT");
		hook.afterSessionEnd(session);

		assertEquals("Version 1.2.0-SNAPSHOT, see 0.9.0\n", read("README.md"));
	}

	@Test
	void ignoresOtherGoalsAndFailedSessions() throws Exception {
		MavenSession other = session("false", "clean", "install");
		VersionHookParticipant hook = new VersionHookParticipant();
		hook.afterProjectsRead(other);
		pom(dir.resolve("pom.xml"), "1.2.0-SNAPSHOT");
		hook.afterSessionEnd(other);
		assertEquals("Version 1.0.0-SNAPSHOT, see 0.9.0\n", read("README.md"));

		MavenSession failed = session("false", "versions:set");
		failed.getResult().addException(new IllegalStateException("goal failed"));
		hook.afterProjectsRead(failed);
		pom(dir.resolve("pom.xml"), "1.2.0-SNAPSHOT");
		hook.afterSessionEnd(failed);
		assertEquals("Version 1.0.0-SNAPSHOT, see 0.9.0\n", read("README.md"));
	}

	@Test
	void reportsPropagationFailuresWithRecoveryHints() throws Exception {
		MavenSession session = session("true", "versions:set");
		Files.write(dir.resolve("README.md"), "no version\n".getBytes(StandardCharsets.UTF_8));
		VersionHookParticipant hook = new VersionHookParticipant();
		hook.afterProjectsRead(session);
		pom(dir.resolve("pom.xml"), "2.0.0");

		MavenExecutionException e = assertThrows(MavenExecutionException.class, () -> hook.afterSessionEnd(session));

		assertTrue(e.getMessage().contains("after versions:set"), e.getMessage());
		assertTrue(e.getMessage().contains("propagate:sync"), e.getMessage());
	}
}
