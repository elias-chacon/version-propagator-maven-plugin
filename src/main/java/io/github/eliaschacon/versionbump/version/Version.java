package io.github.eliaschacon.versionbump.version;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Immutable numeric version: {@code MAJOR.MINOR.PATCH[.BUILD][-QUALIFIER][-SNAPSHOT]}.
 *
 * <p>Parsing is intentionally strict: anything that cannot be bumped without guessing
 * (two components, leading zeros, non-numeric components, huge numbers...) is rejected.</p>
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class Version {

	public static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

	public static final String ACCEPTED_FORMATS = "MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH.BUILD,"
		+ " optionally followed by -QUALIFIER and/or -SNAPSHOT"
		+ " (e.g. 1.2.3, 1.2.3.4, 1.2.3-SNAPSHOT, 1.2.3-RC1)."
		+ " Components must be non-negative integers without leading zeros";

	private static final String NUMBER = "(0|[1-9]\\d*)";
	/*
	 * The qualifier is matched with a plain character class (no repeated group, which Java evaluates
	 * recursively and may overflow the stack on long input); separators are validated in isValidQualifier.
	 */
	private static final Pattern PATTERN = Pattern.compile(
		NUMBER + "\\." + NUMBER + "\\." + NUMBER + "(?:\\." + NUMBER + ")?"
			+ "(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?");

	private final int major;
	private final int minor;
	private final int patch;
	/**
	 * BUILD component, or {@code null} for three-component versions.
	 */
	private final Integer build;
	/**
	 * Qualifier without the leading dash (never {@code SNAPSHOT}), or {@code null}.
	 */
	private final String qualifier;
	private final boolean snapshot;

	/**
	 * Parses a version string.
	 *
	 * @throws BumpException if the value does not follow {@link #ACCEPTED_FORMATS}
	 */
	public static Version parse(String value) throws BumpException {
		if (value == null || value.trim().isEmpty()) {
			throw invalid(value);
		}
		String text = value.trim();
		boolean snapshot = text.endsWith(SNAPSHOT_SUFFIX);
		String core = snapshot ? text.substring(0, text.length() - SNAPSHOT_SUFFIX.length()) : text;

		Matcher m = PATTERN.matcher(core);
		if (!m.matches() || (m.group(5) != null && !isValidQualifier(m.group(5)))) {
			throw invalid(value);
		}
		try {
			return new Version(
				Integer.parseInt(m.group(1)),
				Integer.parseInt(m.group(2)),
				Integer.parseInt(m.group(3)),
				m.group(4) == null ? null : Integer.valueOf(m.group(4)),
				m.group(5),
				snapshot);
		} catch (NumberFormatException e) {
			throw invalid(value);
		}
	}

	/**
	 * Alphanumeric segments separated by single {@code .} or {@code -}, not ending with a separator.
	 * A remaining "SNAPSHOT" (e.g. {@code 1.2.3-SNAPSHOT-SNAPSHOT} or {@code -snapshot}) is ambiguous: rejected.
	 */
	private static boolean isValidQualifier(String qualifier) {
		char last = qualifier.charAt(qualifier.length() - 1);
		return last != '.' && last != '-'
			&& !qualifier.contains("..") && !qualifier.contains("--")
			&& !qualifier.contains(".-") && !qualifier.contains("-.")
			&& !qualifier.toUpperCase(Locale.ROOT).contains("SNAPSHOT");
	}

	private static BumpException invalid(String value) {
		return new BumpException("Invalid version '" + value + "'. Accepted formats: " + ACCEPTED_FORMATS + ".");
	}

	/**
	 * Computes the next version.
	 *
	 * <ul>
	 *     <li>{@code major}: MAJOR+1, other components reset to 0.</li>
	 *     <li>{@code minor}: MINOR+1, PATCH (and BUILD) reset to 0.</li>
	 *     <li>{@code patch}: PATCH+1, BUILD reset to 0 when present.</li>
	 *     <li>{@code build}: BUILD+1; a three-component version gains a BUILD of 1.</li>
	 * </ul>
	 * {@code -SNAPSHOT} is always preserved; other qualifiers follow {@code policy}.
	 */
	public Version bump(VersionPart part, QualifierPolicy policy) throws BumpException {
		Objects.requireNonNull(part, "part");
		Objects.requireNonNull(policy, "policy");

		String nextQualifier = qualifier;
		if (qualifier != null) {
			if (policy == QualifierPolicy.FAIL) {
				throw new BumpException("Version '" + this + "' has the qualifier '" + qualifier
					+ "'. Only -SNAPSHOT is handled automatically. Set bump.qualifierPolicy=preserve"
					+ " to keep it or bump.qualifierPolicy=remove to drop it. Accepted formats: "
					+ ACCEPTED_FORMATS + ".");
			}
			if (policy == QualifierPolicy.REMOVE) {
				nextQualifier = null;
			}
		}

		Integer resetBuild = build == null ? null : 0;
		switch (part) {
			case MAJOR:
				return new Version(increment(major, "MAJOR"), 0, 0, resetBuild, nextQualifier, snapshot);
			case MINOR:
				return new Version(major, increment(minor, "MINOR"), 0, resetBuild, nextQualifier, snapshot);
			case PATCH:
				return new Version(major, minor, increment(patch, "PATCH"), resetBuild, nextQualifier, snapshot);
			case BUILD:
				int nextBuild = build == null ? 1 : increment(build, "BUILD");
				return new Version(major, minor, patch, nextBuild, nextQualifier, snapshot);
			default:
				throw new IllegalStateException("Unhandled part " + part);
		}
	}

	private int increment(int value, String component) throws BumpException {
		if (value == Integer.MAX_VALUE) {
			throw new BumpException("Cannot increment " + component + " of version '" + this + "': value too large.");
		}
		return value + 1;
	}

	/**
	 * Canonical textual form; also used as the POM/file value, so it is written by hand (not by Lombok).
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
		if (build != null) {
			sb.append('.').append(build);
		}
		if (qualifier != null) {
			sb.append('-').append(qualifier);
		}
		if (snapshot) {
			sb.append(SNAPSHOT_SUFFIX);
		}
		return sb.toString();
	}
}
