package io.github.eliaschacon.versionbump.files;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.version.Version;

/**
 * Values available to replacement rules, referenced as {@code ${name}} or {@code @name@}.
 *
 * <p>{@code @name@} exists because Maven interpolates {@code ${...}} inside POM configuration:
 * a value made only of an unknown expression (e.g. {@code <replace>${newVersion}</replace>}) is
 * injected as {@code null}. Use {@code @newVersion@} or the Maven escape {@code $${newVersion}}
 * in the POM; both syntaxes work from Java code and tests.</p>
 */
public final class VersionTokens {

	/**
	 * Matches {@code ${name}} or {@code @name@} for any identifier; unknown names are left untouched.
	 */
	private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z]+)}|@([A-Za-z]+)@");

	private static final String OLD_VERSION = "oldVersion";
	private static final String NEW_VERSION = "newVersion";
	private static final String OLD_PREFIX = "old";
	private static final String NEW_PREFIX = "new";

	private static final String[] NAMES = {
		OLD_VERSION, NEW_VERSION,
		"oldMajor", "oldMinor", "oldPatch", "oldBuild",
		"newMajor", "newMinor", "newPatch", "newBuild"};

	private final Map<String, String> values;
	private final Version oldVersion;
	private final Version newVersion;

	public VersionTokens(Version oldVersion, Version newVersion) {
		this.oldVersion = oldVersion;
		this.newVersion = newVersion;
		Map<String, String> map = new LinkedHashMap<>();
		map.put(OLD_VERSION, oldVersion.toString());
		map.put(NEW_VERSION, newVersion.toString());
		put(map, OLD_PREFIX, oldVersion);
		put(map, NEW_PREFIX, newVersion);
		this.values = Collections.unmodifiableMap(map);
	}

	private static void put(Map<String, String> map, String prefix, Version v) {
		map.put(prefix + "Major", String.valueOf(v.getMajor()));
		map.put(prefix + "Minor", String.valueOf(v.getMinor()));
		map.put(prefix + "Patch", String.valueOf(v.getPatch()));
		if (v.getBuild() != null) {
			map.put(prefix + "Build", String.valueOf(v.getBuild()));
		}
	}

	private static boolean isKnown(String name) {
		for (String known : NAMES) {
			if (known.equals(name)) {
				return true;
			}
		}
		return false;
	}

	public String get(String name) {
		return values.get(name);
	}

	public String oldVersion() {
		return values.get(OLD_VERSION);
	}

	public String newVersion() {
		return values.get(NEW_VERSION);
	}

	/**
	 * Replaces every known token in {@code template} with its escaped value.
	 *
	 * @throws BumpException when a known token has no value (e.g. {@code oldBuild} for {@code 1.2.3})
	 */
	public String expand(String template, Escaper escaper) throws BumpException {
		Matcher m = TOKEN.matcher(template);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (m.find()) {
			// group 1: ${name}, group 2: @name@
			String name = m.group(m.group(1) != null ? 1 : 2);
			if (!isKnown(name)) {
				continue;
			}
			String value = values.get(name);
			if (value == null) {
				Version source = name.startsWith(OLD_PREFIX) ? oldVersion : newVersion;
				throw new BumpException("Token '" + name + "' is not available: version " + source
					+ " has no BUILD component.");
			}
			out.append(template, last, m.start()).append(escaper.escape(value));
			last = m.end();
		}
		return out.append(template.substring(last)).toString();
	}

	/**
	 * Callback used to escape token values for the target syntax (regex or replacement string).
	 */
	public interface Escaper {
		String escape(String value);
	}
}
