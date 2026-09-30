package io.github.eliaschacon.versionbump.pom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.github.eliaschacon.versionbump.BumpException;

class PomDocumentTest {

	static final String POM = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
		+ "<!-- <version>9.9.9</version> -->\n"
		+ "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
		+ "  <modelVersion>4.0.0</modelVersion>\n"
		+ "  <parent>\n"
		+ "    <groupId>org.parent</groupId>\n"
		+ "    <artifactId>parent</artifactId>\n"
		+ "    <version>5.0.0</version>\n"
		+ "    <relativePath/>\n"
		+ "  </parent>\n"
		+ "  <artifactId>demo</artifactId>\n"
		+ "  <version>  1.2.3  </version> <!-- keep me -->\n"
		+ "  <properties><x attr=\"a>b\">1.2.3</x></properties>\n"
		+ "  <dependencies>\n"
		+ "    <dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.2.3</version></dependency>\n"
		+ "  </dependencies>\n"
		+ "  <build><plugins><plugin><artifactId>p</artifactId><version>1.2.3</version></plugin></plugins></build>\n"
		+ "  <description><![CDATA[ <version>0</version> ]]></description>\n"
		+ "</project>\n";

	/**
	 * Replaces the project version and returns the new text: exercises the SAX positions end to end.
	 */
	private static String bump(String xml, String newVersion) throws BumpException {
		PomDocument doc = PomDocument.parse(xml);
		return doc.replace(Collections.singletonList(doc.get(PomDocument.PROJECT_VERSION)), newVersion);
	}

	@Test
	void findsProjectAndParentCoordinatesOnly() throws BumpException {
		PomDocument doc = PomDocument.parse(POM);
		assertEquals("1.2.3", doc.value(PomDocument.PROJECT_VERSION));
		assertEquals("demo", doc.artifactId());
		assertNull(doc.value(PomDocument.PROJECT_GROUP_ID));
		assertEquals("org.parent", doc.groupId());
		assertEquals("5.0.0", doc.value(PomDocument.PARENT_VERSION));
		assertEquals("org.parent:parent", doc.parentKey());
		assertEquals("org.parent:demo", doc.key());
	}

	@Test
	void replacesOnlyTheProjectVersionPreservingFormatting() throws BumpException {
		PomDocument doc = PomDocument.parse(POM);
		String updated = doc.replace(Collections.singletonList(doc.get(PomDocument.PROJECT_VERSION)), "1.2.4");
		assertEquals(POM.replace("<version>  1.2.3  </version>", "<version>  1.2.4  </version>"), updated);
	}

	@Test
	void detectsMissingVersion() throws BumpException {
		PomDocument doc = PomDocument.parse("<project><parent><groupId>g</groupId><artifactId>p</artifactId>"
			+ "<version>1.0.0</version></parent><artifactId>c</artifactId></project>");
		assertNull(doc.get(PomDocument.PROJECT_VERSION));
		assertTrue(doc.hasParent());
	}

	@Test
	void supportsDoctypeAndProcessingInstructions() throws BumpException {
		PomDocument doc = PomDocument.parse("<?xml version='1.0'?><!DOCTYPE project [<!ENTITY x \"y\">]>"
			+ "<?pi data?><project><version>1.0.0</version></project>");
		assertEquals("1.0.0", doc.value(PomDocument.PROJECT_VERSION));
		assertFalse(doc.hasParent());
	}

	@Test
	void rejectsNonPomRoot() {
		assertThrows(BumpException.class, () -> PomDocument.parse("<settings><version>1</version></settings>"));
	}

	@Test
	void rejectsUnbalancedXml() {
		assertThrows(BumpException.class, () -> PomDocument.parse("<project><version>1.0.0</project>"));
		assertThrows(BumpException.class, () -> PomDocument.parse("<project><version>1.0.0</version>"));
		assertThrows(BumpException.class, () -> PomDocument.parse("<project><!-- open </project>"));
	}

	@Test
	void rejectsVersionWithComplexContent() {
		BumpException e = assertThrows(BumpException.class,
			() -> PomDocument.parse("<project><version><!-- c -->1.0.0</version></project>"));
		assertTrue(e.getMessage().contains("project>version"));
	}

	@Test
	void rejectsDuplicatedVersion() {
		assertThrows(BumpException.class,
			() -> PomDocument.parse("<project><version>1.0.0</version><version>1.0.1</version></project>"));
	}

	@Test
	void locatesVersionInAOneLinePomWithBom() throws BumpException {
		String xml = "\uFEFF<project><groupId>g</groupId><artifactId>a</artifactId><version>1.0.0</version></project>";
		assertEquals(xml.replace("1.0.0", "2.0.0"), bump(xml, "2.0.0"));
	}

	@Test
	void locatesVersionWithSpaceInEndTagAndCarriageReturnLines() throws BumpException {
		String xml = "<project>\r  <artifactId>a</artifactId>\r\r  <version>\t1.0.0 </version >\r</project>\r";
		assertEquals(xml.replace("\t1.0.0 <", "\t3.1.4 <"), bump(xml, "3.1.4"));
	}

	@Test
	void normalisesOnlyLoneCarriageReturnsKeepingTheLength() {
		assertEquals("a\nb\r\nc\n\nd\n", PomParser.withLineFeeds("a\rb\r\nc\r\rd\r"));
	}

	@Test
	void locatesParentVersionOnTheSameLineAsOtherElements() throws BumpException {
		String xml = "<project><parent><groupId>g</groupId><artifactId>p</artifactId><version>1.0.0</version></parent>"
			+ "<artifactId>c</artifactId><version>1.0.0</version></project>";
		PomDocument doc = PomDocument.parse(xml);
		String updated = doc.replace(java.util.Arrays.asList(doc.get(PomDocument.PARENT_VERSION),
			doc.get(PomDocument.PROJECT_VERSION)), "1.1.0");
		assertEquals(xml.replace("1.0.0", "1.1.0"), updated);
	}

	@Test
	void locatesVersionAfterSupplementaryCharactersAndMultiLineTags() throws BumpException {
		String xml = "<project name=\"\uD83D\uDE00 >\"\n         xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
			+ "  <description>\uD83D\uDE00 emoji</description><version>1.0.0</version>\n</project>";
		assertEquals(xml.replace("1.0.0", "1.0.1"), bump(xml, "1.0.1"));
	}

	@Test
	void rejectsEmptyAndStructuredVersions() {
		BumpException empty = assertThrows(BumpException.class,
			() -> PomDocument.parse("<project><version/></project>"));
		assertTrue(empty.getMessage().contains("empty"), empty.getMessage());
		BumpException child = assertThrows(BumpException.class,
			() -> PomDocument.parse("<project><version><x>1</x></version></project>"));
		assertTrue(child.getMessage().contains("child elements"), child.getMessage());
		BumpException cdata = assertThrows(BumpException.class,
			() -> PomDocument.parse("<project><version><![CDATA[1.0.0]]></version></project>"));
		assertTrue(cdata.getMessage().contains("'<![CDATA[1.0.0]]>'"), cdata.getMessage());
	}
}
