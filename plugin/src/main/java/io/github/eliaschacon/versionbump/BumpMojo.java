package io.github.eliaschacon.versionbump;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.github.eliaschacon.versionbump.concurrent.PlatformTaskRunner;

/**
 * Changes the project version in the POM (parent and reactor modules) and, optionally, in additional files.
 *
 * <p>The next version is either explicit ({@code newVersion}) or computed from {@code part}
 * ({@code major}, {@code minor}, {@code patch}, {@code build}, {@code release}), optionally combined with
 * {@code snapshot} and {@code qualifier}.</p>
 *
 * <p>The goal is an aggregator: it runs once, on the project where Maven was invoked (the execution
 * root), and handles the other reactor modules itself according to {@code updateModules}.</p>
 *
 * <p>Files are planned by {@link PlatformTaskRunner}: the plugin JAR is a Multi-Release JAR, so Java 8 to 20
 * load the sequential implementation and Java 21+ the virtual-thread one.</p>
 */
@Mojo(name = "bump", aggregator = true, requiresProject = true, threadSafe = true)
public class BumpMojo extends AbstractVersionMojo {

	/**
	 * What to change: {@code major}, {@code minor}, {@code patch} or {@code build} (increment) or {@code release}
	 * (drop {@code -SNAPSHOT}). Required unless {@code newVersion} is set.
	 */
	@Parameter(property = "bump.part")
	String part;

	/** Explicit next version, e.g. {@code 1.2.0-SNAPSHOT} or {@code 1.2.0-RC1}. Exclusive with {@code part}. */
	@Parameter(property = "bump.newVersion")
	String newVersion;

	/** With {@code part}: {@code true} adds {@code -SNAPSHOT}, {@code false} drops it; unset keeps it. */
	@Parameter(property = "bump.snapshot")
	Boolean snapshot;

	/** With {@code part}: sets or replaces the qualifier (e.g. {@code RC1}); {@code none} removes it. */
	@Parameter(property = "bump.qualifier")
	String qualifier;

	/** Update {@code <project><version>} of the execution root POM. */
	@Parameter(property = "bump.updatePom", defaultValue = "true")
	boolean updatePom;

	/**
	 * When updating the POM, also update reactor modules whose {@code <parent>} is a bumped project with the
	 * old version (parent reference and, when equal to the old version, the module's own version).
	 */
	@Parameter(property = "bump.updateModules", defaultValue = "true")
	boolean updateModules;

	/** Replace the old version in {@code files} and in files matched by {@code includes} under {@code directories}. */
	@Parameter(property = "bump.updateFiles", defaultValue = "false")
	boolean updateFiles;

	/** Qualifier handling other than {@code -SNAPSHOT}: {@code fail}, {@code preserve} or {@code remove}. */
	@Parameter(property = "bump.qualifierPolicy", defaultValue = "fail")
	String qualifierPolicy;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("Version bump skipped (bump.skip=true).");
			return;
		}
		if (project.getFile() == null) {
			throw new MojoExecutionException("The goal requires a project with a pom.xml file.");
		}
		try {
			new BumpService(getLog(), new PlatformTaskRunner()).execute(toRequest());
		} catch (BumpException e) {
			throw new MojoFailureException(e.getMessage(), e);
		}
	}

	BumpRequest toRequest() {
		return fileRequest()
			.projectVersion(project.getVersion())
			.part(part)
			.newVersion(newVersion)
			.snapshot(snapshot)
			.qualifier(qualifier)
			.qualifierPolicy(qualifierPolicy)
			.updatePom(updatePom)
			.updateModules(updateModules)
			.updateFiles(updateFiles)
			.build();
	}
}
