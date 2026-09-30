package io.github.eliaschacon.versionbump;

import java.nio.file.Path;
import java.util.List;

import io.github.eliaschacon.versionbump.files.FileReplacer;
import io.github.eliaschacon.versionbump.io.FileChange;
import io.github.eliaschacon.versionbump.version.Version;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

/**
 * Outcome of a bump: versions, planned changes and whether they were written.
 */
@Data
@Builder
public final class BumpResult {

	private final Version oldVersion;
	private final Version newVersion;
	/**
	 * All planned changes (POMs first, then additional files).
	 */
	@Singular
	private final List<FileChange> changes;
	/**
	 * Per-file result of the additional file replacement (empty when {@code updateFiles=false}).
	 */
	@Singular
	private final List<FileReplacer.Outcome> fileOutcomes;
	/**
	 * {@code false} in dry-run mode.
	 */
	private final boolean written;

	public boolean changes(Path path) {
		Path wanted = path.toAbsolutePath().normalize();
		for (FileChange change : changes) {
			if (change.getPath().toAbsolutePath().normalize().equals(wanted)) {
				return true;
			}
		}
		return false;
	}
}
