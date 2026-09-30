package io.github.eliaschacon.versionbump.pom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.io.FileChange;

/**
 * Loading a POM combines the JDK DOM parser (validation, encoding, values) with the format-preserving locator.
 */
class PomFileTest {

	@TempDir
	Path dir;

	private static FileChange bump(PomFile pom, String oldVersion, String newVersion) throws BumpException {
		return new PomVersionPlanner(new SystemStreamLog())
			.plan(pom, Collections.<PomFile>emptyList(), oldVersion, newVersion, true).get(0);
	}

	/**
	 * UTF-16 little endian with its byte order mark, as written by some Windows editors.
	 */
	private static byte[] utf16LeWithBom(String text) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
		bytes.write(text.getBytes(StandardCharsets.UTF_16LE));
		return bytes.toByteArray();
	}

	private Path write(byte[] content) throws IOException {
		Path file = dir.resolve("pom.xml");
		Files.write(file, content);
		return file;
	}

	private Path write(String content) throws IOException {
		return write(content.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void blocksExternalEntities() throws Exception {
		Path secret = dir.resolve("secret.txt");
		Files.write(secret, "TOP-SECRET".getBytes(StandardCharsets.UTF_8));
		Path pom = write("<?xml version=\"1.0\"?>\n<!DOCTYPE project [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>\n"
			+ "<project><groupId>g</groupId><artifactId>a</artifactId><version>&xxe;</version></project>");

		BumpException e = assertThrows(BumpException.class, () -> PomFile.load(pom));

		assertFalse(e.getMessage().contains("TOP-SECRET"), e.getMessage());
	}

	@Test
	void reportsMalformedXmlWithPosition() throws Exception {
		Path pom = write("<project>\n  <version>1.0.0</versio>\n</project>");
		BumpException e = assertThrows(BumpException.class, () -> PomFile.load(pom));
		assertTrue(e.getMessage().contains("line 2"), e.getMessage());
	}

	@Test
	void rejectsValuesThatDependOnXmlEscapes() throws Exception {
		Path pom = write("<project><groupId>g</groupId><artifactId>a</artifactId><version>1.0&#46;0</version></project>");
		BumpException e = assertThrows(BumpException.class, () -> PomFile.load(pom));
		assertTrue(e.getMessage().contains("Cannot safely edit <project>version>"), e.getMessage());
		assertTrue(e.getMessage().contains("'1.0.0'"), e.getMessage());
	}

	@Test
	void acceptsEntitiesOutsideTheEditedElements() throws Exception {
		Path pom = write("<?xml version=\"1.0\"?>\n<!DOCTYPE project [<!ENTITY org \"ACME &amp; Co\">]>\n"
			+ "<project><name>&org;</name><groupId>g</groupId><artifactId>a</artifactId>"
			+ "<version>1.0.0</version></project>");
		assertEquals("1.0.0", PomFile.load(pom).getDocument().value(PomDocument.PROJECT_VERSION));
	}

	@Test
	void acceptsNamespacePrefixes() throws Exception {
		Path pom = write("<pom:project xmlns:pom=\"http://maven.apache.org/POM/4.0.0\"><pom:groupId>g</pom:groupId>"
			+ "<pom:artifactId>a</pom:artifactId><pom:version>1.0.0</pom:version></pom:project>");
		FileChange change = bump(PomFile.load(pom), "1.0.0", "1.1.0");
		assertTrue(new String(change.getNewBytes(), StandardCharsets.UTF_8).contains("<pom:version>1.1.0</pom:version>"));
	}

	@Test
	void preservesCrlfLineEndingsAndLayout() throws Exception {
		String content = "<?xml version='1.0' encoding='UTF-8'?>\r\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\r\n"
			+ "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n"
			+ "  <groupId>g</groupId>\r\n  <artifactId>a</artifactId>\r\n  <version>1.0.0</version>\r\n"
			+ "  <description>&#169; ACME</description>\r\n</project>\r\n";
		FileChange change = bump(PomFile.load(write(content)), "1.0.0", "2.0.0");
		assertEquals(content.replace("<version>1.0.0</version>", "<version>2.0.0</version>"),
			new String(change.getNewBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void preservesUtf8Bom() throws Exception {
		String content = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?><project><groupId>g</groupId>"
			+ "<artifactId>a</artifactId><version>1.0.0</version><name>Ação</name></project>";
		PomFile pom = PomFile.load(write(content));
		FileChange change = bump(pom, "1.0.0", "1.0.1");
		assertEquals(StandardCharsets.UTF_8, pom.getCharset());
		assertArrayEquals(content.replace("1.0.0", "1.0.1").getBytes(StandardCharsets.UTF_8), change.getNewBytes());
	}

	@Test
	void detectsUtf16FromTheByteOrderMark() throws Exception {
		String content = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><project><groupId>g</groupId>"
			+ "<artifactId>a</artifactId><version>1.0.0</version><name>Ação</name></project>";
		PomFile pom = PomFile.load(write(utf16LeWithBom(content)));

		FileChange change = bump(pom, "1.0.0", "3.0.0");

		assertArrayEquals(utf16LeWithBom(content.replace("1.0.0", "3.0.0")), change.getNewBytes(),
			"charset=" + pom.getCharset());
	}

	@Test
	void verifyEditAcceptsOnlyTheExpectedChange() throws Exception {
		String content = "<project><groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version></project>";
		PomFile pom = PomFile.load(write(content));
		byte[] good = content.replace("1.0.0", "1.1.0").getBytes(StandardCharsets.UTF_8);
		byte[] groupChanged = content.replace("1.0.0", "1.1.0").replace(">g<", ">x<").getBytes(StandardCharsets.UTF_8);
		byte[] broken = content.replace("</project>", "").getBytes(StandardCharsets.UTF_8);

		assertDoesNotThrow(() -> pom.verifyEdit(good, Collections.singleton(PomDocument.PROJECT_VERSION), "1.1.0"));
		BumpException e = assertThrows(BumpException.class,
			() -> pom.verifyEdit(groupChanged, Collections.singleton(PomDocument.PROJECT_VERSION), "1.1.0"));
		assertTrue(e.getMessage().contains("Safety check failed"), e.getMessage());
		assertThrows(BumpException.class,
			() -> pom.verifyEdit(broken, Collections.singleton(PomDocument.PROJECT_VERSION), "1.1.0"));
	}

	@Test
	void usesTheParserEncodingAndTheByteOrderFromTheBom() throws BumpException {
		byte[] le = {(byte) 0xFF, (byte) 0xFE, '<', 0};
		byte[] be = {(byte) 0xFE, (byte) 0xFF, 0, '<'};
		assertEquals(StandardCharsets.ISO_8859_1, PomParser.charset("ISO-8859-1", new byte[0], "POM"));
		assertEquals(StandardCharsets.UTF_16LE, PomParser.charset("UTF-16", le, "POM"));
		assertEquals(StandardCharsets.UTF_16BE, PomParser.charset("utf-16", be, "POM"));
		assertEquals(StandardCharsets.UTF_8, PomParser.charset(null, new byte[0], "POM"));
		assertThrows(BumpException.class, () -> PomParser.charset("NOPE-42", new byte[0], "POM"));
	}

	@Test
	void rejectsNonPomXml() throws Exception {
		Path pom = write("<settings><version>1.0.0</version></settings>");
		BumpException e = assertThrows(BumpException.class, () -> PomFile.load(pom));
		assertTrue(e.getMessage().contains("expected <project>"), e.getMessage());
	}
}
