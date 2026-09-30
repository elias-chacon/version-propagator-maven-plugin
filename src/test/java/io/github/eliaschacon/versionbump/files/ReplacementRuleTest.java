package io.github.eliaschacon.versionbump.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.version.Version;

class ReplacementRuleTest {

	private static VersionTokens tokens(String oldVersion, String newVersion) throws BumpException {
		return new VersionTokens(Version.parse(oldVersion), Version.parse(newVersion));
	}

	private static ReplacementRule.Result applyDefault(String oldV, String newV, String text) throws BumpException {
		return ReplacementRule.defaultRule().compile(tokens(oldV, newV)).apply(text);
	}

	@ParameterizedTest(name = "[{0}] -> [{1}]")
	@CsvSource(delimiter = '|', value = {
		"version: 1.2.3|version: 1.2.4",
		"1.2.3|1.2.4",
		"v1.2.3|v1.2.4",
		"app-1.2.3.jar|app-1.2.4.jar",
		"Release 1.2.3.|Release 1.2.4.",
		"\"1.2.3\"|\"1.2.4\"",
		"(1.2.3)|(1.2.4)",
		"1.2.3,1.2.3|1.2.4,1.2.4",
		"11.2.30|11.2.30",
		"11.2.3|11.2.3",
		"1.2.30|1.2.30",
		"0.1.2.3|0.1.2.3",
		"1.2.3.4|1.2.3.4",
		"1.2.3-SNAPSHOT|1.2.3-SNAPSHOT",
		"1.2.3-RC1|1.2.3-RC1",
		"1.2.3a|1.2.3a",
		"1x2x3|1x2x3",
		"1.2.3_4|1.2.3_4"
	})
	void defaultRuleReplacesOnlyTheExactVersion(String text, String expected) throws BumpException {
		assertEquals(expected, applyDefault("1.2.3", "1.2.4", text).getText());
	}

	@Test
	void defaultRuleHandlesSnapshotVersions() throws BumpException {
		ReplacementRule.Result r = applyDefault("1.2.3-SNAPSHOT", "1.2.4-SNAPSHOT",
			"a=1.2.3-SNAPSHOT\nb=1.2.3\nc=1.2.3-SNAPSHOT-x\n");
		assertEquals("a=1.2.4-SNAPSHOT\nb=1.2.3\nc=1.2.3-SNAPSHOT-x\n", r.getText());
		assertEquals(1, r.getCount());
	}

	@Test
	void customRuleWithCaptureGroupsAndComponentTokens() throws BumpException {
		ReplacementRule rule = new ReplacementRule("(appVersion\\s*=\\s*)${oldVersion}", "$1${newVersion}");
		assertEquals("appVersion = 2.0.0 # 1.2.3",
			rule.compile(tokens("1.2.3", "2.0.0")).apply("appVersion = 1.2.3 # 1.2.3").getText());

		ReplacementRule parts = new ReplacementRule("major: ${oldMajor}\\nminor: ${oldMinor}",
			"major: @newMajor@\nminor: @newMinor@");
		assertEquals("major: 2\nminor: 0",
			parts.compile(tokens("1.2.3", "2.0.0")).apply("major: 1\nminor: 2").getText());
	}

	@Test
	void buildTokensAreAvailableWhenPresent() throws BumpException {
		ReplacementRule rule = new ReplacementRule("build=${oldBuild}", "build=${newBuild}");
		assertEquals("build=5", rule.compile(tokens("1.2.3.4", "1.2.3.5")).apply("build=4").getText());
		assertEquals("build=1", new ReplacementRule("build=0", "build=${newBuild}")
			.compile(tokens("1.2.3", "1.2.3.1")).apply("build=0").getText());
	}

	@Test
	void missingBuildTokenFailsClearly() throws BumpException {
		ReplacementRule rule = new ReplacementRule("build=${oldBuild}", "x");
		VersionTokens tokens = tokens("1.2.3", "1.2.3.1");
		BumpException e = assertThrows(BumpException.class, () -> rule.compile(tokens));
		assertTrue(e.getMessage().contains("oldBuild"));
	}

	@Test
	void tokenValuesAreQuotedInRegex() throws BumpException {
		ReplacementRule rule = new ReplacementRule("${oldVersion}", "${newVersion}");
		assertEquals("1x2x3", rule.compile(tokens("1.2.3", "1.2.4")).apply("1x2x3").getText());
	}

	@Test
	void unknownTokensAreLeftAsIs() throws BumpException {
		ReplacementRule rule = new ReplacementRule("x@foo@", "y");
		assertEquals("y", rule.compile(tokens("1.2.3", "1.2.4")).apply("x@foo@").getText());
	}

	@Test
	void rejectsInvalidRules() throws BumpException {
		VersionTokens tokens = tokens("1.2.3", "1.2.4");
		ReplacementRule noReplace = new ReplacementRule("a", null);
		ReplacementRule badRegex = new ReplacementRule("(", "x");
		ReplacementRule matchesEmpty = new ReplacementRule("a?", "x");
		assertThrows(BumpException.class, () -> noReplace.compile(tokens));
		assertThrows(BumpException.class, () -> badRegex.compile(tokens));
		assertThrows(BumpException.class, () -> matchesEmpty.compile(tokens));

		ReplacementRule.Compiled missingGroup = new ReplacementRule("a", "$2").compile(tokens);
		assertThrows(BumpException.class, () -> missingGroup.apply("a"));
	}

	@Test
	void omittedSearchUsesTheDefaultSearch() throws BumpException {
		ReplacementRule onlyScope = new ReplacementRule();
		assertTrue(onlyScope.usesDefaultSearch());
		assertEquals("v 1.2.4 11.2.30", onlyScope.compile(tokens("1.2.3", "1.2.4")).apply("v 1.2.3 11.2.30").getText());

		ReplacementRule customReplace = new ReplacementRule("", "[@newVersion@]");
		assertEquals("[1.2.4] 1.2.30", customReplace.compile(tokens("1.2.3", "1.2.4")).apply("1.2.3 1.2.30").getText());
	}

	@Test
	void scopeByFilesIncludesAndExcludes(@TempDir Path base) throws BumpException {
		ReplacementRule byFile = new ReplacementRule();
		byFile.setFiles(Collections.singletonList("config/app.yaml"));
		ReplacementRule.Compiled fileRule = byFile.compile(tokens("1.2.3", "1.2.4"), base);
		assertTrue(fileRule.appliesTo(base.resolve("config/app.yaml")));
		assertTrue(fileRule.appliesTo(base.resolve("config/../config/app.yaml")));
		assertFalse(fileRule.appliesTo(base.resolve("config/other.yaml")));

		ReplacementRule byGlob = new ReplacementRule();
		byGlob.setIncludes(Collections.singletonList("**/*.yaml"));
		byGlob.setExcludes(Collections.singletonList("legacy/**"));
		ReplacementRule.Compiled globRule = byGlob.compile(tokens("1.2.3", "1.2.4"), base);
		assertTrue(globRule.appliesTo(base.resolve("app.yaml")));
		assertTrue(globRule.appliesTo(base.resolve("config/app.yaml")));
		assertFalse(globRule.appliesTo(base.resolve("legacy/app.yaml")));
		assertFalse(globRule.appliesTo(base.resolve("app.properties")));

		ReplacementRule excludesOnly = new ReplacementRule();
		excludesOnly.setExcludes(Collections.singletonList("*.md"));
		ReplacementRule.Compiled excludeRule = excludesOnly.compile(tokens("1.2.3", "1.2.4"), base);
		assertFalse(excludeRule.appliesTo(base.resolve("README.md")));
		assertTrue(excludeRule.appliesTo(base.resolve("a.txt")));
	}

	@Test
	void ruleFilesMustStayInsideTheProject(@TempDir Path base) throws BumpException {
		ReplacementRule rule = new ReplacementRule();
		rule.setFiles(Collections.singletonList("../outside.txt"));
		VersionTokens tokens = tokens("1.2.3", "1.2.4");
		BumpException e = assertThrows(BumpException.class, () -> rule.compile(tokens, base));
		assertTrue(e.getMessage().contains("outside the project"));
	}
}
