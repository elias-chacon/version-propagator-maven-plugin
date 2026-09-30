package io.github.eliaschacon.versionbump.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.version.Version;

class FileReplacerTest {

	@TempDir
	Path dir;

	private FileReplacer replacer(ReplacementRule... rules) throws BumpException {
		VersionTokens tokens = new VersionTokens(Version.parse("1.2.3"), Version.parse("1.3.0"));
		ReplacementRule.Compiled[] compiled = new ReplacementRule.Compiled[rules.length];
		for (int i = 0; i < rules.length; i++) {
			compiled[i] = rules[i].compile(tokens);
		}
		return new FileReplacer(Arrays.asList(compiled), StandardCharsets.UTF_8, "1.2.3 -> 1.3.0");
	}

	private Path file(String name, byte[] content) throws Exception {
		Path file = dir.resolve(name);
		Files.write(file, content);
		return file;
	}

	@Test
	void plansChangePreservingRestOfContent() throws Exception {
		String content = "\uFEFFname: démo\r\nversion: 1.2.3\r\nother: 11.2.30\r\n";
		Path file = file("app.yaml", content.getBytes(StandardCharsets.UTF_8));

		FileReplacer.Outcome outcome = replacer(ReplacementRule.defaultRule()).process(file);

		assertEquals(FileReplacer.Status.CHANGED, outcome.getStatus());
		assertEquals(1, outcome.getReplacements());
		assertEquals(content.replace("version: 1.2.3", "version: 1.3.0"),
			new String(outcome.getChange().getNewBytes(), StandardCharsets.UTF_8));
		// planning never writes
		assertEquals(content, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
	}

	@Test
	void reportsNoMatch() throws Exception {
		Path file = file("a.properties", "version=11.2.30\n".getBytes(StandardCharsets.UTF_8));
		FileReplacer.Outcome outcome = replacer(ReplacementRule.defaultRule()).process(file);
		assertEquals(FileReplacer.Status.NO_MATCH, outcome.getStatus());
		assertNull(outcome.getChange());
	}

	@Test
	void skipsBinaryFiles() throws Exception {
		Path file = file("a.bin", new byte[]{'1', '.', '2', '.', '3', 0, 1, 2});
		assertEquals(FileReplacer.Status.SKIPPED_BINARY, replacer(ReplacementRule.defaultRule()).process(file).getStatus());
	}

	@Test
	void skipsFilesThatCannotBeDecoded() throws Exception {
		Path file = file("latin1.txt", "versão 1.2.3".getBytes(StandardCharsets.ISO_8859_1));
		assertEquals(FileReplacer.Status.SKIPPED_UNDECODABLE,
			replacer(ReplacementRule.defaultRule()).process(file).getStatus());
	}

	@Test
	void appliesRulesInOrder() throws Exception {
		Path file = file("a.txt", "v=1.2.3 major=1".getBytes(StandardCharsets.UTF_8));
		FileReplacer.Outcome outcome = replacer(ReplacementRule.defaultRule(),
			new ReplacementRule("major=${oldMajor}\\b", "major=${newMajor}")).process(file);
		assertEquals(2, outcome.getReplacements());
		assertEquals("v=1.3.0 major=1", new String(outcome.getChange().getNewBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void identicalResultIsNotAChange() throws Exception {
		Path file = file("a.txt", "keep".getBytes(StandardCharsets.UTF_8));
		assertEquals(FileReplacer.Status.UNCHANGED, replacer(new ReplacementRule("keep", "keep")).process(file).getStatus());
	}

	@Test
	void failsOnUnreadableFile() throws BumpException {
		FileReplacer replacer = replacer(ReplacementRule.defaultRule());
		Path missing = dir.resolve("missing.txt");
		assertThrows(BumpException.class, () -> replacer.process(missing));
	}

	@Test
	void emptyRuleListAppliesNoRule() throws Exception {
		Path file = file("a.txt", "1.2.3".getBytes(StandardCharsets.UTF_8));
		FileReplacer r = new FileReplacer(Collections.<ReplacementRule.Compiled>emptyList(), StandardCharsets.UTF_8, "");
		assertEquals(FileReplacer.Status.NO_RULE, r.process(file).getStatus());
	}

	@Test
	void appliesOnlyRulesWhoseScopeContainsTheFile() throws Exception {
		Path yaml = file("app.yaml", "version: 1.2.3\n".getBytes(StandardCharsets.UTF_8));
		Path props = file("app.properties", "version=1.2.3\n".getBytes(StandardCharsets.UTF_8));
		Path other = file("notes.txt", "1.2.3\n".getBytes(StandardCharsets.UTF_8));
		ReplacementRule yamlRule = new ReplacementRule("(version: )@oldVersion@", "$1@newVersion@-yaml");
		yamlRule.setFiles(Collections.singletonList("app.yaml"));
		ReplacementRule propsRule = new ReplacementRule("(version=)@oldVersion@", "$1@newVersion@-props");
		propsRule.setIncludes(Collections.singletonList("*.properties"));

		VersionTokens tokens = new VersionTokens(Version.parse("1.2.3"), Version.parse("1.3.0"));
		FileReplacer replacer = new FileReplacer(Arrays.asList(yamlRule.compile(tokens, dir), propsRule.compile(tokens, dir)),
			StandardCharsets.UTF_8, "");

		assertEquals("version: 1.3.0-yaml\n",
			new String(replacer.process(yaml).getChange().getNewBytes(), StandardCharsets.UTF_8));
		assertEquals("version=1.3.0-props\n",
			new String(replacer.process(props).getChange().getNewBytes(), StandardCharsets.UTF_8));
		assertEquals(FileReplacer.Status.NO_RULE, replacer.process(other).getStatus());
	}
}
