package io.github.eliaschacon.versionbump.version;

import java.util.Locale;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * Version component to increment.
 */
public enum VersionPart {
	MAJOR,
	MINOR,
	PATCH,
	BUILD;

	/**
	 * Parses a user supplied value (case-insensitive).
	 *
	 * @throws BumpException when the value is missing or unknown
	 */
	public static VersionPart parse(String value) throws BumpException {
		if (value == null || value.trim().isEmpty()) {
			throw new BumpException("Missing bump part. Set -Dbump.part=<major|minor|patch|build>"
				+ " or <part> in the plugin configuration.");
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new BumpException("Invalid bump part '" + value + "'. Accepted values: major, minor, patch, build.");
		}
	}

	@Override
	public String toString() {
		return name().toLowerCase(Locale.ROOT);
	}
}
