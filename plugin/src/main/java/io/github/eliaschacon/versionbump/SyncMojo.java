package io.github.eliaschacon.versionbump;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.github.eliaschacon.versionbump.concurrent.PlatformTaskRunner;
import io.github.eliaschacon.versionbump.version.Version;

/**
 * Propagates a version change that is already in the POMs (made by {@code release:prepare}, {@code versions:set}
 * or by hand) to the configured files. The POMs are never edited.
 *
 * <p>The previous version is {@code bump.oldVersion}; inside {@code release:prepare} it is read from the files the
 * release plugin leaves ({@code release.properties}, {@code pom.xml.releaseBackup}). For {@code release:prepare}, add
 * this goal to the {@code preparationGoals} and {@code completionGoals} of the release plugin, followed by
 * {@code scm:checkin} for the propagated files (the release plugin only commits POMs).</p>
 */
@Mojo(name = "sync", aggregator = true, requiresProject = true, threadSafe = true)
public class SyncMojo extends AbstractVersionMojo {

	/** Previous version; detected only inside {@code release:prepare} (see the class description). */
	@Parameter(property = "bump.oldVersion")
	String oldVersion;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("Version sync skipped (bump.skip=true).");
			return;
		}
		if (project.getFile() == null) {
			throw new MojoExecutionException("The goal requires a project with a pom.xml file.");
		}
		try {
			List<Path> poms = new ArrayList<>();
			poms.add(rootPom());
			poms.addAll(modulePoms());
			Map<Version, Version> changes = new PreviousVersions(getLog(), baseDir()).resolve(poms, oldVersion);
			new BumpService(getLog(), new PlatformTaskRunner())
				.propagate(fileRequest().updatePom(false).updateFiles(true).build(), changes);
		} catch (BumpException e) {
			throw new MojoFailureException(e.getMessage(), e);
		}
	}
}
