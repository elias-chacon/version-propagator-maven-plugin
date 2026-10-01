package io.github.eliaschacon.versionbump.pom;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.io.TextFiles;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * A POM loaded from disk: raw bytes (kept for rollback) and the {@link PomDocument} produced by the SAX
 * parser ({@link PomParser}). Identity is the file path.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class PomFile {

	@EqualsAndHashCode.Include
	private final Path path;
	@ToString.Exclude
	private final byte[] bytes;
	@ToString.Exclude
	private final PomDocument document;

	public static PomFile load(Path path) throws BumpException {
		byte[] bytes;
		try {
			bytes = TextFiles.read(path);
		} catch (IOException e) {
			throw new BumpException("Cannot read POM " + path + ": " + e.getMessage(), e);
		}
		return new PomFile(path, bytes, PomParser.parse(bytes, path.toString()));
	}

	/**
	 * Parses POM content that is not (or not only) on disk, e.g. a previous revision read from git or a
	 * {@code pom.xml.releaseBackup}. {@code path} identifies the content in messages.
	 */
	public static PomFile parse(Path path, byte[] bytes) throws BumpException {
		return new PomFile(path, bytes.clone(), PomParser.parse(bytes, path.toString()));
	}

	/**
	 * @return the project version: its own {@code <version>}, or the parent's when inherited ({@code null} if none)
	 */
	public String version() {
		String own = document.value(PomDocument.PROJECT_VERSION);
		return own != null ? own : document.value(PomDocument.PARENT_VERSION);
	}

	/**
	 * Defensive copy: the original bytes are needed intact for rollback.
	 */
	public byte[] getBytes() {
		return bytes.clone();
	}

	/**
	 * Encoding detected by the XML parser; used to write the file back.
	 */
	public Charset getCharset() {
		return document.getCharset();
	}

	/**
	 * Safety net run before anything is written: parses {@code newBytes} again with the XML parser and checks
	 * that exactly the {@code edited} elements now hold {@code newVersion}, every other tracked value is
	 * unchanged and the document is still well-formed.
	 */
	public void verifyEdit(byte[] newBytes, Collection<String> edited, String newVersion) throws BumpException {
		Map<String, String> expected = new HashMap<>();
		for (Map.Entry<String, String> entry : document.values().entrySet()) {
			expected.put(entry.getKey(), edited.contains(entry.getKey()) ? newVersion : entry.getValue());
		}
		Map<String, String> actual = PomParser.parse(newBytes, path.toString()).values();
		if (!expected.equals(actual)) {
			throw new BumpException("Safety check failed for " + path + ": after the edit the POM reads " + actual
				+ " instead of " + expected + ". No file was written.");
		}
	}
}
