package io.github.eliaschacon.versionbump.hook;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import io.github.eliaschacon.versionbump.BumpRequest;
import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.io.ChangeApplier;
import lombok.Data;

/**
 * Hook settings, read from the {@code <configuration>} of the plugin in the execution root POM (the same
 * parameters as the goals) and from the user properties ({@code -Dbump.dryRun}, {@code -Dbump.hook.skip}).
 */
@Data
final class HookConfig {

	static final String PLUGIN_KEY = "io.github.eliaschacon:version-propagator-maven-plugin";
	static final List<String> DEFAULT_GOALS = Collections.unmodifiableList(
		Arrays.asList("release:update-versions", "versions:set"));

	private final boolean skip;
	private final List<String> goals;
	private final BumpRequest request;

	/**
	 * @param root           execution root project
	 * @param modulePoms     POMs of the other reactor projects (never edited by the hook)
	 * @param userProperties {@code -D} properties of the session
	 */
	static HookConfig read(MavenProject root, List<Path> modulePoms, Properties userProperties) {
		Xpp3Dom configuration = configuration(root);
		Path baseDir = root.getBasedir().toPath();
		boolean skip = bool(userProperties.getProperty("bump.hook.skip"), bool(value(configuration, "hookSkip"), false));
		List<String> goals = list(configuration, "hookGoals");
		BumpRequest request = BumpRequest.builder()
			.baseDir(baseDir)
			.rootPom(root.getFile().toPath().toAbsolutePath().normalize())
			.modulePoms(modulePoms)
			.updatePom(false)
			.updateFiles(true)
			.dryRun(bool(userProperties.getProperty("bump.dryRun"), bool(value(configuration, "dryRun"), false)))
			.files(paths(baseDir, list(configuration, "files")))
			.directories(paths(baseDir, list(configuration, "directories")))
			.includes(list(configuration, "includes"))
			.excludes(list(configuration, "excludes"))
			.useDefaultExcludes(bool(value(configuration, "useDefaultExcludes"), true))
			.replacements(replacements(configuration))
			.encoding(text(value(configuration, "encoding"), "UTF-8"))
			.createBackup(bool(value(configuration, "createBackup"), false))
			.backupSuffix(text(value(configuration, "backupSuffix"), ChangeApplier.DEFAULT_BACKUP_SUFFIX))
			.overwriteBackups(bool(value(configuration, "overwriteBackups"), false))
			.failOnNoMatch(bool(value(configuration, "failOnNoMatch"), false))
			.followSymlinks(bool(value(configuration, "followSymlinks"), false))
			.build();
		return new HookConfig(skip, goals.isEmpty() ? DEFAULT_GOALS : goals, request);
	}

	/** Plugin configuration of the project ({@code null} when the plugin has none). */
	private static Xpp3Dom configuration(MavenProject project) {
		Plugin plugin = project.getBuild() == null ? null : project.getBuild().getPluginsAsMap().get(PLUGIN_KEY);
		return plugin == null ? null : (Xpp3Dom) plugin.getConfiguration();
	}

	private static String value(Xpp3Dom parent, String name) {
		Xpp3Dom child = parent == null ? null : parent.getChild(name);
		return child == null || child.getValue() == null ? null : child.getValue().trim();
	}

	private static List<String> list(Xpp3Dom parent, String name) {
		List<String> values = new ArrayList<>();
		Xpp3Dom container = parent == null ? null : parent.getChild(name);
		if (container != null) {
			for (Xpp3Dom item : container.getChildren()) {
				if (item.getValue() != null && !item.getValue().trim().isEmpty()) {
					values.add(item.getValue().trim());
				}
			}
		}
		return values;
	}

	private static List<ReplacementRule> replacements(Xpp3Dom configuration) {
		List<ReplacementRule> rules = new ArrayList<>();
		Xpp3Dom container = configuration == null ? null : configuration.getChild("replacements");
		if (container != null) {
			for (Xpp3Dom item : container.getChildren()) {
				// A missing <search> means "default search"; a missing <replace> stays null as in the goals.
				Xpp3Dom replace = item.getChild("replace");
				rules.add(ReplacementRule.builder()
					.search(value(item, "search"))
					.replace(replace == null ? null : replace.getValue())
					.files(list(item, "files"))
					.includes(list(item, "includes"))
					.excludes(list(item, "excludes"))
					.build());
			}
		}
		return rules;
	}

	private static List<Path> paths(Path baseDir, List<String> values) {
		List<Path> paths = new ArrayList<>();
		for (String value : values) {
			paths.add(baseDir.resolve(value).normalize());
		}
		return paths;
	}

	private static boolean bool(String value, boolean defaultValue) {
		return value == null || value.isEmpty() ? defaultValue : Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
	}

	private static String text(String value, String defaultValue) {
		return value == null || value.isEmpty() ? defaultValue : value;
	}
}
