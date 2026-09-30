package io.github.eliaschacon.versionbump.pom;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.DefaultHandler;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.io.TextFiles;
import lombok.RequiredArgsConstructor;

/**
 * Parses a POM with the JDK SAX parser (JAXP, Xerces bundled with the JDK). The parser alone decides
 * well-formedness, the encoding and the element values; its {@link Locator} gives the line and column of
 * every start and end tag, which are turned into character offsets so that an edit replaces exactly the
 * value of an element and leaves every other byte of the file untouched.
 *
 * <p>Hardened against XXE: external DTDs, schemas and entities are never loaded.</p>
 */
final class PomParser {

	private static final String ROOT = "project";
	private static final char PATH_SEPARATOR = '/';

	private PomParser() {
	}

	/**
	 * Two SAX passes: the first reads the bytes (well-formedness, encoding detection from BOM and XML
	 * declaration); the second reads the decoded text to take positions. The second pass gets the text with
	 * the XML end-of-line normalisation (lone CR to LF, spec section 2.11) already applied, because the JDK
	 * parser miscounts columns after a lone CR. The replacement keeps the length, so offsets stay valid for
	 * the original text.
	 *
	 * @param bytes  raw POM content
	 * @param source file name used in error messages
	 */
	static PomDocument parse(byte[] bytes, String source) throws BumpException {
		Handler probe = run(new InputSource(new ByteArrayInputStream(bytes)), source);
		if (!ROOT.equals(probe.rootName)) {
			throw new BumpException("Malformed POM " + source + ": root element is <" + probe.rootName
				+ ">, expected <project>.");
		}
		Charset charset = charset(probe.encoding, bytes, source);
		String content;
		try {
			content = TextFiles.decode(bytes, charset);
		} catch (CharacterCodingException e) {
			throw new BumpException("Cannot decode POM " + source + " using " + charset + ".", e);
		}
		int shift = !content.isEmpty() && content.charAt(0) == '\uFEFF' ? 1 : 0;
		String text = withLineFeeds(content.substring(shift));
		Handler located = run(new InputSource(new StringReader(text)), source);
		return PomDocument.of(content, charset, locate(content, shift, new LineIndex(text), located.found, source));
	}

	private static Handler run(InputSource input, String source) throws BumpException {
		Handler handler = new Handler(source);
		try {
			newParser().parse(input, handler);
		} catch (SAXParseException e) {
			throw new BumpException("Malformed POM " + source + " (line " + e.getLineNumber() + ", column "
				+ e.getColumnNumber() + "): " + e.getMessage(), e);
		} catch (SAXException | IOException e) {
			// Domain errors raised by the handler travel inside a SAXException.
			if (e.getCause() instanceof BumpException) {
				throw (BumpException) e.getCause();
			}
			throw new BumpException("Cannot parse POM " + source + ": " + e.getMessage(), e);
		}
		return handler;
	}

	/**
	 * XML end-of-line normalisation for lone CRs only: same length, CRLF pairs are kept.
	 */
	static String withLineFeeds(String text) {
		char[] chars = text.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			if (chars[i] == '\r' && (i + 1 == chars.length || chars[i + 1] != '\n')) {
				chars[i] = '\n';
			}
		}
		return new String(chars);
	}

	private static SAXParser newParser() throws BumpException {
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setXIncludeAware(false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			SAXParser parser = factory.newSAXParser();
			// XXE protection: external DTDs and external entities cannot be resolved.
			parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			return parser;
		} catch (ParserConfigurationException | SAXException e) {
			throw new BumpException("The JDK XML parser does not support the secure configuration: " + e, e);
		}
	}

	/**
	 * Encoding reported by the parser. For an unmarked {@code UTF-16}/{@code UTF-32} name the byte order is
	 * taken from the BOM, so that re-encoding writes the same bytes (Java's plain "UTF-16" encoder would
	 * always write a big-endian BOM).
	 */
	static Charset charset(String parserEncoding, byte[] bytes, String source) throws BumpException {
		String name = parserEncoding == null ? StandardCharsets.UTF_8.name() : parserEncoding;
		String upper = name.toUpperCase(Locale.ROOT);
		if (("UTF-16".equals(upper) || "UTF-32".equals(upper)) && bytes.length >= 2) {
			boolean littleEndian = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE;
			name = upper + (littleEndian ? "LE" : "BE");
		}
		try {
			return Charset.forName(name);
		} catch (IllegalArgumentException e) {
			throw new BumpException("Unsupported encoding '" + name + "' in POM " + source + ".", e);
		}
	}

	/**
	 * Converts the parser positions into character ranges of {@code content} and checks them.
	 *
	 * @param shift number of leading characters (BOM) that were not given to the parser
	 */
	private static Map<String, PomDocument.TextRange> locate(String content, int shift, LineIndex lines,
															 Map<String, Located> found, String source)
		throws BumpException {
		Map<String, PomDocument.TextRange> ranges = new HashMap<>();
		for (Map.Entry<String, Located> entry : found.entrySet()) {
			ranges.put(entry.getKey(), entry.getValue().toRange(content, shift, lines, entry.getKey(), source));
		}
		return Collections.unmodifiableMap(ranges);
	}

	/**
	 * Parser positions and value of one tracked element.
	 */
	@RequiredArgsConstructor
	private static final class Located {
		final int startLine;
		final int startColumn;
		final int endLine;
		final int endColumn;
		final String value;

		PomDocument.TextRange toRange(String content, int shift, LineIndex lines, String element, String source)
			throws BumpException {
			String tag = "<" + element.replace(PATH_SEPARATOR, '>') + ">";
			if (startLine == endLine && startColumn == endColumn) {
				throw new BumpException("Cannot safely edit " + tag + " in " + source + ": the element is empty.");
			}
			int contentStart = shift + lines.offset(startLine, startColumn);
			int endTagEnd = shift + lines.offset(endLine, endColumn);
			// The locator reports the end of the end tag; its start is the last "</" before that point.
			int contentEnd = content.lastIndexOf("</", endTagEnd - 1);
			String expected = PomDocument.stripXmlSpace(value);
			int start = contentStart;
			int end = Math.max(contentStart, contentEnd);
			while (start < end && PomDocument.isXmlSpace(content.charAt(start))) {
				start++;
			}
			while (end > start && PomDocument.isXmlSpace(content.charAt(end - 1))) {
				end--;
			}
			String raw = content.substring(start, end);
			if (contentEnd < contentStart || !raw.equals(expected)) {
				throw new BumpException("Cannot safely edit " + tag + " in " + source + ": its text is '" + raw
					+ "' but the XML value is '" + expected + "'. Write the value as plain text, without"
					+ " comments, CDATA, entities or character references.");
			}
			return new PomDocument.TextRange(start, end, expected);
		}
	}

	/**
	 * Start offset of every line of a text whose line breaks are LF or CRLF (as given to the parser).
	 */
	private static final class LineIndex {
		private final List<Integer> starts = new ArrayList<>();

		LineIndex(String text) {
			starts.add(0);
			for (int i = 0; i < text.length(); i++) {
				if (text.charAt(i) == '\n') {
					starts.add(i + 1);
				}
			}
		}

		int offset(int line, int column) {
			return starts.get(line - 1) + column - 1;
		}
	}

	/**
	 * SAX callbacks: element paths, positions and text of the tracked elements.
	 */
	private static final class Handler extends DefaultHandler {
		private final String source;
		private final Deque<String> paths = new ArrayDeque<>();
		private final Map<String, Located> found = new HashMap<>();
		private Locator locator;
		private String rootName;
		private String encoding;
		private String trackedPath;
		private int trackedLine;
		private int trackedColumn;
		private StringBuilder text;

		Handler(String source) {
			this.source = source;
		}

		private static SAXException fail(String message) {
			return new SAXException(new BumpException(message));
		}

		@Override
		public void setDocumentLocator(Locator locator) {
			this.locator = locator;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
			throws SAXException {
			if (rootName == null) {
				rootName = localName;
				// The XML declaration has been read by now, so the encoding is final.
				encoding = locator instanceof Locator2 ? ((Locator2) locator).getEncoding() : null;
			}
			if (trackedPath != null) {
				throw fail("Cannot safely edit <" + trackedPath.replace(PATH_SEPARATOR, '>') + "> in " + source
					+ ": it contains child elements.");
			}
			String path = paths.isEmpty() ? localName : paths.peek() + PATH_SEPARATOR + localName;
			paths.push(path);
			if (PomDocument.TRACKED.contains(path)) {
				if (found.containsKey(path)) {
					throw fail("Malformed POM " + source + ": duplicated element <" + path + ">.");
				}
				trackedPath = path;
				trackedLine = locator.getLineNumber();
				trackedColumn = locator.getColumnNumber();
				text = new StringBuilder();
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			if (trackedPath != null) {
				text.append(ch, start, length);
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) {
			String path = paths.pop();
			if (path.equals(trackedPath)) {
				found.put(path, new Located(trackedLine, trackedColumn, locator.getLineNumber(),
					locator.getColumnNumber(), text.toString()));
				trackedPath = null;
				text = null;
			}
		}

		/**
		 * Warnings and recoverable errors are treated as fatal: the POM must be clean XML.
		 */
		@Override
		public void warning(SAXParseException e) throws SAXException {
			throw e;
		}

		@Override
		public void error(SAXParseException e) throws SAXException {
			throw e;
		}
	}
}
