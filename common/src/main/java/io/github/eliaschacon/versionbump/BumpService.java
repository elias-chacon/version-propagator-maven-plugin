package io.github.eliaschacon.versionbump;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import io.github.eliaschacon.versionbump.version.QualifierPolicy;
import io.github.eliaschacon.versionbump.version.Version;
import io.github.eliaschacon.versionbump.version.VersionPart;
import lombok.RequiredArgsConstructor;

/**
 * Orchestrates a bump: validate configuration, compute the new version, plan every change
 * (POMs and additional files), then either report it (dry-run) or write it in one transaction.
 * Nothing is written before the whole plan has been computed and validated.
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

	public BumpResult execute(BumpRequest request) throws BumpException {
		VersionPart part = VersionPart.parse(request.getPart());
		QualifierPolicy policy = QualifierPolicy.parse(request.getQualifierPolicy());
		Charset charset = charset(request.getEncoding());
		if (!request.isUpdatePom() && !request.isUpdateFiles()) {
			throw new BumpException("Nothing to do: both bump.updatePom and bump.updateFiles are false.");
		}
		Path baseDir = FileScanner.normalize(request.getBaseDir());

		PomFile rootPom = request.isUpdatePom() ? PomFile.load(request.getRootPom()) : null;
		Version oldVersion = Version.parse(currentVersion(request, rootPom));
		Version newVersion = oldVersion.bump(part, policy);
		log.info("Bumping " + part + ": " + oldVersion + " -> " + newVersion + (request.isDryRun() ? " (dry run)" : ""));

		List<FileChange> changes = new ArrayList<>(planPoms(request, rootPom, oldVersion, newVersion));
		List<FileReplacer.Outcome> outcomes = request.isUpdateFiles()
			? planFiles(request, baseDir, charset, new VersionTokens(oldVersion, newVersion))
			: Collections.<FileReplacer.Outcome>emptyList();
		for (FileReplacer.Outcome outcome : outcomes) {
			if (outcome.getChange() != null) {
				changes.add(outcome.getChange());
			}
		}
		report(request, baseDir, changes);
		boolean written = write(request, changes, newVersion);

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

	private List<FileReplacer.Outcome> planFiles(BumpRequest request, Path baseDir, Charset charset,
												 VersionTokens tokens) throws BumpException {
		List<ReplacementRule> rules = request.getReplacements().isEmpty()
			? Collections.singletonList(ReplacementRule.defaultRule()) : request.getReplacements();
		List<ReplacementRule.Compiled> compiled = new ArrayList<>();
		for (ReplacementRule rule : rules) {
			compiled.add(rule.compile(tokens, baseDir));
		}
		SortedSet<Path> candidates = new FileSelector(log, baseDir, request).select(rules);
		FileReplacer replacer = new FileReplacer(compiled, charset, tokens.oldVersion() + " -> " + tokens.newVersion());

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
			throw new BumpException("No occurrence of version " + tokens.oldVersion() + " found in the "
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
	private boolean write(BumpRequest request, List<FileChange> changes, Version newVersion) throws BumpException {
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
		log.info("Version bumped to " + newVersion + ": " + changes.size() + " file(s) changed.");
		return true;
	}
}
