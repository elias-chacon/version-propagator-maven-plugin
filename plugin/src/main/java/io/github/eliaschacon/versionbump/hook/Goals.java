package io.github.eliaschacon.versionbump.hook;

import java.util.Locale;

/**
 * Compares goals written in different forms: {@code release:update-versions},
 * {@code org.apache.maven.plugins:maven-release-plugin:update-versions} or
 * {@code org.apache.maven.plugins:maven-release-plugin:3.0.1:update-versions} are the same goal.
 */
final class Goals {

	private Goals() {
	}

	/**
	 * @return {@code prefix:goal} in lower case, or {@code null} for a lifecycle phase (no {@code :})
	 */
	static String normalize(String goal) {
		if (goal == null || goal.indexOf(':') < 0) {
			return null;
		}
		String[] parts = goal.trim().split(":");
		String name = parts[parts.length - 1];
		String prefix = parts.length == 2 ? parts[0] : prefixOf(parts[1]);
		return (prefix + ":" + name).toLowerCase(Locale.ROOT);
	}

	/** Maven naming conventions: {@code maven-release-plugin} and {@code versions-maven-plugin} give the prefix. */
	static String prefixOf(String artifactId) {
		String id = artifactId.toLowerCase(Locale.ROOT);
		if (id.startsWith("maven-") && id.endsWith("-plugin")) {
			return id.substring("maven-".length(), id.length() - "-plugin".length());
		}
		if (id.endsWith("-maven-plugin")) {
			return id.substring(0, id.length() - "-maven-plugin".length());
		}
		return id;
	}

	static boolean same(String a, String b) {
		String left = normalize(a);
		return left != null && left.equals(normalize(b));
	}
}
