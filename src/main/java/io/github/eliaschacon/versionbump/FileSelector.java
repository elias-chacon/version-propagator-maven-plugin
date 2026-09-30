package io.github.eliaschacon.versionbump;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.maven.plugin.logging.Log;

import io.github.eliaschacon.versionbump.files.FileScanner;
import io.github.eliaschacon.versionbump.files.GlobMatcher;
import io.github.eliaschacon.versionbump.files.ReplacementRule;
import lombok.RequiredArgsConstructor;

/**
 * Selects the additional files to process:
 * <ul>
 *     <li>global {@code files} and global {@code includes} scanned under {@code directories};</li>
 *     <li>each rule's {@code files} and each rule's {@code includes} scanned under the project base directory
 *     (minus default excludes, global excludes and the rule's excludes).</li>
 * </ul>
 * Reactor POMs are always excluded: they are handled by the POM update, and a text replacement there
 * could hit dependency or plugin versions. Backup files are excluded too.
 */
@RequiredArgsConstructor
final class FileSelector {

	private final Log log;
	private final Path baseDir;
	private final BumpRequest request;

	/**
	 * Path relative to the base directory with {@code /} separators, or the absolute path when outside.
	 */
	static String display(Path baseDir, Path file) {
		Path normalized = FileScanner.normalize(file);
		return normalized.startsWith(baseDir) ? FileScanner.relative(baseDir, normalized) : normalized.toString();
	}

	SortedSet<Path> select(List<ReplacementRule> rules) throws BumpException {
		validate(rules);
		List<String> baseExcludes = baseExcludes();

		SortedSet<Path> candidates = new TreeSet<>();
		for (Path file : request.getFiles()) {
			candidates.add(requireExplicitFile(file, "file"));
		}
		if (!request.getIncludes().isEmpty()) {
			List<Path> directories = request.getDirectories().isEmpty()
				? Collections.singletonList(baseDir) : request.getDirectories();
			candidates.addAll(scan(request.getIncludes(), baseExcludes, directories));
		}
		for (ReplacementRule rule : rules) {
			candidates.addAll(ruleFiles(rule, baseExcludes));
		}

		SortedSet<Path> result = withoutProtectedFiles(candidates);
		if (result.isEmpty()) {
			log.warn("No file matched the configured files/includes.");
		}
		return result;
	}

	private void validate(List<ReplacementRule> rules) throws BumpException {
		boolean rulesSelectFiles = false;
		for (ReplacementRule rule : rules) {
			rulesSelectFiles |= rule.isScoped();
		}
		if (request.getFiles().isEmpty() && request.getIncludes().isEmpty() && !rulesSelectFiles) {
			throw new BumpException("bump.updateFiles=true but no files are configured: set files, includes"
				+ " or <files>/<includes> inside a <replacement>.");
		}
		if (!request.getDirectories().isEmpty() && request.getIncludes().isEmpty()) {
			throw new BumpException("Directories are configured but includes are empty: set includes"
				+ " (e.g. -Dbump.includes=**/*.yaml,**/*.properties).");
		}
	}

	private List<String> baseExcludes() {
		List<String> excludes = new ArrayList<>();
		if (request.isUseDefaultExcludes()) {
			excludes.addAll(FileScanner.DEFAULT_EXCLUDES);
		}
		excludes.addAll(request.getExcludes());
		return excludes;
	}

	private Set<Path> ruleFiles(ReplacementRule rule, List<String> baseExcludes) throws BumpException {
		Set<Path> files = new HashSet<>();
		for (Path file : rule.resolveFiles(baseDir)) {
			files.add(requireExplicitFile(file, "rule file"));
		}
		if (!rule.getIncludes().isEmpty()) {
			List<String> excludes = new ArrayList<>(baseExcludes);
			excludes.addAll(rule.getExcludes());
			files.addAll(scan(rule.getIncludes(), excludes, Collections.singletonList(baseDir)));
		}
		return files;
	}

	private SortedSet<Path> withoutProtectedFiles(SortedSet<Path> candidates) throws BumpException {
		Set<Path> poms = new HashSet<>();
		if (request.getRootPom() != null) {
			poms.add(FileScanner.normalize(request.getRootPom()));
		}
		for (Path pom : request.getModulePoms()) {
			poms.add(FileScanner.normalize(pom));
		}
		SortedSet<Path> result = new TreeSet<>();
		for (Path found : candidates) {
			Path candidate = FileScanner.resolveLink(baseDir, found);
			if (poms.contains(candidate)) {
				log.info(FileScanner.SKIPPED + display(candidate) + " (reactor POM, handled by the POM update)");
			} else if (isBackup(candidate)) {
				log.info(FileScanner.SKIPPED + display(candidate) + " (backup file)");
			} else {
				result.add(candidate);
			}
		}
		return result;
	}

	private boolean isBackup(Path file) {
		String suffix = request.getBackupSuffix();
		return suffix != null && !suffix.isEmpty() && file.getFileName().toString().endsWith(suffix);
	}

	private SortedSet<Path> scan(List<String> includes, List<String> excludes, List<Path> directories)
		throws BumpException {
		return new FileScanner(baseDir, GlobMatcher.of(includes), GlobMatcher.of(excludes),
			request.isFollowSymlinks(), log).scan(directories);
	}

	/**
	 * Validates an explicitly configured file: inside the project, existing, regular, not a link unless allowed.
	 */
	private Path requireExplicitFile(Path file, String what) throws BumpException {
		Path path = FileScanner.requireInside(baseDir, file, what);
		if (!request.isFollowSymlinks() && Files.isSymbolicLink(path)) {
			throw new BumpException("Configured " + what + " is a symbolic link: " + path
				+ ". Set bump.followSymlinks=true to allow it.");
		}
		if (!Files.exists(path)) {
			throw new BumpException("Configured " + what + " does not exist: " + path);
		}
		if (!Files.isRegularFile(path)) {
			throw new BumpException("Configured " + what + " is not a regular file: " + path);
		}
		return path;
	}

	String display(Path file) {
		return display(baseDir, file);
	}
}
