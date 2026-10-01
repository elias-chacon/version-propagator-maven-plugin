package io.github.eliaschacon.versionbump.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GoalsTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
		"release:update-versions, release:update-versions",
		"org.apache.maven.plugins:maven-release-plugin:update-versions, release:update-versions",
		"org.apache.maven.plugins:maven-release-plugin:3.0.1:update-versions, release:update-versions",
		"org.codehaus.mojo:versions-maven-plugin:2.22.0:set, versions:set",
		"io.github.eliaschacon:version-propagator-maven-plugin:1.1.0:sync, version-propagator:sync",
		"Versions:Set, versions:set",
		"com.acme:custom:run, custom:run"
	})
	void normalizesGoals(String goal, String expected) {
		assertEquals(expected, Goals.normalize(goal));
	}

	@Test
	void phasesAreNotGoals() {
		assertNull(Goals.normalize("verify"));
		assertNull(Goals.normalize(null));
		assertFalse(Goals.same("verify", "verify"));
	}

	@Test
	void matchesMonitoredGoals() {
		assertTrue(VersionHookParticipant.monitored(
			Arrays.asList("clean", "org.apache.maven.plugins:maven-release-plugin:3.0.1:update-versions"),
			HookConfig.DEFAULT_GOALS));
		assertTrue(VersionHookParticipant.monitored(Collections.singletonList("versions:set"), HookConfig.DEFAULT_GOALS));
		assertFalse(VersionHookParticipant.monitored(Arrays.asList("clean", "install"), HookConfig.DEFAULT_GOALS));
		assertFalse(VersionHookParticipant.monitored(Collections.singletonList("release:prepare"),
			HookConfig.DEFAULT_GOALS));
		assertTrue(VersionHookParticipant.monitored(Collections.singletonList("release:prepare"),
			Collections.singletonList("release:prepare")));
	}
}
