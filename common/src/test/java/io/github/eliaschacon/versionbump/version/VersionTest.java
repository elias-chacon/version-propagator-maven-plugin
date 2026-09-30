package io.github.eliaschacon.versionbump.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.eliaschacon.versionbump.BumpException;

class VersionTest {

	@ParameterizedTest(name = "{0} + {1} = {2}")
	@CsvSource({
		// three components
		"1.2.3, major, 2.0.0",
		"1.2.3, minor, 1.3.0",
		"1.2.3, patch, 1.2.4",
		"1.2.3, build, 1.2.3.1",
		"0.0.0, patch, 0.0.1",
		"1.9.9, minor, 1.10.0",
		// four components
		"1.2.3.4, build, 1.2.3.5",
		"1.2.3.4, patch, 1.2.4.0",
		"1.2.3.4, minor, 1.3.0.0",
		"1.2.3.4, major, 2.0.0.0",
		"1.2.3.0, build, 1.2.3.1",
		// snapshots are preserved
		"1.2.3-SNAPSHOT, patch, 1.2.4-SNAPSHOT",
		"1.2.3-SNAPSHOT, major, 2.0.0-SNAPSHOT",
		"1.2.3-SNAPSHOT, build, 1.2.3.1-SNAPSHOT",
		"1.2.3.4-SNAPSHOT, build, 1.2.3.5-SNAPSHOT",
		"1.2.3.4-SNAPSHOT, minor, 1.3.0.0-SNAPSHOT"
	})
	void bumpsAccordingToRules(String current, String part, String expected) throws BumpException {
		Version next = Version.parse(current).bump(VersionPart.parse(part), QualifierPolicy.FAIL);
		assertEquals(expected, next.toString());
	}

	@Test
	void buildIsNotASynonymOfPatch() throws BumpException {
		Version next = Version.parse("1.2.3").bump(VersionPart.BUILD, QualifierPolicy.FAIL);
		assertEquals(3, next.getPatch());
		assertEquals(Integer.valueOf(1), next.getBuild());
	}

	@Test
	void parsesComponents() throws BumpException {
		Version v = Version.parse("10.20.30.40-RC1-SNAPSHOT");
		assertEquals(10, v.getMajor());
		assertEquals(20, v.getMinor());
		assertEquals(30, v.getPatch());
		assertEquals(Integer.valueOf(40), v.getBuild());
		assertEquals("RC1", v.getQualifier());
		assertTrue(v.isSnapshot());
		assertEquals("10.20.30.40-RC1-SNAPSHOT", v.toString());
	}

	@Test
	void threeComponentVersionHasNoBuild() throws BumpException {
		Version v = Version.parse("1.2.3");
		assertNull(v.getBuild());
		assertNull(v.getQualifier());
		assertFalse(v.isSnapshot());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"", " ", "1", "1.0", "1.0-SNAPSHOT", "1.2.3.4.5", "01.2.3", "1.02.3", "1.2.03", "1.2.3.04",
		"a.b.c", "1.2.x", "1.2.3.RELEASE", "v1.2.3", "1.2.3-", "1.2.3--SNAPSHOT", "1.2.3-SNAPSHOT-SNAPSHOT",
		"1.2.3-snapshot", "1.2.3-RC 1", "1.2.3_4", "99999999999.0.0", "${revision}", "-1.2.3",
		"1.2.3-RC..1", "1.2.3-RC-", "1.2.3-RC.", "1.2.3-a.-b", "1.2.3-a--b", "1.2.3-.RC"
	})
	void rejectsInvalidVersions(String value) {
		BumpException e = assertThrows(BumpException.class, () -> Version.parse(value));
		assertTrue(e.getMessage().contains("'" + value + "'"), e.getMessage());
		assertTrue(e.getMessage().contains("MAJOR.MINOR.PATCH"), e.getMessage());
	}

	@Test
	void rejectsNull() {
		assertThrows(BumpException.class, () -> Version.parse(null));
	}

	@Test
	void rejectsQualifierByDefault() throws BumpException {
		Version v = Version.parse("1.2.3-RC1");
		BumpException e = assertThrows(BumpException.class, () -> v.bump(VersionPart.PATCH, QualifierPolicy.FAIL));
		assertTrue(e.getMessage().contains("1.2.3-RC1"));
		assertTrue(e.getMessage().contains("bump.qualifierPolicy"));
	}

	@ParameterizedTest(name = "{0} + {1} ({2}) = {3}")
	@CsvSource({
		"1.2.3-RC1, patch, preserve, 1.2.4-RC1",
		"1.2.3-RC1, patch, remove, 1.2.4",
		"1.2.3-RC1-SNAPSHOT, minor, preserve, 1.3.0-RC1-SNAPSHOT",
		"1.2.3-RC1-SNAPSHOT, minor, remove, 1.3.0-SNAPSHOT",
		"1.2.3.4-beta.2, build, preserve, 1.2.3.5-beta.2"
	})
	void appliesQualifierPolicy(String current, String part, String policy, String expected) throws BumpException {
		Version next = Version.parse(current).bump(VersionPart.parse(part), QualifierPolicy.parse(policy));
		assertEquals(expected, next.toString());
	}

	@Test
	void snapshotIsNotAffectedByQualifierPolicy() throws BumpException {
		assertEquals("1.2.4-SNAPSHOT",
			Version.parse("1.2.3-SNAPSHOT").bump(VersionPart.PATCH, QualifierPolicy.REMOVE).toString());
	}

	@Test
	void rejectsOverflow() throws BumpException {
		Version v = Version.parse("1.2." + Integer.MAX_VALUE);
		assertThrows(BumpException.class, () -> v.bump(VersionPart.PATCH, QualifierPolicy.FAIL));
	}

	@ParameterizedTest
	@CsvSource({"MAJOR, MAJOR", "Minor, MINOR", "' patch ', PATCH", "build, BUILD"})
	void parsesPartsCaseInsensitively(String value, VersionPart expected) throws BumpException {
		assertEquals(expected, VersionPart.parse(value));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "micro", "revision"})
	void rejectsUnknownParts(String value) {
		BumpException e = assertThrows(BumpException.class, () -> VersionPart.parse(value));
		assertTrue(e.getMessage().contains("major, minor, patch, build") || e.getMessage().contains("Missing"));
	}

	@Test
	void rejectsUnknownQualifierPolicy() {
		assertThrows(BumpException.class, () -> QualifierPolicy.parse("keep"));
	}

	@Test
	void equalityIsValueBased() throws BumpException {
		assertEquals(Version.parse("1.2.3-SNAPSHOT"), Version.parse("1.2.3-SNAPSHOT"));
		assertEquals(Version.parse("1.2.3").hashCode(), Version.parse("1.2.3").hashCode());
	}
}
