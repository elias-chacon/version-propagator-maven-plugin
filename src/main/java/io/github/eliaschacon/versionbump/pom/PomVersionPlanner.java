package io.github.eliaschacon.versionbump.pom;

import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.io.FileChange;
import io.github.eliaschacon.versionbump.io.TextFiles;
import lombok.RequiredArgsConstructor;

/**
 * Plans the POM edits of a bump.
 *
 * <p>Rules:</p>
 * <ol>
 *     <li>The root project must declare {@code <version>} literally; an inherited version or a property
 *     expression is rejected because changing it would require editing another file.</li>
 *     <li>When module updates are enabled, a reactor module is changed only when its {@code <parent>}
 *     points to a project that is being bumped (same groupId:artifactId) with the old version. Then its
 *     parent version is updated and, if the module declares its own version equal to the old version, that
 *     version too. The rule is applied transitively (grandchildren of the root).</li>
 *     <li>Nothing else is edited: dependencies, plugins and properties are never touched.</li>
 * </ol>
 */
@RequiredArgsConstructor
public final class PomVersionPlanner {

	private final Log log;

	/**
	 * Returns the literal {@code <version>} of the root POM, failing when it cannot be updated safely.
	 */
	public static String explicitVersion(PomFile root) throws BumpException {
		PomDocument doc = root.getDocument();
		String version = doc.value(PomDocument.PROJECT_VERSION);
		if (version == null) {
			if (doc.hasParent()) {
				throw new BumpException("The project version in " + root.getPath() + " is inherited from the parent "
					+ doc.parentKey() + ":" + doc.value(PomDocument.PARENT_VERSION) + " and cannot be bumped here."
					+ " Either declare <version> explicitly in this POM, run the plugin on the parent project,"
					+ " or use -Dbump.updatePom=false to only update files.");
			}
			throw new BumpException("No <version> element found in " + root.getPath() + ".");
		}
		if (version.contains("${")) {
			throw new BumpException("The project version in " + root.getPath() + " is the expression '" + version
				+ "'. Property-based (CI-friendly) versions are not supported: update the property itself"
				+ " or use -Dbump.updatePom=false to only update files.");
		}
		return version;
	}

	private static List<PomFile> childrenOf(List<PomFile> modules, Set<String> parentKeys) {
		List<PomFile> children = new ArrayList<>();
		for (PomFile module : modules) {
			PomDocument doc = module.getDocument();
			if (doc.hasParent() && parentKeys.contains(doc.parentKey())) {
				children.add(module);
			}
		}
		return children;
	}

	/**
	 * Replaces the text of the {@code elements} and verifies the result with the XML parser
	 * ({@link PomFile#verifyEdit}) before handing the change over for writing.
	 */
	private static FileChange toChange(PomFile pom, List<String> elements, String oldVersion,
									   String newVersion) throws BumpException {
		List<PomDocument.TextRange> ranges = new ArrayList<>();
		for (String element : elements) {
			ranges.add(pom.getDocument().get(element));
		}
		String updated = pom.getDocument().replace(ranges, newVersion);
		byte[] newBytes;
		try {
			newBytes = TextFiles.encode(updated, pom.getCharset());
		} catch (CharacterCodingException e) {
			throw new BumpException("Cannot encode " + pom.getPath() + " using " + pom.getCharset() + ".", e);
		}
		pom.verifyEdit(newBytes, elements, newVersion);
		return FileChange.builder()
			.path(pom.getPath())
			.originalBytes(pom.getBytes())
			.newBytes(newBytes)
			.replacements(ranges.size())
			.description("POM " + oldVersion + " -> " + newVersion)
			.build();
	}

	/**
	 * @param root          execution root POM
	 * @param modules       other reactor POMs (may be empty)
	 * @param oldVersion    current version, must equal the root POM literal version
	 * @param newVersion    version to write
	 * @param updateModules whether to apply the module rule
	 */
	public List<FileChange> plan(PomFile root, List<PomFile> modules, String oldVersion, String newVersion,
								 boolean updateModules) throws BumpException {
		String rootVersion = explicitVersion(root);
		if (!rootVersion.equals(oldVersion)) {
			throw new BumpException("The version declared in " + root.getPath() + " (" + rootVersion
				+ ") differs from the project version (" + oldVersion + ").");
		}

		Map<PomFile, List<String>> edits = new LinkedHashMap<>();
		List<String> rootEdits = new ArrayList<>();
		rootEdits.add(PomDocument.PROJECT_VERSION);
		edits.put(root, rootEdits);

		if (updateModules) {
			planModules(root, modules, oldVersion, edits);
		} else if (!modules.isEmpty()) {
			log.warn("bump.updateModules=false: " + modules.size() + " reactor module(s) keep referencing parent version "
				+ oldVersion + ". Update them manually or enable updateModules.");
		}

		List<FileChange> changes = new ArrayList<>();
		for (Map.Entry<PomFile, List<String>> entry : edits.entrySet()) {
			changes.add(toChange(entry.getKey(), entry.getValue(), oldVersion, newVersion));
		}
		return changes;
	}

	/**
	 * Applies the module rule until no more module can be reached: each pass handles the modules whose parent
	 * is a bumped project, which may in turn make their own children eligible.
	 */
	private void planModules(PomFile root, List<PomFile> modules, String oldVersion,
							 Map<PomFile, List<String>> edits) {
		Set<String> bumpedKeys = new HashSet<>();
		bumpedKeys.add(root.getDocument().key());
		List<PomFile> pending = new ArrayList<>(modules);
		pending.remove(root);

		List<PomFile> reachable = childrenOf(pending, bumpedKeys);
		while (!reachable.isEmpty()) {
			pending.removeAll(reachable);
			for (PomFile module : reachable) {
				planModule(module, oldVersion, bumpedKeys, edits);
			}
			reachable = childrenOf(pending, bumpedKeys);
		}
		for (PomFile module : pending) {
			log.info("[skip] " + module.getPath() + ": its parent is not a bumped reactor project");
		}
	}

	/**
	 * Plans one module whose parent is being bumped; registers it as bumped when its own version changes.
	 */
	private void planModule(PomFile module, String oldVersion, Set<String> bumpedKeys,
							Map<PomFile, List<String>> edits) {
		PomDocument doc = module.getDocument();
		String parentVersion = doc.value(PomDocument.PARENT_VERSION);
		if (!oldVersion.equals(parentVersion)) {
			log.info("[skip] " + module.getPath() + ": parent version " + parentVersion + " is not " + oldVersion);
			return;
		}
		List<String> elements = new ArrayList<>();
		elements.add(PomDocument.PARENT_VERSION);
		String own = doc.value(PomDocument.PROJECT_VERSION);
		if (own != null && !oldVersion.equals(own)) {
			log.info("[keep] " + module.getPath() + ": own version " + own + " differs from " + oldVersion
				+ "; only the parent reference is updated");
		} else {
			if (own != null) {
				elements.add(PomDocument.PROJECT_VERSION);
			}
			// The module's effective version changes, so its own children follow the same rule.
			bumpedKeys.add(doc.key());
		}
		edits.put(module, elements);
	}
}
