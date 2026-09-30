package io.github.eliaschacon.versionbump.io;

import java.nio.file.Path;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.ToString;

/**
 * A planned modification of one file: the exact original bytes (used for rollback and
 * concurrent-modification checks) and the new bytes to write.
 *
 * <p>The byte arrays are copied on the way in and out, so a change can never be altered after planning.</p>
 */
@Data
public final class FileChange {

	private final Path path;
	@ToString.Exclude
	private final byte[] originalBytes;
	@ToString.Exclude
	private final byte[] newBytes;
	private final int replacements;
	private final String description;

	@Builder
	private FileChange(@NonNull Path path, @NonNull byte[] originalBytes, @NonNull byte[] newBytes, int replacements,
					   String description) {
		this.path = path;
		this.originalBytes = originalBytes.clone();
		this.newBytes = newBytes.clone();
		this.replacements = replacements;
		this.description = description;
	}

	public byte[] getOriginalBytes() {
		return originalBytes.clone();
	}

	public byte[] getNewBytes() {
		return newBytes.clone();
	}
}
