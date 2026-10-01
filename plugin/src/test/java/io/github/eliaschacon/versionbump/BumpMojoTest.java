package io.github.eliaschacon.versionbump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BumpMojoTest {

	@TempDir
	Path base;

	private static MavenProject project(Path pom, String version) {
		Model model = new Model();
		model.setGroupId("g");
		model.setArtifactId(pom.getParent().getFileName().toString());
		model.setVersion(version);
		MavenProject project = new MavenProject(model);
		project.setFile(pom.toFile());
		return project;
	}

	private BumpMojo mojo(String part) throws Exception {
		Path pom = base.resolve("pom.xml");
		Files.write(pom, "<project><groupId>g</groupId><artifactId>a</artifactId><version>0.9.9-SNAPSHOT</version></project>"
			.getBytes(StandardCharsets.UTF_8));
		Path child = base.resolve("child/pom.xml");
		Files.createDirectories(child.getParent());
		Files.write(child, ("<project><parent><groupId>g</groupId><artifactId>a</artifactId><version>0.9.9-SNAPSHOT</version>"
			+ "</parent><artifactId>child</artifactId></project>").getBytes(StandardCharsets.UTF_8));
		Files.write(base.resolve("app.yaml"), "version: 0.9.9-SNAPSHOT\n".getBytes(StandardCharsets.UTF_8));

		BumpMojo mojo = new BumpMojo();
		mojo.project = project(pom, "0.9.9-SNAPSHOT");
		mojo.reactorProjects = Arrays.asList(mojo.project, project(child, "0.9.9-SNAPSHOT"));
		mojo.part = part;
		mojo.updatePom = true;
		mojo.updateModules = true;
		mojo.useDefaultExcludes = true;
		mojo.encoding = "UTF-8";
		mojo.backupSuffix = ".bak";
		mojo.qualifierPolicy = "fail";
		return mojo;
	}

	private String read(String relative) throws Exception {
		return new String(Files.readAllBytes(base.resolve(relative)), StandardCharsets.UTF_8);
	}

	@Test
	void bumpsPomModulesAndFiles() throws Exception {
		BumpMojo mojo = mojo("minor");
		mojo.updateFiles = true;
		mojo.includes = Collections.singletonList("*.yaml");

		mojo.execute();

		assertTrue(read("pom.xml").contains("<version>0.10.0-SNAPSHOT</version>"));
		assertTrue(read("child/pom.xml").contains("<version>0.10.0-SNAPSHOT</version>"));
		assertEquals("version: 0.10.0-SNAPSHOT\n", read("app.yaml"));
	}

	@Test
	void relativeFilesAreResolvedAgainstBaseDir() throws Exception {
		BumpMojo mojo = mojo("patch");
		mojo.updateFiles = true;
		mojo.files = Collections.singletonList(new File("app.yaml"));

		BumpRequest request = mojo.toRequest();

		assertEquals(base.resolve("app.yaml"), request.getFiles().get(0));
		assertEquals(1, request.getModulePoms().size());
	}

	@Test
	void mapsTheVersionOptions() throws Exception {
		BumpMojo mojo = mojo(null);
		mojo.newVersion = "2.0.0-RC1";
		mojo.snapshot = Boolean.TRUE;
		mojo.qualifier = "none";

		BumpRequest request = mojo.toRequest();

		assertEquals("2.0.0-RC1", request.getNewVersion());
		assertEquals(Boolean.TRUE, request.getSnapshot());
		assertEquals("none", request.getQualifier());
	}

	@Test
	void setsAnExplicitVersionInPomModulesAndFiles() throws Exception {
		BumpMojo mojo = mojo(null);
		mojo.newVersion = "1.0.0-RC1";
		mojo.updateFiles = true;
		mojo.files = Collections.singletonList(new File("app.yaml"));

		mojo.execute();

		assertTrue(read("pom.xml").contains("<version>1.0.0-RC1</version>"));
		assertTrue(read("child/pom.xml").contains("<version>1.0.0-RC1</version>"));
		assertEquals("version: 1.0.0-RC1\n", read("app.yaml"));
	}

	@Test
	void dryRunDoesNotWrite() throws Exception {
		BumpMojo mojo = mojo("major");
		mojo.dryRun = true;
		String before = read("pom.xml");

		mojo.execute();

		assertEquals(before, read("pom.xml"));
	}

	@Test
	void skip() throws Exception {
		BumpMojo mojo = mojo("major");
		mojo.skip = true;
		String before = read("pom.xml");
		mojo.execute();
		assertEquals(before, read("pom.xml"));
	}

	@Test
	void domainErrorsBecomeMojoFailures() throws Exception {
		BumpMojo mojo = mojo("micro");
		MojoFailureException e = assertThrows(MojoFailureException.class, mojo::execute);
		assertTrue(e.getMessage().contains("Accepted values"));
	}
}
