package io.github.eliaschacon.versionbump.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.BumpRequest;
import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.version.Version;

class HookTest {

	@TempDir
	Path dir;

	private MavenProject project(String configuration) throws Exception {
		Path pom = dir.resolve("pom.xml");
		if (!Files.exists(pom)) {
			write(pom, "1.0.0-SNAPSHOT");
		}
		Model model = new Model();
		model.setGroupId("g");
		model.setArtifactId("a");
		model.setVersion("1.0.0-SNAPSHOT");
		Build build = new Build();
		if (configuration != null) {
			Plugin plugin = new Plugin();
			plugin.setGroupId("io.github.eliaschacon");
			plugin.setArtifactId("version-propagator-maven-plugin");
			plugin.setConfiguration(Xpp3DomBuilder.build(new StringReader(configuration)));
			build.addPlugin(plugin);
		}
		model.setBuild(build);
		MavenProject project = new MavenProject(model);
		project.setFile(pom.toFile());
		return project;
	}

	private static void write(Path pom, String version) throws IOException {
		Files.write(pom, ("<project><groupId>g</groupId><artifactId>a</artifactId><version>" + version
			+ "</version></project>").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void readsThePluginConfiguration() throws Exception {
		MavenProject project = project("<configuration>"
			+ "<files><file>README.md</file></files>"
			+ "<includes><include>**/*.yaml</include></includes>"
			+ "<excludes><exclude>legacy/**</exclude></excludes>"
			+ "<failOnNoMatch>true</failOnNoMatch><createBackup>true</createBackup><encoding>ISO-8859-1</encoding>"
			+ "<hookGoals><hookGoal>versions:set</hookGoal></hookGoals>"
			+ "<replacements><replacement><files><file>README.md</file></files>"
			+ "<search>(v)@oldVersion@</search><replace> $1@newVersion@ </replace></replacement>"
			+ "<replacement/></replacements>"
			+ "</configuration>");

		HookConfig config = HookConfig.read(project, Collections.<Path>emptyList(), new Properties());

		BumpRequest request = config.getRequest();
		assertFalse(config.isSkip());
		assertEquals(Collections.singletonList("versions:set"), config.getGoals());
		assertEquals(Collections.singletonList(dir.resolve("README.md")), request.getFiles());
		assertEquals(Collections.singletonList("**/*.yaml"), request.getIncludes());
		assertEquals(Collections.singletonList("legacy/**"), request.getExcludes());
		assertTrue(request.isFailOnNoMatch());
		assertTrue(request.isCreateBackup());
		assertTrue(request.isUpdateFiles());
		assertFalse(request.isUpdatePom());
		assertEquals("ISO-8859-1", request.getEncoding());
		ReplacementRule rule = request.getReplacements().get(0);
		assertEquals("(v)@oldVersion@", rule.getSearch());
		// Maven trims element text when it reads the POM, for the goals and for the hook alike.
		assertEquals("$1@newVersion@", rule.getReplace());
		assertEquals(Collections.singletonList("README.md"), rule.getFiles());
		assertTrue(request.getReplacements().get(1).usesDefaultSearch());
	}

	@Test
	void usesDefaultsAndUserProperties() throws Exception {
		Properties user = new Properties();
		user.setProperty("bump.dryRun", "true");

		HookConfig config = HookConfig.read(project(null), Collections.<Path>emptyList(), user);

		assertEquals(HookConfig.DEFAULT_GOALS, config.getGoals());
		assertTrue(config.getRequest().isDryRun());
		assertTrue(config.getRequest().getFiles().isEmpty());
		assertTrue(config.getRequest().isUseDefaultExcludes());

		user.setProperty("bump.hook.skip", "true");
		assertTrue(HookConfig.read(project("<configuration/>"), Collections.<Path>emptyList(), user).isSkip());
		assertTrue(HookConfig.read(project("<configuration><hookSkip>true</hookSkip></configuration>"),
			Collections.<Path>emptyList(), new Properties()).isSkip());
	}

	@Test
	void comparesTheVersionsBeforeAndAfterTheGoals() throws Exception {
		Path core = dir.resolve("core.xml");
		Path api = dir.resolve("api.xml");
		Path same = dir.resolve("same.xml");
		write(core, "1.1.0-SNAPSHOT");
		write(api, "1.1.0-SNAPSHOT");
		write(same, "3.0.0");
		VersionHookParticipant hook = new VersionHookParticipant();
		hook.before().put(core, "1.0.0-SNAPSHOT");
		hook.before().put(api, "1.0.0-SNAPSHOT");
		hook.before().put(same, "3.0.0");

		Map<Version, Version> changes = hook.changes();

		assertEquals(1, changes.size());
		assertEquals(Version.parse("1.1.0-SNAPSHOT"), changes.get(Version.parse("1.0.0-SNAPSHOT")));

		write(api, "2.0.0");
		BumpException e = assertThrows(BumpException.class, hook::changes);
		assertTrue(e.getMessage().contains("Ambiguous"), e.getMessage());
	}

	@Test
	void doesNothingWithoutRecordedProjects() throws Exception {
		VersionHookParticipant hook = new VersionHookParticipant();
		assertTrue(hook.changes().isEmpty());
		assertNull(HookConfig.read(project(null), Arrays.<Path>asList(), new Properties()).getRequest().getNewVersion());
	}
}
