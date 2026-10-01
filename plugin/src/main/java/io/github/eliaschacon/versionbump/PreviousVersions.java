package io.github.eliaschacon.versionbump;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.plugin.logging.Log;

import io.github.eliaschacon.versionbump.pom.PomFile;
import io.github.eliaschacon.versionbump.version.Version;
import lombok.RequiredArgsConstructor;

/**
 * Finds, for every reactor POM, the version it had before the current change. Sources, in order:
 * <ol>
 *     <li>the explicit {@code bump.oldVersion} (applied to the execution root);</li>
 *     <li>the files {@code release:prepare} leaves: in the preparation goals the POM holds the release version and the
 *     previous one is in {@code pom.xml.releaseBackup}; in the completion goals the POM holds the next development
 *     version and the previous one is the release version of {@code release.properties}.</li>
 * </ol>
 * Without any of them the previous version is unknown and the goal fails: no external tool (SCM) is queried.
 */
@RequiredArgsConstructor
final class PreviousVersions {

	static final String RELEASE_BACKUP_SUFFIX = ".releaseBackup";
	static final String RELEASE_PROPERTIES = "release.properties";

	private final Log log;
	private final Path baseDir;

	/**
	 * @param poms        reactor POMs, execution root first
	 * @param explicitOld {@code bump.oldVersion}, or {@code null}
	 * @return previous version to current version, for every POM whose version changed
	 * @throws BumpException when the previous version cannot be found
	 */
	Map<Version, Version> resolve(List<Path> poms, String explicitOld) throws BumpException {
		Map<Version, Version> changes = new LinkedHashMap<>();
		if (explicitOld != null && !explicitOld.trim().isEmpty()) {
			Version current = Version.parse(PomFile.load(poms.get(0)).version());
			changes.put(Version.parse(explicitOld), current);
			return changes;
		}
		Properties release = releaseProperties();
		boolean found = false;
		for (Path pom : poms) {
			PomFile file = PomFile.load(pom);
			String current = file.version();
			String previous = fromRelease(release, file, pom);
			found |= previous != null;
			if (current != null && previous != null && !previous.equals(current)) {
				log.debug("Previous version of " + pom + ": " + previous + " (now " + current + ")");
				put(changes, Version.parse(previous), Version.parse(current));
			}
		}
		if (!found) {
			throw new BumpException("The previous version is unknown: set -Dbump.oldVersion=<version>. It is only detected"
				+ " inside release:prepare (from " + RELEASE_PROPERTIES + " and pom.xml" + RELEASE_BACKUP_SUFFIX + ")."
				+ " For release:update-versions and versions:set, the lifecycle hook propagates without it.");
		}
		return changes;
	}

	private static void put(Map<Version, Version> changes, Version previous, Version current) throws BumpException {
		Version known = changes.get(previous);
		if (known != null && !known.equals(current)) {
			throw new BumpException("Ambiguous version change: modules moved from " + previous + " to both " + known
				+ " and " + current + ". Run the goal with -Dbump.oldVersion on the module to propagate.");
		}
		changes.put(previous, current);
	}

	private Properties releaseProperties() throws BumpException {
		Path file = baseDir.resolve(RELEASE_PROPERTIES);
		Properties properties = new Properties();
		if (!Files.isRegularFile(file)) {
			return properties;
		}
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		} catch (IOException e) {
			throw new BumpException("Cannot read " + file + ": " + e.getMessage(), e);
		}
		return properties;
	}

	private static String fromRelease(Properties release, PomFile file, Path pom) throws BumpException {
		Path backup = pom.resolveSibling(pom.getFileName() + RELEASE_BACKUP_SUFFIX);
		String backupVersion = Files.isRegularFile(backup) ? PomFile.load(backup).version() : null;
		String key = file.getDocument().key();
		String releaseVersion = release.getProperty("project.rel." + key);
		String developmentVersion = release.getProperty("project.dev." + key);
		String current = file.version();
		if (current != null && current.equals(developmentVersion) && releaseVersion != null) {
			return releaseVersion;
		}
		return backupVersion;
	}
}
