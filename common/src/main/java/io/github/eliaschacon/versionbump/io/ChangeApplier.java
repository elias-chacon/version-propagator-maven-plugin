package io.github.eliaschacon.versionbump.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.Builder;
import lombok.NonNull;

/**
 * Writes planned {@link FileChange}s.
 *
 * <p>Everything is validated before the first write (files unchanged since planning, writable, backups
 * not already present). Backups are written first, then every file atomically. If a write fails, files
 * already written are restored to their original bytes, so the project is never left half-bumped.</p>
 */
@Builder
public class ChangeApplier {

	public static final String DEFAULT_BACKUP_SUFFIX = ".bak";

	@NonNull
	private final AtomicFileWriter writer;
	private final boolean createBackup;
	@Builder.Default
	private final String backupSuffix = DEFAULT_BACKUP_SUFFIX;
	private final boolean overwriteBackups;
	@NonNull
	private final Log log;

	/**
	 * The file must be a writable regular file whose content did not change since planning.
	 */
	private static void validateTarget(Path path, byte[] plannedOriginal) throws BumpException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new BumpException("Not a regular file: " + path);
		}
		if (!Files.isWritable(path)) {
			throw new BumpException("File is not writable: " + path);
		}
		byte[] current;
		try {
			current = Files.readAllBytes(path);
		} catch (IOException e) {
			throw new BumpException("Cannot read " + path + ": " + e, e);
		}
		if (!Arrays.equals(current, plannedOriginal)) {
			throw new BumpException("File changed since the bump was planned: " + path + ". Run the goal again.");
		}
	}

	public Path backupPath(Path file) {
		return file.resolveSibling(file.getFileName() + backupSuffix);
	}

	/**
	 * Fails if any change cannot be written safely. Never modifies the file system.
	 */
	public void validate(List<FileChange> changes) throws BumpException {
		validateBackupSuffix();
		Set<Path> targets = new HashSet<>();
		for (FileChange change : changes) {
			Path path = change.getPath().toAbsolutePath().normalize();
			if (!targets.add(path)) {
				throw new BumpException("File planned twice: " + path + ". Check files/directories/includes.");
			}
		}
		for (FileChange change : changes) {
			Path path = change.getPath().toAbsolutePath().normalize();
			validateTarget(path, change.getOriginalBytes());
			if (createBackup) {
				validateBackup(backupPath(path), targets);
			}
		}
	}

	private void validateBackupSuffix() throws BumpException {
		if (createBackup && (backupSuffix == null || backupSuffix.trim().isEmpty()
			|| backupSuffix.contains("/") || backupSuffix.contains("\\"))) {
			throw new BumpException("Invalid backup suffix '" + backupSuffix + "'.");
		}
	}

	private void validateBackup(Path backup, Set<Path> targets) throws BumpException {
		if (targets.contains(backup)) {
			throw new BumpException("Backup " + backup + " would overwrite a file that is being bumped.");
		}
		if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && !overwriteBackups) {
			throw new BumpException("Backup already exists: " + backup
				+ ". Delete it or set bump.overwriteBackups=true.");
		}
	}

	public void apply(List<FileChange> changes) throws BumpException {
		validate(changes);
		if (createBackup) {
			for (FileChange change : changes) {
				Path backup = backupPath(change.getPath());
				try {
					if (Files.exists(backup)) {
						log.warn("Overwriting existing backup " + backup);
					}
					writer.write(backup, change.getOriginalBytes());
					log.info("[backup] " + backup);
				} catch (IOException e) {
					throw new BumpException("Cannot create backup " + backup + ": " + e + ". No file was modified.", e);
				}
			}
		}

		List<FileChange> written = new ArrayList<>();
		for (FileChange change : changes) {
			try {
				writer.write(change.getPath(), change.getNewBytes());
				written.add(change);
			} catch (IOException e) {
				int restored = rollback(written);
				throw new BumpException("Cannot write " + change.getPath() + ": " + e + ". Restored " + restored
					+ " of " + written.size() + " already written file(s) to their original content.", e);
			}
		}
	}

	private int rollback(List<FileChange> written) {
		int restored = 0;
		for (FileChange change : written) {
			try {
				writer.write(change.getPath(), change.getOriginalBytes());
				restored++;
			} catch (IOException e) {
				log.error("Could not restore " + change.getPath() + ": " + e
					+ (createBackup ? ". Recover it from " + backupPath(change.getPath()) : ""));
			}
		}
		return restored;
	}
}
