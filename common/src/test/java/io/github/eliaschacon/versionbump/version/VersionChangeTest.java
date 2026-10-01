package io.github.eliaschacon.versionbump.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.eliaschacon.versionbump.BumpException;

class VersionChangeTest {

	private static String apply(String current, VersionChange change) throws BumpException {
		return change.apply(Version.parse(current)).toString();
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
		"1.0.4, 1.2.0-SNAPSHOT",
		"1.2.0-SNAPSHOT, 1.2.0",
		"1.2.0, 1.2.0-RC1",
		"1.2.0-RC1, 2.0.0.1",
		"2.0.0, 1.0.0"
	})
	void setsAnExplicitVersion(String current, String next) throws BumpException {
		assertEquals(next, apply(current, VersionChange.builder().newVersion(next).build()));
	}

	@ParameterizedTest(name = "{0} + {1} snapshot={2} qualifier={3} = {4}")
	@CsvSource(nullValues = "null", value = {
		// release: drop -SNAPSHOT, keep numbers and qualifier
		"1.0.5-SNAPSHOT, release, null, null, 1.0.5",
		"1.1.0-RC1-SNAPSHOT, release, null, null, 1.1.0-RC1",
		"1.1.0-RC1-SNAPSHOT, release, null, none, 1.1.0",
		// increment + next development version
		"1.0.4, patch, true, null, 1.0.5-SNAPSHOT",
		"1.0.4, minor, true, null, 1.1.0-SNAPSHOT",
		"1.0.4.7, build, true, null, 1.0.4.8-SNAPSHOT",
		// increment and release in one step
		"1.0.4-SNAPSHOT, patch, false, null, 1.0.5",
		// release candidates
		"1.0.4, minor, null, RC1, 1.1.0-RC1",
		"1.1.0-RC1, patch, null, RC2, 1.1.1-RC2",
		"1.1.0-RC1-SNAPSHOT, release, null, RC2, 1.1.0-RC2",
		"1.0.4-SNAPSHOT, minor, null, beta.1, 1.1.0-beta.1-SNAPSHOT",
		"1.1.0-RC2, patch, null, '', 1.1.1",
		"1.1.0-RC2, patch, null, NONE, 1.1.1"
	})
	void combinesPartSnapshotAndQualifier(String current, String part, Boolean snapshot, String qualifier,
										  String expected) throws BumpException {
		VersionChange change = VersionChange.builder().part(part).snapshot(snapshot).qualifier(qualifier).build();
		assertEquals(expected, apply(current, change));
	}

	@Test
	void keepsTheQualifierPolicyWhenNoQualifierIsGiven() {
		VersionChange change = VersionChange.builder().part("patch").build();
		BumpException e = assertThrows(BumpException.class, () -> apply("1.1.0-RC1", change));
		assertTrue(e.getMessage().contains("bump.qualifierPolicy"), e.getMessage());
	}

	@Test
	void rejectsInvalidCombinations() {
		assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().newVersion("2.0.0").part("patch").build()));
		assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().newVersion("2.0.0").snapshot(true).build()));
		assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().newVersion("2.0.0").qualifier("RC1").build()));
		assertThrows(BumpException.class,
			() -> apply("1.0.0-SNAPSHOT", VersionChange.builder().part("release").snapshot(true).build()));
		BumpException missing = assertThrows(BumpException.class, () -> apply("1.0.0", VersionChange.builder().build()));
		assertTrue(missing.getMessage().contains("bump.newVersion"), missing.getMessage());
	}

	@Test
	void rejectsChangesThatChangeNothing() {
		BumpException e = assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().newVersion("1.0.0").build()));
		assertTrue(e.getMessage().contains("would not change"), e.getMessage());
	}

	@Test
	void rejectsReleasingAReleaseAndInvalidValues() {
		BumpException release = assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().part("release").build()));
		assertTrue(release.getMessage().contains("not a SNAPSHOT"), release.getMessage());
		assertThrows(BumpException.class, () -> apply("1.0.0", VersionChange.builder().newVersion("1.0").build()));
		assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().part("minor").qualifier("RC 1").build()));
		assertThrows(BumpException.class,
			() -> apply("1.0.0", VersionChange.builder().part("minor").qualifier("SNAPSHOT").build()));
	}

	@Test
	void describesTheChange() {
		assertEquals("set", VersionChange.builder().newVersion("1.0.0").build().describe());
		assertEquals("minor + qualifier RC1 + SNAPSHOT",
			VersionChange.builder().part("MINOR").qualifier("RC1").snapshot(true).build().describe());
		assertEquals("patch + qualifier none - SNAPSHOT",
			VersionChange.builder().part("patch").qualifier("").snapshot(false).build().describe());
	}
}
