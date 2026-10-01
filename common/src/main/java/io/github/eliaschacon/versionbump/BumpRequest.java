package io.github.eliaschacon.versionbump;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.eliaschacon.versionbump.files.ReplacementRule;
import io.github.eliaschacon.versionbump.io.ChangeApplier;
import lombok.Builder;
import lombok.Data;

/**
 * Everything {@link BumpService} needs, independent of the Maven API.
 * Build it with {@link #builder()}; unspecified values take the documented defaults.
 * List values must not be {@code null} (use an empty list).
 */
@Data
@Builder(toBuilder = true)
public final class BumpRequest {

	private Path baseDir;
	private Path rootPom;
	@Builder.Default
	private List<Path> modulePoms = new ArrayList<>();
	/**
	 * Effective version computed by Maven; {@code null} to use the literal version of the root POM.
	 */
	private String projectVersion;

	private String part;
	/** Explicit next version; exclusive with {@code part} (see {@code VersionChange}). */
	private String newVersion;
	/** {@code true}/{@code false} adds/drops {@code -SNAPSHOT} after {@code part}; {@code null} keeps it. */
	private Boolean snapshot;
	/** Sets or replaces the qualifier after {@code part} ({@code none} removes it); {@code null} keeps it. */
	private String qualifier;
	@Builder.Default
	private String qualifierPolicy = "fail";
	private boolean dryRun;
	@Builder.Default
	private boolean updatePom = true;
	@Builder.Default
	private boolean updateModules = true;
	private boolean updateFiles;
	@Builder.Default
	private List<String> includes = new ArrayList<>();
	@Builder.Default
	private List<String> excludes = new ArrayList<>();
	@Builder.Default
	private boolean useDefaultExcludes = true;
	@Builder.Default
	private List<Path> directories = new ArrayList<>();
	@Builder.Default
	private List<Path> files = new ArrayList<>();
	@Builder.Default
	private List<ReplacementRule> replacements = new ArrayList<>();
	@Builder.Default
	private String encoding = "UTF-8";
	private boolean createBackup;
	@Builder.Default
	private String backupSuffix = ChangeApplier.DEFAULT_BACKUP_SUFFIX;
	private boolean overwriteBackups;
	private boolean failOnNoMatch;
	private boolean followSymlinks;
}
