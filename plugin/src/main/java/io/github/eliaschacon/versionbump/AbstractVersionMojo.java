package io.github.eliaschacon.versionbump;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.io.ChangeApplier;

/**
 * Parameters shared by the goals that edit files ({@code bump}, {@code sync}). The same names are read by the
 * lifecycle hook ({@code VersionHookParticipant}) from the plugin {@code <configuration>}.
 */
public abstract class AbstractVersionMojo extends AbstractMojo {

	/** Execution root project. */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	MavenProject project;

	/** All projects of the current reactor. */
	@Parameter(defaultValue = "${reactorProjects}", readonly = true)
	List<MavenProject> reactorProjects;

	/** Show the planned changes without writing any file. */
	@Parameter(property = "bump.dryRun", defaultValue = "false")
	boolean dryRun;

	/** Glob patterns (relative to each directory) of files to process, e.g. {@code **}{@code /*.yaml}. */
	@Parameter(property = "bump.includes")
	List<String> includes;

	/** Glob patterns of files or directories to exclude. */
	@Parameter(property = "bump.excludes")
	List<String> excludes;

	/** Also exclude {@code **}{@code /target/**} and VCS directories ({@code .git}, {@code .svn}, {@code .hg}). */
	@Parameter(property = "bump.useDefaultExcludes", defaultValue = "true")
	boolean useDefaultExcludes;

	/** Directories to scan with {@code includes}; defaults to the project base directory. */
	@Parameter(property = "bump.directories")
	List<File> directories;

	/** Specific files to process (not filtered by includes/excludes). */
	@Parameter(property = "bump.files")
	List<File> files;

	/**
	 * Replacement rules ({@code search}, {@code replace} and an optional scope: {@code files}, {@code includes},
	 * {@code excludes} relative to the project base directory). Rule {@code files} are processed automatically.
	 * When empty, a safe rule replacing the exact old version is used.
	 */
	@Parameter
	List<ReplacementRule> replacements;

	/** Encoding used to read and write the additional files (POMs use their XML declaration). */
	@Parameter(property = "bump.encoding", defaultValue = "UTF-8")
	String encoding;

	/** Write {@code <file><backupSuffix>} with the original content before modifying a file. */
	@Parameter(property = "bump.createBackup", defaultValue = "false")
	boolean createBackup;

	/** Suffix of backup files. */
	@Parameter(property = "bump.backupSuffix", defaultValue = ChangeApplier.DEFAULT_BACKUP_SUFFIX)
	String backupSuffix;

	/** Allow replacing backups that already exist. */
	@Parameter(property = "bump.overwriteBackups", defaultValue = "false")
	boolean overwriteBackups;

	/** Fail when the old version is not found in any of the configured files. */
	@Parameter(property = "bump.failOnNoMatch", defaultValue = "false")
	boolean failOnNoMatch;

	/** Follow symbolic links while scanning (targets must stay inside the project). */
	@Parameter(property = "bump.followSymlinks", defaultValue = "false")
	boolean followSymlinks;

	/**
	 * Goals after which the lifecycle hook propagates the new version to the configured files (only when the
	 * plugin is declared with {@code <extensions>true</extensions>}). Default: {@code release:update-versions},
	 * {@code versions:set}.
	 */
	@Parameter
	List<String> hookGoals;

	/** Turns the lifecycle hook off (it only runs when the plugin is declared with extensions). */
	@Parameter(property = "bump.hook.skip", defaultValue = "false")
	boolean hookSkip;

	/** Skip the goal. */
	@Parameter(property = "bump.skip", defaultValue = "false")
	boolean skip;

	/** Maven leaves unconfigured list parameters {@code null}. */
	static <T> List<T> orEmpty(List<T> list) {
		return list == null ? new ArrayList<T>() : new ArrayList<>(list);
	}

	Path baseDir() {
		return project.getBasedir().toPath();
	}

	Path rootPom() {
		return project.getFile().toPath().toAbsolutePath().normalize();
	}

	/** POMs of the other reactor projects (protected from file replacement). */
	List<Path> modulePoms() {
		Path rootPom = rootPom();
		List<Path> modulePoms = new ArrayList<>();
		if (reactorProjects != null) {
			for (MavenProject reactorProject : reactorProjects) {
				if (reactorProject.getFile() != null) {
					Path pom = reactorProject.getFile().toPath().toAbsolutePath().normalize();
					if (!pom.equals(rootPom)) {
						modulePoms.add(pom);
					}
				}
			}
		}
		return modulePoms;
	}

	/** Request with the file parameters; goal-specific values are added by the caller. */
	BumpRequest.BumpRequestBuilder fileRequest() {
		return BumpRequest.builder()
			.baseDir(baseDir())
			.rootPom(rootPom())
			.modulePoms(modulePoms())
			.dryRun(dryRun)
			.includes(orEmpty(includes))
			.excludes(orEmpty(excludes))
			.useDefaultExcludes(useDefaultExcludes)
			.directories(toPaths(directories))
			.files(toPaths(files))
			.replacements(orEmpty(replacements))
			.encoding(encoding)
			.createBackup(createBackup)
			.backupSuffix(backupSuffix)
			.overwriteBackups(overwriteBackups)
			.failOnNoMatch(failOnNoMatch)
			.followSymlinks(followSymlinks);
	}

	private List<Path> toPaths(List<File> list) {
		List<Path> paths = new ArrayList<>();
		if (list != null) {
			for (File file : list) {
				// Maven resolves File parameters against the base directory; resolve again for safety.
				paths.add(file.isAbsolute() ? file.toPath() : baseDir().resolve(file.toPath()));
			}
		}
		return paths;
	}
}
