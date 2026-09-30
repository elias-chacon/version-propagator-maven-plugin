package io.github.eliaschacon.versionbump.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.eliaschacon.versionbump.BumpException;

class GlobMatcherTest {

	@ParameterizedTest(name = "{0} ~ {1} = {2}")
	@CsvSource({
		"**/*.yaml, app.yaml, true",
		"**/*.yaml, config/app.yaml, true",
		"**/*.yaml, a/b/c/app.yaml, true",
		"**/*.yaml, app.yml, false",
		"**/*.yaml, app.yaml.bak, false",
		"*.properties, app.properties, true",
		"*.properties, conf/app.properties, false",
		"conf/*.properties, conf/app.properties, true",
		"conf/**, conf/a/b.txt, true",
		"conf/**, conf, true",
		"conf/**, config/a.txt, false",
		"conf/, conf/x.txt, true",
		"**/target/**, target/classes/a.yaml, true",
		"**/target/**, module/target/a.yaml, true",
		"**/target/**, targets/a.yaml, false",
		"src/**/*.md, src/README.md, true",
		"src/**/*.md, src/docs/x/README.md, true",
		"?.txt, a.txt, true",
		"?.txt, ab.txt, false",
		"**, any/path/file.bin, true",
		"./docs/*.md, docs/a.md, true",
		"docs\\*.md, docs/a.md, true",
		"file[1].txt, file[1].txt, true",
		"file.txt, fileXtxt, false"
	})
	void matches(String glob, String path, boolean expected) throws BumpException {
		assertEquals(expected, GlobMatcher.of(Collections.singletonList(glob)).matches(path));
	}

	@Test
	void matchesAnyOfSeveralPatterns() throws BumpException {
		GlobMatcher m = GlobMatcher.of(Arrays.asList("**/*.yaml", "**/*.properties"));
		assertTrue(m.matches("a/b.properties"));
		assertTrue(m.matches("b.yaml"));
		assertFalse(m.matches("b.json"));
	}

	@Test
	void emptyMatcherMatchesNothing() throws BumpException {
		GlobMatcher m = GlobMatcher.of(null);
		assertTrue(m.isEmpty());
		assertFalse(m.matches("a.txt"));
	}

	@Test
	void rejectsBlankPatterns() {
		assertThrows(BumpException.class, () -> GlobMatcher.of(Collections.singletonList(" ")));
	}
}
