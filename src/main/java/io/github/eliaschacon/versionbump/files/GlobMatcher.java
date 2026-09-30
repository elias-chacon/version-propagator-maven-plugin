package io.github.eliaschacon.versionbump.files;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Portable glob matcher for relative paths using {@code /} as separator on every platform.
 *
 * <p>Supported syntax (Ant/Maven style): {@code *} matches within a path segment, {@code ?} matches one
 * character within a segment, {@code **} matches any number of directories ({@code **}{@code /} may match
 * zero directories, so {@code **}{@code /*.yaml} also matches {@code app.yaml}). A trailing {@code /}
 * means "everything below". Backslashes in patterns are treated as separators. Matching is case-sensitive.
 * Other characters, including {@code [ ] { }}, are literal.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class GlobMatcher {

	private final List<Pattern> patterns;

	public static GlobMatcher of(Collection<String> globs) throws BumpException {
		List<Pattern> compiled = new ArrayList<>();
		if (globs != null) {
			for (String glob : globs) {
				if (glob == null || glob.trim().isEmpty()) {
					throw new BumpException("Empty glob pattern in includes/excludes.");
				}
				compiled.add(Pattern.compile(toRegex(normalize(glob.trim()))));
			}
		}
		return new GlobMatcher(Collections.unmodifiableList(compiled));
	}

	static String normalize(String glob) {
		String g = glob.replace('\\', '/');
		while (g.startsWith("./")) {
			g = g.substring(2);
		}
		if (g.endsWith("/")) {
			g = g + "**";
		}
		return g;
	}

	static String toRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < glob.length()) {
			char c = glob.charAt(i);
			if (c == '*' && glob.startsWith("**", i)) {
				i = appendDoubleStar(glob, i, sb);
			} else {
				sb.append(translate(c));
				i++;
			}
		}
		return sb.toString();
	}

	/**
	 * Translates {@code **} at {@code index}: {@code **}{@code /} at a segment start matches zero or more
	 * directories, a trailing {@code /**} matches the directory itself and everything below, otherwise any text.
	 *
	 * @return index of the next character to translate
	 */
	private static int appendDoubleStar(String glob, int index, StringBuilder sb) {
		boolean afterSlash = index > 0 && glob.charAt(index - 1) == '/';
		boolean segmentStart = index == 0 || afterSlash;
		if (segmentStart && glob.startsWith("/", index + 2)) {
			sb.append("(?:.*/)?");
			return index + 3;
		}
		if (afterSlash && index + 2 == glob.length()) {
			sb.setLength(sb.length() - 1); // drop the '/' just emitted
			sb.append("(?:/.*)?");
			return index + 2;
		}
		sb.append(".*");
		return index + 2;
	}

	/**
	 * Regex for a single glob character other than {@code **}.
	 */
	private static String translate(char c) {
		if (c == '*') {
			return "[^/]*";
		}
		if (c == '?') {
			return "[^/]";
		}
		return "\\.[]{}()+-^$|".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c);
	}

	/**
	 * @param relativePath path relative to the scanned directory, with {@code /} separators
	 */
	public boolean matches(String relativePath) {
		for (Pattern pattern : patterns) {
			if (pattern.matcher(relativePath).matches()) {
				return true;
			}
		}
		return false;
	}

	public boolean isEmpty() {
		return patterns.isEmpty();
	}
}
