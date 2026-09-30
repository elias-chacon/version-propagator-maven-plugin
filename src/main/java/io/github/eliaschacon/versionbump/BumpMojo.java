package io.github.eliaschacon.versionbump;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.io.ChangeApplier;

/**
 * Bumps the project version ({@code major}, {@code minor}, {@code patch} or {@code build}) in the POM
 * and, optionally, replaces the old version in additional files.
 *
 * <p>The goal is an aggregator: it runs once, on the project where Maven was invoked (the execution
 * root), and handles the other reactor modules itself according to {@code updateModules}.</p>
 */
@Mojo(name = "bump", aggregator = true, requiresProject = true, threadSafe = true)
public class BumpMojo extends AbstractMojo {

	/**
	 * Execution root project.
	 */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	MavenProject project;

	/**
	 * All projects of the current reactor.
	 */
	@Parameter(defaultValue = "${reactorProjects}", readonly = true)
	List<MavenProject> reactorProjects;

	/**
	 * Version part to increment: {@code major}, {@code minor}, {@code patch} or {@code build}.
	 */
	@Parameter(property = "bump.part", required = true)
	String part;

	/**
	 * Show the planned changes without writing any file.
	 */
	@Parameter(property = "bump.dryRun", defaultValue = "false")
	boolean dryRun;

	/**
	 * Update {@code <project><version>} of the execution root POM.
	 */
	@Parameter(property = "bump.updatePom", defaultValue = "true")
	boolean updatePom;

	/**
	 * When updating the POM, also update reactor modules whose {@code <parent>} is a bumped project with the
	 * old version (parent reference and, when equal to the old version, the module's own version).
	 */
	@Parameter(property = "bump.updateModules", defaultValue = "true")
	boolean updateModules;

	/**
	 * Replace the old version in {@code files} and in files matched by {@code includes} under {@code directories}.
	 */
	@Parameter(property = "bump.updateFiles", defaultValue = "false")
	boolean updateFiles;

	/**
	 * Glob patterns (relative to each directory) of files to process, e.g. {@code **}{@code /*.yaml}.
	 */
	@Parameter(property = "bump.includes")
	List<String> includes;

	/**
	 * Glob patterns of files or directories to exclude.
	 */
	@Parameter(property = "bump.excludes")
	List<String> excludes;

	/**
	 * Also exclude {@code **}{@code /target/**} and VCS directories ({@code .git}, {@code .svn}, {@code .hg}).
	 */
	@Parameter(property = "bump.useDefaultExcludes", defaultValue = "true")
	boolean useDefaultExcludes;

	/**
	 * Directories to scan with {@code includes}; defaults to the project base directory.
	 */
	@Parameter(property = "bump.directories")
	List<File> directories;

	/**
	 * Specific files to process (not filtered by includes/excludes).
	 */
	@Parameter(property = "bump.files")
	List<File> files;

	/**
	 * Replacement rules ({@code search}, {@code replace} and an optional scope: {@code files}, {@code includes},
	 * {@code excludes} relative to the project base directory). Rule {@code files} are processed automatically.
	 * When empty, a safe rule replacing the exact old version is used.
	 */
	@Parameter
	List<ReplacementRule> replacements;

	/**
	 * Encoding used to read and write the additional files (POMs use their XML declaration).
	 */
	@Parameter(property = "bump.encoding", defaultValue = "UTF-8")
	String encoding;

	/**
	 * Write {@code <file><backupSuffix>} with the original content before modifying a file.
	 */
	@Parameter(property = "bump.createBackup", defaultValue = "false")
	boolean createBackup;

	/**
	 * Suffix of backup files.
	 */
	@Parameter(property = "bump.backupSuffix", defaultValue = ChangeApplier.DEFAULT_BACKUP_SUFFIX)
	String backupSuffix;

	/**
	 * Allow replacing backups that already exist.
	 */
	@Parameter(property = "bump.overwriteBackups", defaultValue = "false")
	boolean overwriteBackups;

	/**
	 * Fail when the old version is not found in any of the configured files.
	 */
	@Parameter(property = "bump.failOnNoMatch", defaultValue = "false")
	boolean failOnNoMatch;

	/**
	 * Qualifier handling other than {@code -SNAPSHOT}: {@code fail}, {@code preserve} or {@code remove}.
	 */
	@Parameter(property = "bump.qualifierPolicy", defaultValue = "fail")
	String qualifierPolicy;

	/**
	 * Follow symbolic links while scanning (targets must stay inside the project).
	 */
	@Parameter(property = "bump.followSymlinks", defaultValue = "false")
	boolean followSymlinks;

	/**
	 * Skip the goal.
	 */
	@Parameter(property = "bump.skip", defaultValue = "false")
	boolean skip;

	/**
	 * Maven leaves unconfigured list parameters {@code null}.
	 */
	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? new ArrayList<>() : new ArrayList<>(list);
	}

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
			new BumpService(getLog()).execute(toRequest());
		} catch (BumpException e) {
			throw new MojoFailureException(e.getMessage(), e);
		}
	}

	BumpRequest toRequest() {
		Path rootPom = project.getFile().toPath().toAbsolutePath().normalize();
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
		return BumpRequest.builder()
			.baseDir(project.getBasedir().toPath())
			.rootPom(rootPom)
			.modulePoms(modulePoms)
			.projectVersion(project.getVersion())
			.part(part)
			.qualifierPolicy(qualifierPolicy)
			.dryRun(dryRun)
			.updatePom(updatePom)
			.updateModules(updateModules)
			.updateFiles(updateFiles)
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
			.followSymlinks(followSymlinks)
			.build();
	}

	private List<Path> toPaths(List<File> list) {
		List<Path> paths = new ArrayList<>();
		if (list != null) {
			for (File file : list) {
				// Maven resolves File parameters against the base directory; resolve again for safety.
				paths.add(file.isAbsolute() ? file.toPath() : project.getBasedir().toPath().resolve(file.toPath()));
			}
		}
		return paths;
	}
}
