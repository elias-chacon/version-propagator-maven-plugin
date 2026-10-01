package io.github.eliaschacon.versionbump;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.apache.maven.plugin.logging.Log;

import io.github.eliaschacon.versionbump.concurrent.TaskRunner;
import io.github.eliaschacon.versionbump.files.FileReplacer;
import io.github.eliaschacon.versionbump.files.FileScanner;
import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.files.VersionTokens;
import io.github.eliaschacon.versionbump.io.AtomicFileWriter;
import io.github.eliaschacon.versionbump.io.ChangeApplier;
import io.github.eliaschacon.versionbump.io.FileChange;
import io.github.eliaschacon.versionbump.pom.PomFile;
import io.github.eliaschacon.versionbump.pom.PomVersionPlanner;
import io.github.eliaschacon.versionbump.version.Version;
import io.github.eliaschacon.versionbump.version.VersionChange;
import lombok.RequiredArgsConstructor;

/**
 * Orchestrates a version change: validate configuration, compute the new version, plan every change
 * (POMs and additional files), then either report it (dry-run) or write it in one transaction.
 * Nothing is written before the whole plan has been computed and validated.
 *
 * <ul>
 *     <li>{@link #execute}: the {@code bump} goal; computes the next version and edits POMs and files.</li>
 *     <li>{@link #propagate}: the {@code sync} goal and the hook; the POMs already hold the new version (changed by
 *     another tool such as {@code release:update-versions}), so only the additional files are edited.</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class BumpService {

	private final Log log;
	private final AtomicFileWriter writer;
	/** Runs the per-file planning; see {@link TaskRunner} for the ordering and failure contract. */
	private final TaskRunner runner;

	public BumpService(Log log, TaskRunner runner) {
		this(log, new AtomicFileWriter(), runner);
	}

	private static Charset charset(String encoding) throws BumpException {
		try {
			return Charset.forName(encoding == null || encoding.trim().isEmpty() ? "UTF-8" : encoding.trim());
		} catch (IllegalArgumentException e) {
			throw new BumpException("Unsupported encoding '" + encoding + "'.", e);
		}
	}

	private static String currentVersion(BumpRequest request, PomFile rootPom) throws BumpException {
		if (rootPom != null) {
			String literal = PomVersionPlanner.explicitVersion(rootPom);
			return request.getProjectVersion() != null ? request.getProjectVersion() : literal;
		}
		if (request.getProjectVersion() == null) {
			throw new BumpException("The project version is unknown.");
		}
		return request.getProjectVersion();
	}

	private static VersionChange changeOf(BumpRequest request) {
		return VersionChange.builder()
			.part(request.getPart())
			.newVersion(request.getNewVersion())
			.snapshot(request.getSnapshot())
			.qualifier(request.getQualifier())
			.qualifierPolicy(request.getQualifierPolicy())
			.build();
	}

	public BumpResult execute(BumpRequest request) throws BumpException {
		VersionChange change = changeOf(request);
		Charset charset = charset(request.getEncoding());
		if (!request.isUpdatePom() && !request.isUpdateFiles()) {
			throw new BumpException("Nothing to do: both bump.updatePom and bump.updateFiles are false.");
		}
		Path baseDir = FileScanner.normalize(request.getBaseDir());

		PomFile rootPom = request.isUpdatePom() ? PomFile.load(request.getRootPom()) : null;
		Version oldVersion = Version.parse(currentVersion(request, rootPom));
		Version newVersion = change.apply(oldVersion);
		log.info("Bumping " + change.describe() + ": " + oldVersion + " -> " + newVersion
			+ (request.isDryRun() ? " (dry run)" : ""));

		List<FileChange> changes = new ArrayList<>(planPoms(request, rootPom, oldVersion, newVersion));
		List<FileReplacer.Outcome> outcomes = request.isUpdateFiles()
			? planFiles(request, baseDir, charset, Collections.singletonList(new VersionTokens(oldVersion, newVersion)))
			: Collections.<FileReplacer.Outcome>emptyList();
		return finish(request, baseDir, oldVersion, newVersion, changes, outcomes, "Version bumped to " + newVersion);
	}

	/**
	 * Propagates version changes that were already applied to the POMs (by {@code release:update-versions},
	 * {@code versions:set}...) to the configured files. POMs are never edited here.
	 *
	 * <p>Several modules may change from different versions; each distinct {@code old -> new} pair is applied. Pairs
	 * that could chain (a new version equal to another old version) or conflict (one old version, two new ones) are
	 * rejected, because the result would depend on the order. Without any configured file, nothing is done.</p>
	 *
	 * @param changes old and new version of every project whose version changed
	 * @return the result, or {@code null} when there is nothing to do
	 */
	public BumpResult propagate(BumpRequest request, Map<Version, Version> changes) throws BumpException {
		Charset charset = charset(request.getEncoding());
		Path baseDir = FileScanner.normalize(request.getBaseDir());
		List<VersionTokens> pairs = pairsOf(changes);
		if (pairs.isEmpty()) {
			log.info("Version propagation: no project version changed, nothing to propagate.");
			return null;
		}
		if (!selectsFiles(request)) {
			log.warn("Version propagation: no file is configured (files, includes or <replacement> files/includes):"
				+ " nothing to propagate.");
			return null;
		}
		Version oldVersion = Version.parse(pairs.get(0).oldVersion());
		Version newVersion = Version.parse(pairs.get(0).newVersion());
		log.info("Propagating " + describe(pairs) + " to the configured files" + (request.isDryRun() ? " (dry run)" : ""));
		List<FileReplacer.Outcome> outcomes = planFiles(request, baseDir, charset, pairs);
		return finish(request, baseDir, oldVersion, newVersion, new ArrayList<FileChange>(), outcomes,
			"Version " + describe(pairs) + " propagated");
	}

	private static List<VersionTokens> pairsOf(Map<Version, Version> changes) throws BumpException {
		Map<Version, Version> distinct = new LinkedHashMap<>();
		for (Map.Entry<Version, Version> change : changes.entrySet()) {
			if (!change.getKey().equals(change.getValue())) {
				distinct.put(change.getKey(), change.getValue());
			}
		}
		for (Version next : distinct.values()) {
			if (distinct.containsKey(next)) {
				throw new BumpException("Ambiguous version change: " + next + " is both a new and an old version ("
					+ distinctToString(distinct) + "). Propagate the changes one at a time.");
			}
		}
		List<VersionTokens> pairs = new ArrayList<>();
		for (Map.Entry<Version, Version> change : distinct.entrySet()) {
			pairs.add(new VersionTokens(change.getKey(), change.getValue()));
		}
		return pairs;
	}

	private static String distinctToString(Map<Version, Version> changes) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Version, Version> change : changes.entrySet()) {
			sb.append(sb.length() == 0 ? "" : ", ").append(change.getKey()).append(" -> ").append(change.getValue());
		}
		return sb.toString();
	}

	private static String describe(List<VersionTokens> pairs) {
		StringBuilder sb = new StringBuilder();
		for (VersionTokens pair : pairs) {
			sb.append(sb.length() == 0 ? "" : ", ").append(pair.oldVersion()).append(" -> ").append(pair.newVersion());
		}
		return sb.toString();
	}

	/** {@code true} when some file is selected: global files/includes or a rule scope. */
	static boolean selectsFiles(BumpRequest request) {
		if (!request.getFiles().isEmpty() || !request.getIncludes().isEmpty()) {
			return true;
		}
		for (ReplacementRule rule : request.getReplacements()) {
			if (rule.isScoped()) {
				return true;
			}
		}
		return false;
	}

	private BumpResult finish(BumpRequest request, Path baseDir, Version oldVersion, Version newVersion,
							  List<FileChange> changes, List<FileReplacer.Outcome> outcomes, String summary)
		throws BumpException {
		for (FileReplacer.Outcome outcome : outcomes) {
			if (outcome.getChange() != null) {
				changes.add(outcome.getChange());
			}
		}
		report(request, baseDir, changes);
		boolean written = write(request, changes, newVersion, summary);

		return BumpResult.builder()
			.oldVersion(oldVersion)
			.newVersion(newVersion)
			.changes(changes)
			.fileOutcomes(outcomes)
			.written(written)
			.build();
	}

	private List<FileChange> planPoms(BumpRequest request, PomFile rootPom, Version oldVersion, Version newVersion)
		throws BumpException {
		if (rootPom == null) {
			return Collections.emptyList();
		}
		List<PomFile> modules = new ArrayList<>();
		for (Path modulePom : request.getModulePoms()) {
			modules.add(PomFile.load(modulePom));
		}
		return new PomVersionPlanner(log).plan(rootPom, modules, oldVersion.toString(), newVersion.toString(),
			request.isUpdateModules());
	}

	/**
	 * Plans the additional files. With several version pairs, every rule is compiled for every pair and applied in
	 * pair order on each file (the pairs never chain, see {@link #pairsOf}).
	 */
	private List<FileReplacer.Outcome> planFiles(BumpRequest request, Path baseDir, Charset charset,
												 List<VersionTokens> pairs) throws BumpException {
		List<ReplacementRule> rules = request.getReplacements().isEmpty()
			? Collections.singletonList(ReplacementRule.defaultRule()) : request.getReplacements();
		List<ReplacementRule.Compiled> compiled = new ArrayList<>();
		for (VersionTokens tokens : pairs) {
			for (ReplacementRule rule : rules) {
				compiled.add(rule.compile(tokens, baseDir));
			}
		}
		SortedSet<Path> candidates = new FileSelector(log, baseDir, request).select(rules);
		FileReplacer replacer = new FileReplacer(compiled, charset, describe(pairs));

		// Planning only reads files, so it may run concurrently; outcomes come back in candidate order.
		List<TaskRunner.Task<FileReplacer.Outcome>> tasks = new ArrayList<>();
		for (Path file : candidates) {
			tasks.add(() -> replacer.process(file));
		}
		log.debug("Planning " + tasks.size() + " file(s) with the " + runner.name() + " task runner");
		List<FileReplacer.Outcome> outcomes = runner.runAll(tasks);
		int total = 0;
		for (FileReplacer.Outcome outcome : outcomes) {
			total += outcome.getChange() == null ? 0 : outcome.getReplacements();
			logOutcome(outcome, FileSelector.display(baseDir, outcome.getPath()), charset);
		}
		if (total == 0 && request.isFailOnNoMatch()) {
			StringBuilder versions = new StringBuilder();
			for (VersionTokens pair : pairs) {
				versions.append(versions.length() == 0 ? "" : ", ").append(pair.oldVersion());
			}
			throw new BumpException("No occurrence of version " + versions + " found in the "
				+ candidates.size() + " configured file(s) (bump.failOnNoMatch=true).");
		}
		return outcomes;
	}

	/**
	 * Changed files are reported together with the POMs by {@link #report}.
	 */
	private void logOutcome(FileReplacer.Outcome outcome, String name, Charset charset) {
		switch (outcome.getStatus()) {
			case NO_MATCH:
				log.info("[no match] " + name);
				break;
			case NO_RULE:
				log.info("[no rule] " + name + " (no replacement rule applies to this file)");
				break;
			case UNCHANGED:
				log.info("[unchanged] " + name);
				break;
			case SKIPPED_BINARY:
				log.info(FileScanner.SKIPPED + name + " (binary)");
				break;
			case SKIPPED_UNDECODABLE:
				log.warn(FileScanner.SKIPPED + name + " (not valid " + charset + ")");
				break;
			default:
				break;
		}
	}

	private void report(BumpRequest request, Path baseDir, List<FileChange> changes) {
		String verb = request.isDryRun() ? "[would update] " : "[update] ";
		for (FileChange change : changes) {
			log.info(verb + FileSelector.display(baseDir, change.getPath()) + " (" + change.getReplacements()
				+ " replacement(s), " + change.getDescription() + ")");
		}
	}

	/**
	 * @return {@code true} when the changes were written, {@code false} in dry-run mode
	 */
	private boolean write(BumpRequest request, List<FileChange> changes, Version newVersion, String summary)
		throws BumpException {
		ChangeApplier applier = ChangeApplier.builder()
			.writer(writer)
			.createBackup(request.isCreateBackup())
			.backupSuffix(request.getBackupSuffix())
			.overwriteBackups(request.isOverwriteBackups())
			.log(log)
			.build();
		if (request.isDryRun()) {
			applier.validate(changes);
			log.info("DRY RUN: no file was written. " + changes.size() + " file(s) would be changed; version would be "
				+ newVersion + ".");
			return false;
		}
		applier.apply(changes);
		log.info(summary + ": " + changes.size() + " file(s) changed.");
		return true;
	}
}
