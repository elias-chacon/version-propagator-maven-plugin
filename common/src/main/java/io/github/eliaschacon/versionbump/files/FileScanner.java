package io.github.eliaschacon.versionbump.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.maven.plugin.logging.Log;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.RequiredArgsConstructor;

/**
 * Collects candidate files below configured directories.
 *
 * <p>Includes/excludes are matched against the path relative to the scanned directory. Excluded
 * directories are pruned (not traversed). Symbolic links are skipped unless {@code followSymlinks}
 * is enabled. Every path must stay inside the project base directory.</p>
 */
public final class FileScanner {

	/**
	 * Applied unless disabled: build output and VCS metadata are never scanned by default.
	 */
	public static final List<String> DEFAULT_EXCLUDES = Collections.unmodifiableList(
		Arrays.asList("**/target/**", "**/.git/**", "**/.svn/**", "**/.hg/**"));

	/**
	 * Log prefix for files that are not processed.
	 */
	public static final String SKIPPED = "[skipped] ";

	private final Path baseDir;
	private final GlobMatcher includes;
	private final GlobMatcher excludes;
	private final boolean followSymlinks;
	private final Log log;

	public FileScanner(Path baseDir, GlobMatcher includes, GlobMatcher excludes, boolean followSymlinks, Log log) {
		this.baseDir = normalize(baseDir);
		this.includes = includes;
		this.excludes = excludes;
		this.followSymlinks = followSymlinks;
		this.log = log;
	}

	/**
	 * Validates that {@code candidate} is inside {@code baseDir} and returns its normalized absolute path.
	 */
	public static Path requireInside(Path baseDir, Path candidate, String what) throws BumpException {
		Path base = normalize(baseDir);
		Path path = normalize(base.resolve(candidate));
		if (!path.startsWith(base)) {
			throw new BumpException("The " + what + " " + path + " is outside the project directory " + base
				+ ". Only paths inside the project can be modified.");
		}
		return path;
	}

	/**
	 * Absolute, normalised form used for every path comparison in the plugin.
	 */
	public static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}

	/**
	 * Returns the target of a symbolic link, expressed under {@code baseDir}, so that writing it does not
	 * replace the link by a regular file. Non-links are returned unchanged. The target must be inside the project.
	 */
	public static Path resolveLink(Path baseDir, Path path) throws BumpException {
		if (!Files.isSymbolicLink(path)) {
			return path;
		}
		try {
			Path real = path.toRealPath();
			Path realBase = baseDir.toRealPath();
			if (!real.startsWith(realBase)) {
				throw new BumpException("Symbolic link " + path + " points outside the project: " + real);
			}
			return normalize(baseDir.resolve(realBase.relativize(real)));
		} catch (IOException e) {
			throw new BumpException("Cannot resolve symbolic link " + path + ": " + e, e);
		}
	}

	/**
	 * Converts a path to the portable {@code a/b/c} form used for glob matching and messages.
	 */
	public static String relative(Path from, Path to) {
		return from.relativize(to).toString().replace('\\', '/');
	}

	/**
	 * Scans the directories and returns the matching regular files, sorted.
	 *
	 * @throws BumpException if a directory is missing, not a directory, outside the project or unreadable
	 */
	public SortedSet<Path> scan(List<Path> directories) throws BumpException {
		SortedSet<Path> result = new TreeSet<>();
		for (Path directory : directories) {
			Path dir = requireInside(baseDir, directory, "directory");
			if (!Files.exists(dir)) {
				throw new BumpException("Configured directory does not exist: " + dir);
			}
			if (!Files.isDirectory(dir)) {
				throw new BumpException("Configured directory is not a directory: " + dir);
			}
			if (!followSymlinks && Files.isSymbolicLink(dir)) {
				throw new BumpException("Configured directory is a symbolic link: " + dir
					+ ". Set bump.followSymlinks=true to allow it.");
			}
			walk(dir, result);
		}
		return result;
	}

	private void walk(Path root, Set<Path> result) throws BumpException {
		Set<FileVisitOption> options = followSymlinks
			? EnumSet.of(FileVisitOption.FOLLOW_LINKS) : EnumSet.noneOf(FileVisitOption.class);
		try {
			Files.walkFileTree(root, options, Integer.MAX_VALUE, new Collector(root, result));
		} catch (IOException e) {
			throw new BumpException("Cannot scan directory " + root + ": " + e, e);
		} catch (UncheckedIOException e) {
			throw new BumpException("Cannot scan directory " + root + ": " + e.getCause(), e.getCause());
		}
	}

	/**
	 * Visitor collecting the matching regular files below one scanned directory.
	 */
	@RequiredArgsConstructor
	private final class Collector extends SimpleFileVisitor<Path> {
		private final Path root;
		private final Set<Path> result;

		@Override
		public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
			if (!dir.equals(root) && excludes.matches(relative(root, dir))) {
				log.debug("[excluded] " + dir);
				return FileVisitResult.SKIP_SUBTREE;
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
			String rel = relative(root, file);
			if (includes.matches(rel) && !excludes.matches(rel) && isProcessable(file, attrs)) {
				result.add(normalize(file));
			}
			return FileVisitResult.CONTINUE;
		}

		/**
		 * Without FOLLOW_LINKS the attributes are those of the link itself; with it, only broken links
		 * still report {@code isSymbolicLink()}.
		 */
		private boolean isProcessable(Path file, BasicFileAttributes attrs) {
			if (attrs.isSymbolicLink()) {
				log.info(SKIPPED + file + (followSymlinks ? " (broken symbolic link)" : " (symbolic link)"));
				return false;
			}
			if (!attrs.isRegularFile()) {
				log.info(SKIPPED + file + " (not a regular file)");
				return false;
			}
			return true;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException e) {
			if (e instanceof FileSystemLoopException) {
				log.warn(SKIPPED + file + " (symbolic link loop)");
				return FileVisitResult.CONTINUE;
			}
			throw new UncheckedIOException(e);
		}
	}
}
