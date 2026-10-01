package io.github.eliaschacon.versionbump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sync goal: the POMs already hold the new version (as after {@code versions:set} or inside
 * {@code release:prepare}) and the configured files are brought in line. No SCM is involved.
 */
class SyncMojoTest {

	@TempDir
	Path dir;

	private static void pom(Path file, String version, boolean module) throws IOException {
		Files.createDirectories(file.getParent());
		String body = module
			? "<parent><groupId>g</groupId><artifactId>root</artifactId><version>" + version + "</version></parent>"
			+ "<artifactId>core</artifactId>"
			: "<groupId>g</groupId><artifactId>root</artifactId><version>" + version + "</version>";
		Files.write(file, ("<project>" + body + "</project>").getBytes(StandardCharsets.UTF_8));
	}

	private void poms(String version) throws IOException {
		pom(dir.resolve("pom.xml"), version, false);
		pom(dir.resolve("core/pom.xml"), version, true);
	}

	private String read(String name) throws IOException {
		return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
	}

	private void write(String name, String content) throws IOException {
		Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
	}

	@BeforeEach
	void project() throws IOException {
		poms("1.0.0-SNAPSHOT");
		write("README.md", "Version 1.0.0-SNAPSHOT\n");
		write("NOTES.md", "Version 1.0.0-SNAPSHOT\n");
	}

	private SyncMojo mojo() {
		Model model = new Model();
		model.setGroupId("g");
		model.setArtifactId("root");
		MavenProject root = new MavenProject(model);
		root.setFile(dir.resolve("pom.xml").toFile());
		MavenProject core = new MavenProject(new Model());
		core.setFile(dir.resolve("core/pom.xml").toFile());
		SyncMojo mojo = new SyncMojo();
		mojo.project = root;
		mojo.reactorProjects = Arrays.asList(root, core);
		mojo.files = Collections.singletonList(new File("README.md"));
		mojo.useDefaultExcludes = true;
		mojo.encoding = "UTF-8";
		mojo.backupSuffix = ".bak";
		return mojo;
	}

	@Test
	void usesAnExplicitPreviousVersion() throws Exception {
		poms("2.0.0-RC1");
		SyncMojo mojo = mojo();
		mojo.oldVersion = "1.0.0-SNAPSHOT";

		mojo.execute();

		assertEquals("Version 2.0.0-RC1\n", read("README.md"));
		assertEquals("Version 1.0.0-SNAPSHOT\n", read("NOTES.md"), "not configured");
		assertEquals("<project><groupId>g</groupId><artifactId>root</artifactId><version>2.0.0-RC1</version></project>",
			read("pom.xml"), "POMs are never edited");
	}

	@Test
	void followsReleasePrepare() throws Exception {
		// preparation goals: POMs hold the release version, the backups the previous one
		Files.copy(dir.resolve("pom.xml"), dir.resolve("pom.xml.releaseBackup"));
		Files.copy(dir.resolve("core/pom.xml"), dir.resolve("core/pom.xml.releaseBackup"));
		write("release.properties", "project.rel.g\\:root=1.0.0\nproject.dev.g\\:root=1.0.1-SNAPSHOT\n"
			+ "project.rel.g\\:core=1.0.0\nproject.dev.g\\:core=1.0.1-SNAPSHOT\n");
		poms("1.0.0");
		mojo().execute();
		assertEquals("Version 1.0.0\n", read("README.md"));

		// completion goals: POMs hold the next development version, the previous one is the release version
		poms("1.0.1-SNAPSHOT");
		mojo().execute();
		assertEquals("Version 1.0.1-SNAPSHOT\n", read("README.md"));
	}

	@Test
	void releasePrepareDryRunChangesNothing() throws Exception {
		// in a dry run the POMs are not modified: backup and POM hold the same version
		Files.copy(dir.resolve("pom.xml"), dir.resolve("pom.xml.releaseBackup"));
		write("release.properties", "project.rel.g\\:root=1.0.0\nproject.dev.g\\:root=1.0.1-SNAPSHOT\n");

		mojo().execute();

		assertEquals("Version 1.0.0-SNAPSHOT\n", read("README.md"));
	}

	@Test
	void failsWhenThePreviousVersionIsUnknown() throws Exception {
		poms("2.0.0");
		SyncMojo mojo = mojo();

		MojoFailureException e = assertThrows(MojoFailureException.class, mojo::execute);

		assertTrue(e.getMessage().contains("-Dbump.oldVersion"), e.getMessage());
		assertEquals("Version 1.0.0-SNAPSHOT\n", read("README.md"));
	}

	@Test
	void skip() throws Exception {
		poms("1.2.0-SNAPSHOT");
		SyncMojo mojo = mojo();
		mojo.skip = true;
		mojo.execute();
		assertEquals("Version 1.0.0-SNAPSHOT\n", read("README.md"));
	}
}
