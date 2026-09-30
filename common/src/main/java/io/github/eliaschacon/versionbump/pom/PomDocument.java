package io.github.eliaschacon.versionbump.pom;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.eliaschacon.versionbump.BumpException;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

/**
 * A POM as seen by the plugin: its decoded text, the charset used by the XML parser and the location and
 * value of the project/parent coordinates. Built by {@link PomParser} (JDK SAX parser); edits replace only
 * the recorded character ranges, so every other character of the file is preserved.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class PomDocument {

	public static final String PROJECT_GROUP_ID = "project/groupId";
	public static final String PROJECT_ARTIFACT_ID = "project/artifactId";
	public static final String PROJECT_VERSION = "project/version";
	public static final String PARENT_GROUP_ID = "project/parent/groupId";
	public static final String PARENT_ARTIFACT_ID = "project/parent/artifactId";
	public static final String PARENT_VERSION = "project/parent/version";

	/**
	 * Element paths read by the plugin.
	 */
	static final Set<String> TRACKED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		PROJECT_GROUP_ID, PROJECT_ARTIFACT_ID, PROJECT_VERSION, PARENT_GROUP_ID, PARENT_ARTIFACT_ID, PARENT_VERSION)));

	@ToString.Exclude
	private final String content;
	private final Charset charset;
	private final Map<String, TextRange> elements;

	static PomDocument of(String content, Charset charset, Map<String, TextRange> elements) {
		return new PomDocument(content, charset, elements);
	}

	/**
	 * Convenience for tests and in-memory content: parses UTF-8 text with {@link PomParser}.
	 */
	static PomDocument parse(String xml) throws BumpException {
		return PomParser.parse(xml.getBytes(StandardCharsets.UTF_8), "POM");
	}

	/**
	 * XML white space (XML 1.0 production S): space, tab, CR, LF.
	 */
	static boolean isXmlSpace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}

	/**
	 * Removes leading and trailing XML white space.
	 */
	static String stripXmlSpace(String text) {
		int start = 0;
		int end = text.length();
		while (start < end && isXmlSpace(text.charAt(start))) {
			start++;
		}
		while (end > start && isXmlSpace(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(start, end);
	}

	/**
	 * @return the element at the given path (see constants), or {@code null} when absent
	 */
	public TextRange get(String path) {
		return elements.get(path);
	}

	public String value(String path) {
		TextRange range = elements.get(path);
		return range == null ? null : range.getValue();
	}

	/**
	 * Values of all tracked elements present in the POM, keyed by element path.
	 */
	public Map<String, String> values() {
		Map<String, String> values = new HashMap<>();
		for (Map.Entry<String, TextRange> entry : elements.entrySet()) {
			values.put(entry.getKey(), entry.getValue().getValue());
		}
		return values;
	}

	public boolean hasParent() {
		return elements.containsKey(PARENT_ARTIFACT_ID);
	}

	/**
	 * Effective groupId: the project's own, or the parent's when inherited.
	 */
	public String groupId() {
		String own = value(PROJECT_GROUP_ID);
		return own != null ? own : value(PARENT_GROUP_ID);
	}

	public String artifactId() {
		return value(PROJECT_ARTIFACT_ID);
	}

	public String parentKey() {
		return hasParent() ? value(PARENT_GROUP_ID) + ":" + value(PARENT_ARTIFACT_ID) : null;
	}

	public String key() {
		return groupId() + ":" + artifactId();
	}

	/**
	 * Returns a copy of the document where each range is replaced by {@code replacement}.
	 * All other characters are left untouched.
	 */
	public String replace(List<TextRange> ranges, String replacement) {
		TextRange[] sorted = ranges.toArray(new TextRange[0]);
		Arrays.sort(sorted, (a, b) -> Integer.compare(b.start, a.start));
		StringBuilder sb = new StringBuilder(content);
		for (TextRange range : sorted) {
			sb.replace(range.start, range.end, replacement);
		}
		return sb.toString();
	}

	/**
	 * Text value of an element and the character range of that (trimmed) value in the document.
	 */
	@Data
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class TextRange {
		private final int start;
		private final int end;
		private final String value;
	}
}
