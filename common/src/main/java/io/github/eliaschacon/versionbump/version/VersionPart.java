package io.github.eliaschacon.versionbump.version;

import java.util.Locale;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * What to change in the version: one numeric component to increment, or {@link #RELEASE} to drop the
 * {@code -SNAPSHOT} suffix without changing the numbers.
 */
public enum VersionPart {
	MAJOR,
	MINOR,
	PATCH,
	BUILD,
	/** {@code 1.2.3-SNAPSHOT} becomes {@code 1.2.3}; numbers and qualifier are kept. */
	RELEASE;

	public static final String ACCEPTED_VALUES = "major, minor, patch, build, release";

	/**
	 * Parses a user supplied value (case-insensitive).
	 *
	 * @throws BumpException when the value is missing or unknown
	 */
	public static VersionPart parse(String value) throws BumpException {
		if (value == null || value.trim().isEmpty()) {
			throw new BumpException("Missing bump part. Set -Dbump.part=<major|minor|patch|build|release>"
				+ " (or <part> in the plugin configuration), or set -Dbump.newVersion=<version>.");
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new BumpException("Invalid bump part '" + value + "'. Accepted values: " + ACCEPTED_VALUES + ".");
		}
	}

	@Override
	public String toString() {
		return name().toLowerCase(Locale.ROOT);
	}
}
