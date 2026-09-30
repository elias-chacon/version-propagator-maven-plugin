# Version Propagator Plugin

![Java 8](https://shields.io)
![Maven](https://shields.io)
![Coverage Badge](.github/badges/jacoco.svg)
![Branches Badge](.github/badges/branches.svg)
[![codecov](https://codecov.io/gh/elias-chacon/version-propagator-maven-plugin/graph/badge.svg?token=syFvGZQXLV)](https://codecov.io/gh/elias-chacon/version-propagator-maven-plugin)
![GitHub issues](https://shields.io)

**Version:** v0.11.0-SNAPSHOT
**Updated:** 2026-09-30

A Maven plugin with one goal, `bump`. It:

1. reads the current project version,
2. works out the next version for the part you choose (`major`, `minor`, `patch` or `build`),
3. updates the version in the POM without changing the rest of its formatting,
4. propagates the new version to other files (YAML, properties, docs...),
5. lists each file it changed, skipped or found no match in,
6. has a dry-run mode that writes nothing.

Coordinates: `io.github.eliaschacon:version-propagator-maven-plugin:0.11.0-SNAPSHOT`, goal prefix `propagate`.
The goal is still `bump` (`mvn propagate:bump`) and parameters keep the `bump.*` prefix (`-Dbump.part=minor`).
Base package: `io.github.eliaschacon.versionbump`. To publish under other coordinates, change the
`groupId`/`artifactId` in `pom.xml` and the `goalPrefix` of `maven-plugin-plugin`.

## Prerequisites

- JDK 8 or newer. The plugin is compiled for Java 8 and runs on newer JDKs.
- Maven 3.6.3 or newer.
- No other tools or runtime dependencies are needed. The plugin uses only the Maven API and the JDK.

## Build, test and install

This project runs Maven through wrapper scripts that load `.env` (`JAVA_HOME`, `MAVEN_HOME`, `MAVEN_SETTINGS`).
If you don't use the wrappers, run plain `mvn` with the same arguments.

```bash
.\mvn.bat clean verify     # Windows: compile, run tests, generate the plugin descriptor, package
./mvn.sh clean verify      # Linux / macOS / Git Bash
.\mvn.bat clean install    # install into the local repository (~/.m2)
```

Unit tests use JUnit 5 and write only to temporary directories.

## SonarQube analysis (`sonar` profile)

The `sonar` profile is kept apart from the regular build. It adds JaCoCo coverage
(`target/site/jacoco/jacoco.xml`) and the `sonar-maven-plugin`. The analysis runs in two steps because
the scanner needs JDK 17+, while the plugin is built and tested on its Java 8 target:

```powershell
# 1. build, test and write coverage on Java 8 (wrapper, .env)
.\mvn.bat -Psonar clean verify

# 2. analysis only (no recompilation) on JDK 17, against the local server
$env:JAVA_HOME = 'C:\Users\eachacon\.jdks\ms-17.0.19'
& "$env:MAVEN_HOME\bin\mvn.cmd" -s <settings.xml> -Psonar sonar:sonar `
    "-Dsonar.host.url=http://localhost:9000" "-Dsonar.token=$env:SONAR_TOKEN"
```

- The token comes from the `SONAR_TOKEN` environment variable. It is never stored in the project.
- Active profiles in `settings.xml` override POM properties. When a corporate `settings.xml` sets
  `sonar.host.url`, `sonar.login` or `sonar.token`, pass the local values with `-D`, because command-line
  properties win over `settings.xml`.
- The Maven JVM needs Java 11+ (a `sonar-maven-plugin` 5.x requirement). The analysis engine of recent
  servers (SonarQube Community Build 26.x) needs **Java 21+**. The scanner's JRE auto-provisioning handles
  that by downloading a compatible JRE from the server, so it stays enabled.
- Status: 0 issues (bugs, code smells, vulnerabilities, security hotspots), 0% duplication, about 89% coverage,
  quality gate passed ("Sonar way" profile).

## Using the plugin in a project

Declare the plugin in the consumer `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.eliaschacon</groupId>
            <artifactId>version-propagator-maven-plugin</artifactId>
            <version>0.11.0-SNAPSHOT</version>
            <configuration>
                <updateFiles>true</updateFiles>
                <includes>
                    <include>**/*.yaml</include>
                    <include>**/*.properties</include>
                </includes>
                <excludes>
                    <exclude>**/secret/**</exclude>
                </excludes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

When the plugin is declared in the POM, the short prefix works: `mvn propagate:bump -Dbump.part=patch`.
Without a declaration, use the full coordinates:

```bash
mvn io.github.eliaschacon:version-propagator-maven-plugin:0.11.0-SNAPSHOT:bump -Dbump.part=patch
```

> Maven rule: a value set in `<configuration>` in the POM always wins over the matching `-Dbump.*`
> property. A property only takes effect when that parameter is not configured in the POM.

### Examples

```bash
mvn propagate:bump -Dbump.part=major   # 1.2.3 -> 2.0.0     1.2.3.4 -> 2.0.0.0
mvn propagate:bump -Dbump.part=minor   # 1.2.3 -> 1.3.0     1.2.3.4 -> 1.3.0.0
mvn propagate:bump -Dbump.part=patch   # 1.2.3 -> 1.2.4     1.2.3.4 -> 1.2.4.0
mvn propagate:bump -Dbump.part=build   # 1.2.3 -> 1.2.3.1   1.2.3.4 -> 1.2.3.5
```

Dry run (nothing is written):

```bash
mvn io.github.eliaschacon:version-propagator-maven-plugin:0.11.0-SNAPSHOT:bump \
  -Dbump.part=minor \
  -Dbump.dryRun=true
```

Update YAML and properties files, excluding a directory:

```bash
mvn propagate:bump -Dbump.part=patch -Dbump.updateFiles=true \
  "-Dbump.includes=**/*.yaml,**/*.properties" "-Dbump.excludes=**/secret/**"
```

Specific files and directories (relative paths resolve against the project base directory):

```bash
mvn propagate:bump -Dbump.part=patch -Dbump.updateFiles=true \
  -Dbump.files=README.md,docs/install.md \
  -Dbump.directories=src/main/resources "-Dbump.includes=**/*.properties"
```

Backups (`<file>.bak` holds the original content; the plugin refuses to overwrite an existing backup
unless `bump.overwriteBackups=true`):

```bash
mvn propagate:bump -Dbump.part=minor -Dbump.createBackup=true
```

Only files, POM untouched (for example when the version is inherited):

```bash
mvn propagate:bump -Dbump.part=patch -Dbump.updatePom=false -Dbump.updateFiles=true -Dbump.files=VERSION.txt
```

### Real execution (sample multi-module project)

```text
$ mvn -f sample/pom.xml io.github.eliaschacon:version-propagator-maven-plugin:0.11.0-SNAPSHOT:bump \
      -Dbump.part=patch -Dbump.createBackup=true -Dbump.files=config/build.properties,config/application.yaml
[INFO] --- propagate:0.11.0-SNAPSHOT:bump (default-cli) @ sample ---
[INFO] Bumping patch: 1.2.3-SNAPSHOT -> 1.2.4-SNAPSHOT
[INFO] [update] pom.xml (1 replacement(s), POM 1.2.3-SNAPSHOT -> 1.2.4-SNAPSHOT)
[INFO] [update] core/pom.xml (1 replacement(s), POM 1.2.3-SNAPSHOT -> 1.2.4-SNAPSHOT)
[INFO] [update] config/application.yaml (1 replacement(s), 1.2.3-SNAPSHOT -> 1.2.4-SNAPSHOT)
[INFO] [update] config/build.properties (1 replacement(s), 1.2.3-SNAPSHOT -> 1.2.4-SNAPSHOT)
[INFO] [backup] .../sample/pom.xml.bak
[INFO] [backup] .../sample/core/pom.xml.bak
[INFO] [backup] .../sample/config/application.yaml.bak
[INFO] [backup] .../sample/config/build.properties.bak
[INFO] Version bumped to 1.2.4-SNAPSHOT: 4 file(s) changed.
```

In this run, `lib: 11.2.30` in `application.yaml`, `target/stale.yaml` and the version of the plugin
declaration in the POM stayed unchanged.

## Parameters

| Parameter            | Property                  | Default           | Description                                                                                  |
|----------------------|---------------------------|-------------------|----------------------------------------------------------------------------------------------|
| `part`               | `bump.part`               | (required)        | `major`, `minor`, `patch` or `build` (case-insensitive).                                     |
| `dryRun`             | `bump.dryRun`             | `false`           | Show planned changes and write nothing.                                                      |
| `updatePom`          | `bump.updatePom`          | `true`            | Update `<project><version>` of the execution-root POM.                                       |
| `updateModules`      | `bump.updateModules`      | `true`            | Update reactor modules too (see [Multi-module](#multi-module-reactors)).                     |
| `updateFiles`        | `bump.updateFiles`        | `false`           | Replace the version in `files` and in files found under `directories` that match `includes`. |
| `includes`           | `bump.includes`           | —                 | Glob patterns relative to each directory, e.g. `**/*.yaml`.                                  |
| `excludes`           | `bump.excludes`           | —                 | Glob patterns to exclude. Excluded directories are not scanned.                              |
| `useDefaultExcludes` | `bump.useDefaultExcludes` | `true`            | Also exclude `**/target/**`, `**/.git/**`, `**/.svn/**`, `**/.hg/**`.                        |
| `directories`        | `bump.directories`        | project base dir  | Directories to scan with `includes`.                                                         |
| `files`              | `bump.files`              | —                 | Specific files. They are always processed; `includes`/`excludes` do not filter them.         |
| `replacements`       | — (POM only)              | safe default rule | Custom replacement rules, optionally limited to some files (see below).                      |
| `encoding`           | `bump.encoding`           | `UTF-8`           | Encoding of the additional files. POMs use their XML declaration.                            |
| `createBackup`       | `bump.createBackup`       | `false`           | Write `<file><backupSuffix>` before changing a file (POMs included).                         |
| `backupSuffix`       | `bump.backupSuffix`       | `.bak`            | Suffix for backup files.                                                                     |
| `overwriteBackups`   | `bump.overwriteBackups`   | `false`           | Allow replacing existing backups.                                                            |
| `failOnNoMatch`      | `bump.failOnNoMatch`      | `false`           | Fail if the configured files contain no occurrence of the old version.                       |
| `qualifierPolicy`    | `bump.qualifierPolicy`    | `fail`            | How to handle qualifiers other than `-SNAPSHOT`: `fail`, `preserve` or `remove`.             |
| `followSymlinks`     | `bump.followSymlinks`     | `false`           | Follow symbolic links. The link target must be inside the project.                           |
| `skip`               | `bump.skip`               | `false`           | Skip the goal.                                                                               |

For list parameters, use a comma-separated value on the command line (`-Dbump.includes=a,b`) or
child elements in the POM (`<includes><include>a</include></includes>`).

## Version rules

Accepted formats are `MAJOR.MINOR.PATCH` and `MAJOR.MINOR.PATCH.BUILD`. Either can be followed by
`-QUALIFIER`, `-SNAPSHOT`, or both. Components must be non-negative integers with no leading zeros.

| Part  | `1.2.3`                                               | `1.2.3.4` |
|-------|-------------------------------------------------------|-----------|
| major | `2.0.0`                                               | `2.0.0.0` |
| minor | `1.3.0`                                               | `1.3.0.0` |
| patch | `1.2.4`                                               | `1.2.4.0` |
| build | `1.2.3.1` (a BUILD component is added, starting at 1) | `1.2.3.5` |

`build` never behaves like `patch`.

- **`-SNAPSHOT`** is always kept: `1.2.3-SNAPSHOT` + patch gives `1.2.4-SNAPSHOT`.
- **Other qualifiers** (`-RC1`, `-beta.2`...) are rejected by default so the plugin never guesses.
  `-Dbump.qualifierPolicy=preserve` gives `1.2.3-RC1` -> `1.2.4-RC1`.
  `remove` gives `1.2.3-RC1` -> `1.2.4`. Both work together with `-SNAPSHOT` (`1.2.3-RC1-SNAPSHOT`).
- **Rejected versions**: anything the plugin can't read safely, such as `1.0`, `1.0-SNAPSHOT`,
  `01.2.3`, `1.2.3.4.5`, `1.2.3.RELEASE`, `v1.2.3`, `${revision}`, or values that overflow. The error
  shows the version it received and the accepted formats.

## POM update

- **Parsing** uses the JDK SAX parser (JAXP, `PomParser`); there is no hand-written XML parsing. The parser
  rejects malformed XML with the line and column, detects the encoding (XML declaration, UTF-8 BOM,
  UTF-16/32 byte order), reads the project and parent coordinates and, through its `Locator`, gives the
  exact line and column of each element. It is hardened against XXE: external DTDs and entities are never loaded.
- **Writing** replaces only the characters of `<project><version>` (and `<parent><version>` in modules) at
  the positions reported by the parser. Whitespace, comments, attribute order, quotes, line endings (LF, CRLF,
  CR), character references and the XML declaration stay byte for byte. A DOM round-trip cannot guarantee
  that: XML parsers must turn CRLF into LF, and serialisers rewrite the declaration, attribute layout and
  character references.
- **Cross-checks:** the text at the reported position must equal the value the parser reads. A version
  written with entities, character references, CDATA or comments (`1.0&#46;0`) is rejected instead of edited
  on a guess. After the edit, the new content is parsed again and must still be well-formed, with exactly the
  expected values, before anything is written.
- Dependency, plugin, property and `dependencyManagement` versions are never changed. This
  includes the version of this plugin declared in the POM.
- **Inherited version**: if the POM has no `<version>` because it inherits one from `<parent>`, the
  goal fails and suggests these fixes: declare `<version>`, run on the parent, or use `-Dbump.updatePom=false`.
- **Property version** (`<version>${revision}</version>`): rejected. Update the property instead.
- If Maven's effective project version differs from the literal version in the POM, the goal fails.

### Multi-module reactors

The goal is an **aggregator**: it runs once, on the project where Maven is invoked (the execution
root), and ignores modules outside the current reactor (`-pl`, `-N` narrow the reactor).

With `updateModules=true` (the default), a reactor module is changed only if its `<parent>` points
to a project being bumped (same `groupId:artifactId`) with the old version. In that case:

- its `<parent><version>` is updated;
- its own `<version>` is updated too, if present and equal to the old version;
- a module with a different explicit version keeps it, and only its parent reference changes;
- the rule applies transitively, so grandchildren of bumped modules are covered;
- modules with an external parent or a different parent version are left unchanged.

With `updateModules=false`, only the root POM changes, and a warning lists the modules that still
point to the old parent version.

## File replacement

- Files come from `files` plus a scan of `directories` (default: the base directory) filtered by
  `includes`/`excludes`. Glob syntax is portable: `*`, `?`, `**`, `/` separators on every OS,
  case-sensitive, and matched relative to each scanned directory.
- The plugin never touches:
	- `target/` and VCS directories (default excludes);
	- symbolic links (unless `followSymlinks`);
	- binary files (a NUL byte in the first 8 KiB);
	- files that are not valid in `encoding` (reported as skipped);
	- reactor POMs (the POM update handles these);
	- files ending with the backup suffix.
- All paths must be inside the project base directory. Missing directories or files, and paths
  outside the project, make the goal fail before anything is written.
- The file is decoded and re-encoded strictly with `encoding`. Content that isn't replaced, including
  a BOM and line endings, is kept byte for byte. A file with no match is not rewritten.
- Every file is reported as `[update]`/`[would update]` (with its replacement count), `[no match]`,
  `[no rule]`, `[unchanged]` or `[skipped] (reason)`.

### Default rule

With no `replacements`, this rule is used:

```
search : (?<![0-9.])${oldVersion}(?![0-9A-Za-z_]|\.[0-9]|-[0-9A-Za-z])
replace: ${newVersion}
```

The version is inserted literally (quoted), so its dots are not regex wildcards. The lookarounds
make sure only the complete version is replaced. Example when bumping `1.2.3`:

| Text                                                                                         | Replaced? |
|----------------------------------------------------------------------------------------------|-----------|
| `1.2.3`, `v1.2.3`, `app-1.2.3.jar`, `"1.2.3"`, `Release 1.2.3.`                              | yes       |
| `11.2.30`, `11.2.3`, `1.2.30`, `0.1.2.3`, `1.2.3.4`, `1.2.3-SNAPSHOT`, `1.2.3-RC1`, `1.2.3a` | no        |

### Custom rules

Each `<replacement>` has a `search` (Java regex) and a `replace` (Java replacement string, `$1` = group 1).
Rules run in order, each on the output of the previous one. Configuring any rule replaces the default rule.
To keep the default behaviour next to custom rules, add an empty `<replacement/>`, which uses the default
search and replace. Rules can also be limited to specific files; see [Per-file rules](#per-file-rules).

Tokens: `oldVersion`, `newVersion`, `oldMajor`, `oldMinor`, `oldPatch`, `oldBuild`, `newMajor`,
`newMinor`, `newPatch`, `newBuild`. Each can be written as `${name}` or `@name@`. In `search`, token values
are regex-quoted. In `replace`, they are inserted literally. `*Build` tokens fail with a clear error when
the version has no BUILD component.

> **Important:** Maven interpolates `${...}` in POM configuration. A value that is only
> `${newVersion}` becomes `null`. The plugin then fails and explains why. In the POM, use `@newVersion@` or
> the Maven escape `$${newVersion}`.

```xml
<configuration>
    <updateFiles>true</updateFiles>
    <includes><include>**/*.properties</include></includes>
    <replacements>
        <replacement>
            <search>(app\.version=)@oldVersion@</search>
            <replace>$1@newVersion@</replace>
        </replacement>
        <replacement>
            <search>release=@oldMajor@\.@oldMinor@\.\d+</search>
            <replace>release=@newMajor@.@newMinor@.0</replace>
        </replacement>
    </replacements>
</configuration>
```

### Per-file rules

A `<replacement>` can be limited to some files. All paths and globs are relative to the project base directory.

| Element                                     | Meaning                                                                                                                                                                   |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `<files><file>…</file></files>`             | Specific files. They are **selected for processing automatically**; there is no need to list them again in the global `files`. They must exist and be inside the project. |
| `<includes><include>…</include></includes>` | Globs. Matching files are **selected automatically**: the project base directory is scanned, minus the default excludes, global `excludes` and the rule `excludes`.       |
| `<excludes><exclude>…</exclude></excludes>` | Globs the rule must not touch, even if a global setting selected them.                                                                                                    |

- A rule **with** `files`/`includes` applies only to those files. A rule **without** them applies to
  every processed file.
- If you omit `<search>`, the rule uses the safe [default search](#default-rule). If you also omit
  `<replace>`, it defaults to the new version. So `<replacement><files>…</files></replacement>` is the
  default rule limited to those files.
- A processed file that no rule applies to is reported as `[no rule]` and left unchanged.
- Reactor POMs stay protected: listing `pom.xml` in a rule has no effect.

```xml
<configuration>
    <updateFiles>true</updateFiles>
    <replacements>
        <!-- custom pattern, only in application.yaml -->
        <replacement>
            <files><file>config/application.yaml</file></files>
            <search>(image: acme/app:)@oldVersion@</search>
            <replace>$1@newVersion@</replace>
        </replacement>
        <!-- default search, only in docs (except docs/old) -->
        <replacement>
            <includes><include>docs/**/*.md</include></includes>
            <excludes><exclude>docs/old/**</exclude></excludes>
        </replacement>
        <!-- default search, only in VERSION -->
        <replacement>
            <files><file>VERSION</file></files>
        </replacement>
    </replacements>
</configuration>
```

Result of `mvn propagate:bump -Dbump.part=minor` with that configuration:

```text
[INFO] Bumping minor: 1.2.3 -> 1.3.0
[INFO] [update] pom.xml (1 replacement(s), POM 1.2.3 -> 1.3.0)
[INFO] [update] config/application.yaml (1 replacement(s), 1.2.3 -> 1.3.0)   # "image: acme/app:1.3.0"; "lib: acme/lib:1.2.3" untouched
[INFO] [update] docs/install.md (1 replacement(s), 1.2.3 -> 1.3.0)
[INFO] [update] VERSION (1 replacement(s), 1.2.3 -> 1.3.0)
[INFO] Version bumped to 1.3.0: 4 file(s) changed.
```

## Safety

- **Plan first, then write.** The goal validates every parameter, computes all changes (POMs and files)
  and checks every precondition before it writes anything: files unchanged since planning, files
  writable, backups not already present, `failOnNoMatch` satisfied.
- **Atomic writes.** Each file goes to a temporary sibling (flushed, POSIX permissions kept) and then
  replaces the original with an atomic move.
- **Rollback.** If a write fails, files already written are restored to their original bytes.
- **Dry run.** Runs the same validation and prints the version change, each file with its replacement
  count, and `DRY RUN: no file was written`.

## Technical decisions

- **Java 8 target**, as the project requires. Uses Maven API 3.9.9 (provided scope) and Maven Plugin Tools 3.15.1.
- **SAX parser, position-based edits.** The JDK SAX parser (no extra dependency) is the only XML parser.
  One pass over the bytes validates the POM and detects the encoding. A second pass over the decoded text
  takes the positions from the parser's `Locator`. Before that pass, lone CRs are turned into LF (XML
  end-of-line normalisation, same length), because the JDK parser miscounts columns after a lone CR.
  The edit replaces exactly those characters and is verified by parsing again.
	- DOM was rejected: it has no source positions, and writing it back normalises CRLF and rewrites the XML
	  declaration and character references (tested).
	- The earlier hand-written scanner was removed.
- **Aggregator goal**, so a reactor is bumped once and consistently instead of once per module.
- **Core free of Maven API.** Apart from logging, the core does not depend on the Maven API.
  `BumpService` receives a `BumpRequest`; `BumpMojo` only maps parameters, which keeps the core easy to test.
- **Lombok** (compile-time only, `provided` scope; nothing is added to the plugin's runtime classpath):
	- `@Data` is the default for data carriers (`Version`, `BumpRequest`, `BumpResult`, `FileChange`,
	  `PomFile`, `PomDocument`, `ReplacementRule`, `FileReplacer.Outcome`...).
	- `@Builder` builds configuration and result objects (`BumpRequest`, `BumpResult`, `FileChange`,
	  `ChangeApplier`, `ReplacementRule`, `FileReplacer.Outcome`).
	- `@RequiredArgsConstructor` is used for behaviour classes (services, planners, matchers).
	- Some methods stay hand-written on purpose: `Version.toString()` is the canonical version text,
	  `ReplacementRule.toString()` feeds error messages, and the `byte[]` getters of `FileChange`/`PomFile`
	  return defensive copies.
	- `lombok.config` adds `@lombok.Generated`, so JaCoCo and SonarQube ignore generated code.
	- IDEs need the Lombok plugin; IntelliJ includes it.
- **Rules replace the default** instead of adding to it, so the configuration always shows exactly
  what will run. An empty `<replacement/>` brings the default back explicitly.
- **Per-file rules select their own files.** A rule's `files`/`includes` both limit where it applies and
  select those files for processing. Without that, a rule would need its files listed twice (once globally,
  once in the rule). Rule globs are relative to the project base directory, because a rule does not belong
  to any scanned directory.

## Known limitations

- `${revision}`-style (CI-friendly) versions and versions inherited from a parent can't be updated in the POM.
- POM coordinates written with entities or character references are rejected (not edited). Use plain text.
- Only `MAJOR.MINOR.PATCH[.BUILD]` versions are supported (no two-component versions such as `1.0`).
- Literal versions of sibling modules in `<dependencies>`/`<dependencyManagement>` are not updated.
  Use `${project.version}` for them.
- Modules outside the current reactor (not listed in `<modules>`, or excluded with `-pl`) are not updated.
- The default rule does not update classifier file names such as `app-1.2.3-sources.jar`, because
  `-sources` looks like a qualifier. Add a custom rule for those.
- Globs don't support `[...]` or `{a,b}` (they are treated as literal text).
- The symbolic-link tests are skipped on Windows accounts that can't create links.
- Binary detection is a heuristic (a NUL byte in the first 8 KiB). UTF-16/UTF-32 encodings skip it.

## Project layout

```
pom.xml
src/main/java/io/github/eliaschacon/versionbump/
    BumpMojo.java            Maven goal "bump" (parameter mapping only)
    BumpService.java         orchestration: plan, report, apply
    FileSelector.java        selection of the additional files (global and per-rule)
    BumpRequest.java / BumpResult.java / BumpException.java
    version/                 Version, VersionPart, QualifierPolicy
    pom/                     PomParser (JDK SAX, XXE-safe), PomDocument (text, charset, element ranges),
                             PomFile (load + post-edit verification), PomVersionPlanner
    files/                   GlobMatcher, FileScanner, ReplacementRule, VersionTokens, FileReplacer
    io/                      TextFiles, FileChange, AtomicFileWriter, ChangeApplier
src/test/java/...            JUnit 5 tests (temporary directories only)
```
