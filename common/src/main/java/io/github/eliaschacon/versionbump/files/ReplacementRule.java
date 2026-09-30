package io.github.eliaschacon.versionbump.files;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * User configurable replacement rule (also the Maven configuration object of {@code <replacement>}).
 *
 * <ul>
 *     <li>{@code search}: Java regular expression. Tokens ({@code ${oldVersion}}, {@code @oldMajor@}...)
 *     are inserted as quoted literals, so dots in versions never act as wildcards. When omitted, the
 *     safe default search ({@link #DEFAULT_SEARCH}) is used.</li>
 *     <li>{@code replace}: Java replacement string. Tokens are inserted literally; capture groups can be
 *     referenced with {@code $1}. A literal {@code $} or {@code \} must be escaped with {@code \}.
 *     Required with a custom search; defaults to {@code ${newVersion}} with the default search.</li>
 *     <li>Scope (optional): {@code files} (paths relative to the project base directory), {@code includes}
 *     and {@code excludes} (globs relative to the project base directory). Files named by {@code files} or
 *     matched by {@code includes} are selected for processing automatically, and the rule only applies to
 *     them. A rule without {@code files}/{@code includes} applies to every processed file.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplacementRule {

	/**
	 * Characters that may not precede the old version (prevents matching {@code 11.2.3} or {@code 0.1.2.3}).
	 */
	public static final String DEFAULT_PREFIX = "(?<![0-9.])";
	/**
	 * Characters that may not follow the old version: digits, letters, {@code _}, a dot followed by a digit
	 * ({@code 1.2.30}, {@code 1.2.3.4}) or a dash followed by a letter/digit ({@code 1.2.3-SNAPSHOT}, {@code 1.2.3-RC1}).
	 */
	public static final String DEFAULT_SUFFIX = "(?![0-9A-Za-z_]|\\.[0-9]|-[0-9A-Za-z])";
	/**
	 * The complete old version as a standalone token.
	 */
	public static final String DEFAULT_SEARCH = DEFAULT_PREFIX + "${oldVersion}" + DEFAULT_SUFFIX;
	public static final String DEFAULT_REPLACE = "${newVersion}";

	private String search;
	private String replace;
	/**
	 * Files this rule is restricted to (relative to the project base directory).
	 */
	@NonNull
	@Builder.Default
	private List<String> files = new ArrayList<>();
	@NonNull
	@Builder.Default
	private List<String> includes = new ArrayList<>();
	@NonNull
	@Builder.Default
	private List<String> excludes = new ArrayList<>();

	/**
	 * Unscoped rule; handy for code and tests. Maven uses the no-args constructor and the setters.
	 */
	public ReplacementRule(String search, String replace) {
		this(search, replace, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	}

	/**
	 * Safe default: the complete old version as a standalone token replaced by the complete new version.
	 */
	public static ReplacementRule defaultRule() {
		return builder().search(DEFAULT_SEARCH).replace(DEFAULT_REPLACE).build();
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/**
	 * {@code true} when the rule relies on the default search (no {@code <search>} configured).
	 */
	public boolean usesDefaultSearch() {
		return isBlank(search);
	}

	/**
	 * {@code true} when {@code files} or {@code includes} restrict the rule.
	 */
	public boolean isScoped() {
		return !files.isEmpty() || !includes.isEmpty();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder()
			.append("search='").append(usesDefaultSearch() ? "<default>" : search).append('\'')
			.append(", replace='").append(replace).append('\'');
		if (!files.isEmpty()) {
			sb.append(", files=").append(files);
		}
		if (!includes.isEmpty()) {
			sb.append(", includes=").append(includes);
		}
		if (!excludes.isEmpty()) {
			sb.append(", excludes=").append(excludes);
		}
		return sb.toString();
	}

	/**
	 * Resolves the rule {@code files} against {@code baseDir}; they must stay inside the project.
	 */
	public List<Path> resolveFiles(Path baseDir) throws BumpException {
		List<Path> resolved = new ArrayList<>();
		for (String file : files) {
			if (isBlank(file)) {
				throw new BumpException("Empty <file> in replacement rule (" + this + ").");
			}
			try {
				resolved.add(FileScanner.requireInside(baseDir, Paths.get(file.trim()), "rule file"));
			} catch (InvalidPathException e) {
				throw new BumpException("Invalid <file> '" + file + "' in replacement rule (" + this + ").", e);
			}
		}
		return resolved;
	}

	/**
	 * Test convenience: scope paths are resolved against the working directory. Production code must use
	 * {@link #compile(VersionTokens, Path)} with the project base directory.
	 */
	Compiled compile(VersionTokens tokens) throws BumpException {
		return compile(tokens, Paths.get("").toAbsolutePath());
	}

	/**
	 * Resolves tokens, validates the rule and prepares its scope.
	 *
	 * @param baseDir project base directory used to resolve {@code files} and match {@code includes}/{@code excludes}
	 */
	public Compiled compile(VersionTokens tokens, Path baseDir) throws BumpException {
		String effectiveSearch = search;
		String effectiveReplace = replace;
		if (usesDefaultSearch()) {
			effectiveSearch = DEFAULT_SEARCH;
			if (effectiveReplace == null) {
				effectiveReplace = DEFAULT_REPLACE;
			}
		} else if (effectiveReplace == null) {
			throw new BumpException("Replacement rule has no <replace> (" + this + "). If you wrote ${newVersion}"
				+ " in the POM, Maven interpolated it: use @newVersion@ or $${newVersion} instead.");
		}
		String regex = tokens.expand(effectiveSearch, Pattern::quote);
		String replacement = tokens.expand(effectiveReplace, Matcher::quoteReplacement);
		Set<Path> scopeFiles = new HashSet<>();
		for (Path file : resolveFiles(baseDir)) {
			scopeFiles.add(file);
			// Processed files are link targets (see FileScanner.resolveLink): the scope must know them too.
			scopeFiles.add(FileScanner.resolveLink(baseDir, file));
		}
		Scope scope = new Scope(FileScanner.normalize(baseDir), scopeFiles,
			GlobMatcher.of(includes), GlobMatcher.of(excludes));
		try {
			Pattern pattern = Pattern.compile(regex);
			if (pattern.matcher("").matches()) {
				throw new BumpException("Replacement rule search matches the empty string (" + this + ").");
			}
			return new Compiled(this, pattern, replacement, scope);
		} catch (PatternSyntaxException e) {
			throw new BumpException("Invalid regular expression in replacement rule (" + this + "): "
				+ e.getDescription(), e);
		}
	}

	/**
	 * Where a rule applies.
	 */
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	private static final class Scope {
		private final Path baseDir;
		private final Set<Path> files;
		private final GlobMatcher includes;
		private final GlobMatcher excludes;

		boolean contains(Path file) {
			Path path = FileScanner.normalize(file);
			String relative = path.startsWith(baseDir) ? FileScanner.relative(baseDir, path) : null;
			if (relative != null && excludes.matches(relative)) {
				return false;
			}
			if (files.isEmpty() && includes.isEmpty()) {
				return true;
			}
			return files.contains(path) || (relative != null && includes.matches(relative));
		}
	}

	/**
	 * A rule ready to be applied.
	 */
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Compiled {
		private final ReplacementRule source;
		private final Pattern pattern;
		private final String replacement;
		private final Scope scope;

		/**
		 * {@code true} when the file is inside the rule scope.
		 */
		public boolean appliesTo(Path file) {
			return scope.contains(file);
		}

		/**
		 * Applies the rule, returning the new text and the number of replacements.
		 */
		public Result apply(String text) throws BumpException {
			Matcher m = pattern.matcher(text);
			StringBuffer out = new StringBuffer(text.length());
			int count = 0;
			try {
				while (m.find()) {
					m.appendReplacement(out, replacement);
					count++;
				}
			} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
				throw new BumpException("Invalid replacement in rule (" + source + "): " + e.getMessage(), e);
			}
			m.appendTail(out);
			return new Result(count == 0 ? text : out.toString(), count);
		}
	}

	/**
	 * Output of {@link Compiled#apply(String)}.
	 */
	@Data
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Result {
		private final String text;
		private final int count;
	}
}
