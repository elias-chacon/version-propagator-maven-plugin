package io.github.eliaschacon.versionbump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

/**
 * Verifies the packaged plugin JAR (run by Failsafe after {@code package}, once per JDK toolchain): it is a
 * Multi-Release JAR and the running JVM loads the {@code PlatformTaskRunner} built for its version.
 */
class MultiReleaseJarIT {

	private static final String RUNNER = "io.github.eliaschacon.versionbump.concurrent.PlatformTaskRunner";
	private static final String RUNNER_ENTRY = RUNNER.replace('.', '/') + ".class";

	private static File jar() {
		File jar = new File(System.getProperty("plugin.jar", ""));
		assertTrue(jar.isFile(), "plugin JAR not found: " + jar);
		return jar;
	}

	/** Java feature release of the running JVM (8 for "1.8"). */
	private static int javaFeature() {
		String version = System.getProperty("java.specification.version");
		return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
	}

	@Test
	void isAMultiReleaseJarWithTheWholePlugin() throws Exception {
		try (JarFile jar = new JarFile(jar())) {
			assertEquals("true", jar.getManifest().getMainAttributes().getValue("Multi-Release"));
			assertEquals("io.github.eliaschacon.versionbump",
				jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
			for (String entry : Arrays.asList(
				"META-INF/maven/plugin.xml",
				"META-INF/plexus/components.xml",
				"io/github/eliaschacon/versionbump/BumpMojo.class",
				"io/github/eliaschacon/versionbump/SyncMojo.class",
				"io/github/eliaschacon/versionbump/hook/VersionHookParticipant.class",
				"io/github/eliaschacon/versionbump/BumpService.class",
				"io/github/eliaschacon/versionbump/concurrent/TaskRunner.class",
				RUNNER_ENTRY,
				"META-INF/versions/21/" + RUNNER_ENTRY)) {
				assertNotNull(jar.getJarEntry(entry), entry);
			}
		}
	}

	@Test
	void theDescriptorAllowsJava8() throws Exception {
		try (JarFile jar = new JarFile(jar());
			 Scanner scanner = new Scanner(jar.getInputStream(jar.getJarEntry("META-INF/maven/plugin.xml")),
				 StandardCharsets.UTF_8.name())) {
			String descriptor = scanner.useDelimiter("\\A").next();
			// Maven refuses to run a plugin whose descriptor requires a newer Java than the running one.
			assertTrue(descriptor.contains("<requiredJavaVersion>1.8</requiredJavaVersion>"), descriptor);
		}
	}

	@Test
	void theJvmLoadsTheRunnerBuiltForItsVersion() throws Exception {
		String expected = javaFeature() >= 21 ? "virtual-threads" : "sequential";
		// Isolated loader (no parent classpath): classes come only from the packaged JAR.
		try (URLClassLoader loader = new URLClassLoader(new URL[]{jar().toURI().toURL()}, null)) {
			Class<?> runnerClass = loader.loadClass(RUNNER);
			Object runner = runnerClass.getConstructor().newInstance();

			assertEquals(expected, runnerClass.getMethod("name").invoke(runner));
			assertEquals(Arrays.asList(0, 1, 2), runTasks(loader, runnerClass, runner, 3));
		}
	}

	/** Runs {@code count} tasks returning their index through the loaded runner. */
	private static Object runTasks(ClassLoader loader, Class<?> runnerClass, Object runner, int count)
		throws Exception {
		Class<?> taskClass = loader.loadClass("io.github.eliaschacon.versionbump.concurrent.TaskRunner$Task");
		List<Object> tasks = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final int index = i;
			tasks.add(Proxy.newProxyInstance(loader, new Class<?>[]{taskClass},
				(proxy, method, args) -> "call".equals(method.getName()) ? Integer.valueOf(index) : null));
		}
		Method runAll = runnerClass.getMethod("runAll", List.class);
		return runAll.invoke(runner, tasks);
	}
}
