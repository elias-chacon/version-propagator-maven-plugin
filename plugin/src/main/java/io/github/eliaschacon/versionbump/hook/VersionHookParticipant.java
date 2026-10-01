package io.github.eliaschacon.versionbump.hook;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.slf4j.LoggerFactory;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.BumpService;
import io.github.eliaschacon.versionbump.concurrent.PlatformTaskRunner;
import io.github.eliaschacon.versionbump.pom.PomFile;
import io.github.eliaschacon.versionbump.version.Version;

/**
 * Hook into the Maven session: when a goal that rewrites the project versions has run (by default
 * {@code release:update-versions} and {@code versions:set}), the new versions are propagated to the files
 * configured for the plugin (files, includes, replacement rules). POMs are left to the goal that changed them.
 *
 * <p>Active only when the plugin is declared with {@code <extensions>true</extensions>}. Registered through
 * {@code META-INF/plexus/components.xml}.</p>
 *
 * <ol>
 *     <li>{@link #afterProjectsRead}: records the version of every reactor project (before any goal runs);</li>
 *     <li>{@link #afterSessionEnd}: if the build succeeded and a monitored goal ran, re-reads the POMs from disk and
 *     propagates every {@code old -> new} change.</li>
 * </ol>
 */
public class VersionHookParticipant extends AbstractMavenLifecycleParticipant {

	private static final String RELEASE_PREPARE = "release:prepare";

	private final Log log = new Slf4jLog(LoggerFactory.getLogger(VersionHookParticipant.class));

	/** Version per reactor POM, captured before the goals run. */
	private final Map<Path, String> before = new LinkedHashMap<>();

	@Override
	public void afterProjectsRead(MavenSession session) {
		before.clear();
		for (MavenProject project : session.getProjects()) {
			if (project.getFile() != null) {
				before.put(pathOf(project), project.getVersion());
			}
		}
	}

	@Override
	public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
		if (before.isEmpty() || session.getResult().hasExceptions()) {
			return;
		}
		List<String> goals = session.getGoals();
		if (containsGoal(goals, RELEASE_PREPARE)) {
			log.debug("release:prepare commits and tags inside the goal: the hook does not act; propagate with the"
				+ " sync goal in its preparationGoals and completionGoals (see the plugin documentation).");
		}
		HookConfig config = HookConfig.read(session.getTopLevelProject(), modulePoms(session),
			session.getUserProperties());
		if (config.isSkip() || !monitored(goals, config.getGoals())) {
			return;
		}
		try {
			new BumpService(log, new PlatformTaskRunner()).propagate(config.getRequest(), changes());
		} catch (BumpException e) {
			// The goal finished (its summary already says BUILD SUCCESS) and the POMs hold the new version.
			throw new MavenExecutionException("Version propagation failed after " + String.join(" ", goals)
				+ ": " + e.getMessage() + " The POMs already have the new version and no other file was changed;"
				+ " fix the files or the configuration, then run 'mvn propagate:sync -Dbump.oldVersion=<previous version>'.", e);
		}
	}

	/** Versions now on disk compared with the versions captured before the goals ran. */
	Map<Version, Version> changes() throws BumpException {
		Map<Version, Version> changes = new LinkedHashMap<>();
		for (Map.Entry<Path, String> entry : before.entrySet()) {
			String current = PomFile.load(entry.getKey()).version();
			String previous = entry.getValue();
			if (current == null || previous == null || previous.equals(current)) {
				continue;
			}
			Version old = Version.parse(previous);
			Version next = Version.parse(current);
			Version known = changes.put(old, next);
			if (known != null && !known.equals(next)) {
				throw new BumpException("Ambiguous version change: modules moved from " + old + " to both " + known
					+ " and " + next + ".");
			}
		}
		return changes;
	}

	/** Test seam: the versions captured before the goals ran. */
	Map<Path, String> before() {
		return before;
	}

	static boolean monitored(List<String> sessionGoals, List<String> hookGoals) {
		for (String hookGoal : hookGoals) {
			if (containsGoal(sessionGoals, hookGoal)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsGoal(List<String> sessionGoals, String goal) {
		for (String sessionGoal : sessionGoals) {
			if (Goals.same(sessionGoal, goal)) {
				return true;
			}
		}
		return false;
	}

	private static List<Path> modulePoms(MavenSession session) {
		Path root = pathOf(session.getTopLevelProject());
		List<Path> poms = new ArrayList<>();
		for (MavenProject project : session.getProjects()) {
			if (project.getFile() != null && !pathOf(project).equals(root)) {
				poms.add(pathOf(project));
			}
		}
		return poms;
	}

	private static Path pathOf(MavenProject project) {
		return project.getFile().toPath().toAbsolutePath().normalize();
	}
}
