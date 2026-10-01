package io.github.eliaschacon.versionbump.version;

import java.util.Locale;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.Builder;
import lombok.Data;

/**
 * How the next version is computed from the current one. Two exclusive ways:
 * <ul>
 *     <li>{@code newVersion}: an explicit version in any accepted format (release, SNAPSHOT, release candidate...);</li>
 *     <li>{@code part} ({@code major}, {@code minor}, {@code patch}, {@code build} or {@code release}), optionally
 *     combined with {@code snapshot} (add or drop {@code -SNAPSHOT}) and {@code qualifier} (set or replace it, e.g.
 *     {@code RC1}; {@code none} or a blank value removes it).</li>
 * </ul>
 * Order with {@code part}: increment, then qualifier, then snapshot. The result must differ from the current version.
 */
@Data
@Builder
public final class VersionChange {

	/** Value of {@code qualifier} that removes the qualifier (Maven may turn an empty -D value into null). */
	public static final String NO_QUALIFIER = "none";

	private final String part;
	private final String newVersion;
	/** {@code null}: keep the current suffix. */
	private final Boolean snapshot;
	/** {@code null}: keep (or follow {@code qualifierPolicy}); blank or {@link #NO_QUALIFIER}: remove. */
	private final String qualifier;
	private final String qualifierPolicy;

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	public Version apply(Version current) throws BumpException {
		if (!isBlank(newVersion)) {
			if (!isBlank(part) || snapshot != null || qualifier != null) {
				throw new BumpException("bump.newVersion sets the whole version: it cannot be combined with bump.part,"
					+ " bump.snapshot or bump.qualifier.");
			}
			return requireChange(current, Version.parse(newVersion));
		}
		VersionPart versionPart = VersionPart.parse(part);
		if (versionPart == VersionPart.RELEASE && Boolean.TRUE.equals(snapshot)) {
			throw new BumpException("bump.part=release drops -SNAPSHOT: it cannot be combined with bump.snapshot=true.");
		}
		// A new qualifier replaces the current one, so the policy for keeping it does not apply.
		QualifierPolicy policy = qualifier != null ? QualifierPolicy.PRESERVE : QualifierPolicy.parse(qualifierPolicy);
		Version next = current.bump(versionPart, policy);
		if (qualifier != null) {
			next = next.withQualifier(NO_QUALIFIER.equals(qualifier.trim().toLowerCase(Locale.ROOT)) ? null : qualifier);
		}
		if (snapshot != null) {
			next = next.withSnapshot(snapshot);
		}
		return requireChange(current, next);
	}

	private static Version requireChange(Version current, Version next) throws BumpException {
		if (next.equals(current)) {
			throw new BumpException("The version would not change (" + current + "). Check bump.part, bump.newVersion,"
				+ " bump.snapshot and bump.qualifier.");
		}
		return next;
	}

	/**
	 * @return short description for logs, e.g. {@code patch}, {@code minor + RC1 + SNAPSHOT} or {@code set}
	 */
	public String describe() {
		if (!isBlank(newVersion)) {
			return "set";
		}
		StringBuilder sb = new StringBuilder(isBlank(part) ? "?" : part.trim().toLowerCase(Locale.ROOT));
		if (qualifier != null) {
			sb.append(" + qualifier ").append(isBlank(qualifier) ? NO_QUALIFIER : qualifier.trim());
		}
		if (snapshot != null) {
			sb.append(snapshot ? " + SNAPSHOT" : " - SNAPSHOT");
		}
		return sb.toString();
	}
}
