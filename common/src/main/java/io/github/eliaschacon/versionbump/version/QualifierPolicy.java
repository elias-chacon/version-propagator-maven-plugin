package io.github.eliaschacon.versionbump.version;

import java.util.Locale;

import io.github.eliaschacon.versionbump.BumpException;

/**
 * What to do with a qualifier other than {@code -SNAPSHOT} (for example {@code -RC1}) when bumping.
 * {@code -SNAPSHOT} is never considered a qualifier: it is always preserved.
 */
public enum QualifierPolicy {
	/**
	 * Reject versions that carry a qualifier (default: no silent assumptions).
	 */
	FAIL,
	/**
	 * Keep the qualifier: {@code 1.2.3-RC1} + patch = {@code 1.2.4-RC1}.
	 */
	PRESERVE,
	/**
	 * Drop the qualifier: {@code 1.2.3-RC1} + patch = {@code 1.2.4}.
	 */
	REMOVE;

	public static QualifierPolicy parse(String value) throws BumpException {
		if (value == null || value.trim().isEmpty()) {
			return FAIL;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new BumpException("Invalid qualifier policy '" + value + "'. Accepted values: fail, preserve, remove.");
		}
	}

	@Override
	public String toString() {
		return name().toLowerCase(Locale.ROOT);
	}
}
