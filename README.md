# Version Propagator Plugin

[![CI](https://github.com/elias-chacon/version-propagator-maven-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/elias-chacon/version-propagator-maven-plugin/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/elias-chacon/version-propagator-maven-plugin/graph/badge.svg?token=syFvGZQXLV)](https://codecov.io/gh/elias-chacon/version-propagator-maven-plugin)
[![GitHub issues](https://img.shields.io/github/issues/elias-chacon/version-propagator-maven-plugin)](https://github.com/elias-chacon/version-propagator-maven-plugin/issues)
![Coverage Badge](.github/badges/jacoco.svg)

![Version](https://img.shields.io/static/v1?label=Version&message=v1.1.1-SNAPSHOT&color=blue)
![Updated](https://img.shields.io/static/v1?label=Updated&message=2026-09-30&color=green)
![Java](https://img.shields.io/badge/Java-8%20to%2021%2B-blue)
![Maven](https://img.shields.io/badge/Maven-3.6.3%2B-C71A36)

A Maven plugin that changes the project version and propagates it to other files (README, YAML, properties...):

- **`bump` goal**: computes the next version (`major`, `minor`, `patch`, `build`, `release`, SNAPSHOT, release
  candidates or an explicit version) and writes it to the POMs of the reactor and to the configured files.
- **Hook**: after `release:update-versions` or `versions:set`, propagates the new version to the configured files.
- **`sync` goal**: propagates a version that is already in the POMs (also inside `release:prepare`).

POMs keep their formatting byte for byte, other versions (dependencies, plugins) are never touched, every change is
planned before anything is written, and a dry run shows the plan.

Coordinates: `io.github.eliaschacon:version-propagator-maven-plugin:1.1.1-SNAPSHOT`, goal prefix `propagate`
(`mvn propagate:bump`), parameters `bump.*`. One Multi-Release JAR runs on Java 8 to 21+ (Maven 3.6.3+) with no
runtime dependencies.

## Usage

```xml
<plugin>
    <groupId>io.github.eliaschacon</groupId>
    <artifactId>version-propagator-maven-plugin</artifactId>
    <version>${version-propagator.version}</version> <!-- the version in "Coordinates" above -->
    <configuration>
        <updateFiles>true</updateFiles>
        <files>
            <file>README.md</file>
        </files>
        <includes>
            <include>**/*.yaml</include>
            <include>**/*.properties</include>
        </includes>
    </configuration>
</plugin>
```

```bash
mvn propagate:bump -Dbump.part=minor                            # 1.2.3 -> 1.3.0
mvn propagate:bump -Dbump.part=build                            # 1.2.3 -> 1.2.3.1   1.2.3.4 -> 1.2.3.5
mvn propagate:bump -Dbump.part=release                          # 1.2.3-SNAPSHOT -> 1.2.3
mvn propagate:bump -Dbump.part=patch -Dbump.snapshot=true       # 1.2.3 -> 1.2.4-SNAPSHOT
mvn propagate:bump -Dbump.part=minor -Dbump.qualifier=RC1       # 1.2.3 -> 1.3.0-RC1
mvn propagate:bump -Dbump.newVersion=2.0.0-beta.1               # any valid version
mvn propagate:bump -Dbump.part=patch -Dbump.dryRun=true         # plan only, nothing written
```

Without a declaration in the POM, use the full coordinates
(`mvn io.github.eliaschacon:version-propagator-maven-plugin:1.1.1-SNAPSHOT:bump -Dbump.part=patch`) and pass the
files on the command line (`-Dbump.updateFiles=true -Dbump.files=README.md "-Dbump.includes=**/*.yaml"`).
A value configured in the POM always wins over the matching `-Dbump.*` property.

Every file is reported: `[update]`/`[would update]` with the number of replacements, `[no match]`, `[no rule]`,
`[unchanged]` or `[skipped] (reason)`.

## Hook: `release:update-versions` and `versions:set`

Declare the plugin with `<extensions>true</extensions>` (plugin 1.1.0+). After a monitored goal changes the project
versions, the new version is propagated to the configured files (`files`, `includes`, `<replacement>` scopes) with the
same rules and checks as `bump`. The POMs are left to the goal that changed them.

```text
$ mvn --batch-mode release:update-versions -DdevelopmentVersion=1.2.0-SNAPSHOT
[INFO] BUILD SUCCESS
[INFO] [version-propagator] Propagating 1.0.0-SNAPSHOT -> 1.2.0-SNAPSHOT to the configured files
[INFO] [version-propagator] [update] README.md (1 replacement(s), 1.0.0-SNAPSHOT -> 1.2.0-SNAPSHOT)
```

- Works with `mvn release:update-versions` (also `-DautoVersionSubmodules=true` or `-DdevelopmentVersion=...`) and
  `mvn versions:set -DnewVersion=...`. Other goals are set with `hookGoals`; any spelling of a goal matches.
- The versions of all reactor projects are recorded before the goals run and compared with the POMs on disk when the
  session ends. Modules with different versions are supported; chained or conflicting changes are rejected.
- Only configured files are changed: with none configured, the hook only logs a warning.
- `-Dbump.dryRun=true` shows the plan, `-Dbump.hook.skip=true` disables the hook. Nothing happens for other goals or
  when the build failed.
- The hook runs after Maven's `BUILD SUCCESS` summary. If it fails, Maven exits with code 1; the POMs already hold the
  new version, so fix the files or the configuration and run `mvn propagate:sync -Dbump.oldVersion=<previous>`.

## Sync goal and `release:prepare`

`propagate:sync` propagates the version already in the POMs without changing them. Outside `release:prepare` the
previous version must be given (the hook covers `release:update-versions` and `versions:set` without it):

```bash
mvn propagate:sync -Dbump.oldVersion=1.9.0      # after the POMs were changed by hand or by another tool
```

`release:prepare` commits and tags inside the goal and commits only the POMs, so the hook cannot act there. Add `sync`
to the release plugin goals, followed by `scm:checkin` (maven-scm-plugin, same `<scm>` as the release) for the
propagated files. Inside `release:prepare` the previous version is read from `release.properties` and
`pom.xml.releaseBackup`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-release-plugin</artifactId>
    <configuration>
        <preparationGoals>clean verify propagate:sync scm:checkin</preparationGoals>
        <completionGoals>propagate:sync scm:checkin</completionGoals>
        <arguments>-Dincludes=README.md,config/app.properties -Dmessage=Propagate-version -DpushChanges=false</arguments>
    </configuration>
</plugin>
```

- `includes` lists the propagated files (comma-separated, relative to the root); only they are committed, before
  the release commit, so the tag holds them with the release version and the next commit with the development version.
- `pushChanges=false` leaves pushing to the release plugin (its own `pushChanges`); the message must not contain spaces
  inside `<arguments>`.
- `release:prepare -DdryRun=true` changes and commits nothing.

## Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `part` | `bump.part` | — | `major`, `minor`, `patch`, `build` or `release` (drop `-SNAPSHOT`). Required unless `newVersion` is set. |
| `newVersion` | `bump.newVersion` | — | Explicit next version. Exclusive with `part`, `snapshot` and `qualifier`. |
| `snapshot` | `bump.snapshot` | keep | With `part`: `true` adds `-SNAPSHOT`, `false` drops it. |
| `qualifier` | `bump.qualifier` | keep | With `part`: sets or replaces the qualifier (`RC1`, `beta.2`); `none` removes it. |
| `qualifierPolicy` | `bump.qualifierPolicy` | `fail` | Existing qualifier when `qualifier` is not set: `fail`, `preserve` or `remove`. |
| `updatePom` | `bump.updatePom` | `true` | Update the execution-root POM. |
| `updateModules` | `bump.updateModules` | `true` | Update reactor modules too (see [Multi-module reactors](#multi-module-reactors)). |
| `updateFiles` | `bump.updateFiles` | `false` | Update the configured files (`bump` only; `sync` and the hook always do). |
| `files` | `bump.files` | — | Specific files, always processed. |
| `includes` / `excludes` | `bump.includes` / `bump.excludes` | — | Globs relative to each scanned directory, e.g. `**/*.yaml`. |
| `directories` | `bump.directories` | base dir | Directories scanned with `includes`. |
| `useDefaultExcludes` | `bump.useDefaultExcludes` | `true` | Exclude `**/target/**`, `.git`, `.svn`, `.hg`. |
| `replacements` | POM only | default rule | Custom replacement rules (see [File replacement](#file-replacement)). |
| `encoding` | `bump.encoding` | `UTF-8` | Encoding of the files (POMs use their XML declaration). |
| `failOnNoMatch` | `bump.failOnNoMatch` | `false` | Fail if no configured file contains the old version. |
| `createBackup` / `backupSuffix` | `bump.createBackup` / `bump.backupSuffix` | `false` / `.bak` | Write `<file>.bak` (POMs included) before changing a file. |
| `overwriteBackups` | `bump.overwriteBackups` | `false` | Allow replacing existing backups. |
| `followSymlinks` | `bump.followSymlinks` | `false` | Follow symbolic links (targets must be inside the project). |
| `dryRun` | `bump.dryRun` | `false` | Show the plan, write nothing. |
| `skip` | `bump.skip` | `false` | Skip the goal. |
| `hookGoals` | POM only | `release:update-versions`, `versions:set` | Goals after which the hook propagates. |
| `hookSkip` | `bump.hook.skip` | `false` | Disable the hook. |
| `oldVersion` (sync) | `bump.oldVersion` | — | Previous version; detected only inside `release:prepare`. |

List parameters take comma-separated values on the command line (`-Dbump.includes=a,b`) or child elements in the POM.

## Version rules

Accepted: `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH.BUILD`, optionally followed by `-QUALIFIER` and/or `-SNAPSHOT`
(non-negative integers, no leading zeros).

| Part | `1.2.3` | `1.2.3.4` |
|---|---|---|
| major | `2.0.0` | `2.0.0.0` |
| minor | `1.3.0` | `1.3.0.0` |
| patch | `1.2.4` | `1.2.4.0` |
| build | `1.2.3.1` (BUILD added) | `1.2.3.5` |
| release | from `1.2.3-SNAPSHOT`: `1.2.3` (fails if not a SNAPSHOT) | |

- `-SNAPSHOT` is kept unless `release` or `snapshot=false` is used.
- Order: increment, then `qualifier`, then `snapshot` (`minor` + `RC1` + `snapshot=true`: `1.2.3` -> `1.3.0-RC1-SNAPSHOT`).
- An existing qualifier (`-RC1`) is rejected by default; `qualifierPolicy=preserve|remove` keeps or drops it.
- Rejected: `1.0`, `01.2.3`, `1.2.3.4.5`, `1.2.3.RELEASE`, `v1.2.3`, `${revision}`, overflows, a result equal to the
  current version. The error shows the value and the accepted formats.

## POM update

- Parsed with the JDK SAX parser (XXE-safe; malformed XML is reported with line and column). Only the characters of
  `<project><version>` (and `<parent><version>` in modules) are replaced; comments, whitespace, line endings,
  encoding and the XML declaration stay byte for byte. The result is parsed again before it is written.
- Dependency, plugin, property and `dependencyManagement` versions are never changed.
- Rejected with a hint: an inherited version (no `<version>`; use the parent or `-Dbump.updatePom=false`), a property
  (`${revision}`), a value with entities or CDATA, or a literal version different from Maven's effective version.

### Multi-module reactors

The goal is an aggregator: it runs once, on the execution root. With `updateModules=true`, a module is changed only if
its `<parent>` is a bumped project with the old version: the parent reference is updated, and its own `<version>` too
when equal to the old version (transitively). Other modules are left unchanged; `-pl`/`-N` narrow the reactor.

## File replacement

- Files come from `files` plus a scan of `directories` filtered by `includes`/`excludes` (portable globs `*`, `?`,
  `**`, case-sensitive). All paths must be inside the project; missing ones fail before anything is written.
- Never touched: `target/` and VCS directories, symbolic links (unless `followSymlinks`), binary files, files not valid
  in `encoding`, reactor POMs and backup files. Untouched content (BOM, line endings) is kept byte for byte.

**Default rule.** The complete old version only, so `1.2.3` is replaced in `v1.2.3`, `app-1.2.3.jar`, `"1.2.3"`, but
not in `11.2.30`, `1.2.30`, `1.2.3.4`, `1.2.3-SNAPSHOT` or `1.2.3-RC1`.

**Custom rules.** Each `<replacement>` has a `search` (Java regex) and a `replace` (`$1` = group 1); rules run in order
and replace the default rule (an empty `<replacement/>` adds it back). Tokens: `oldVersion`, `newVersion`,
`old|newMajor`, `Minor`, `Patch`, `Build`, written `@name@` (or `$${name}`): Maven interpolates `${...}` in the POM.
Token values are regex-quoted in `search`.

**Per-file rules.** A rule with `<files>` or `<includes>` (relative to the base directory) applies only to those files
and selects them automatically; `<excludes>` removes files. Without `<search>`, the rule uses the default search.

```xml
<replacements>
    <replacement>                                   <!-- custom pattern, one file -->
        <files><file>config/application.yaml</file></files>
        <search>(image: acme/app:)@oldVersion@</search>
        <replace>$1@newVersion@</replace>
    </replacement>
    <replacement>                                   <!-- default search, docs except docs/old -->
        <includes><include>docs/**/*.md</include></includes>
        <excludes><exclude>docs/old/**</exclude></excludes>
    </replacement>
</replacements>
```

## Safety

- **Plan first:** every parameter and precondition is checked and all changes are computed before the first write
  (files unchanged since planning, writable, backups absent, `failOnNoMatch`).
- **Atomic writes:** a temporary sibling replaces each file with an atomic move (POSIX permissions kept); on Windows
  the move is retried for about 2 s while another process holds the file.
- **Rollback:** if a write fails, the files already written are restored.

## Known limitations

- CI-friendly (`${revision}`) and inherited versions are not edited in the POM; two-component versions (`1.0`) are
  not supported.
- Literal versions of sibling modules in `<dependencies>` are not updated (use `${project.version}`), nor are modules
  outside the reactor.
- The default rule skips classifier names such as `app-1.2.3-sources.jar` (`-sources` looks like a qualifier): add a
  custom rule. Globs do not support `[...]` or `{a,b}`. Binary detection is a heuristic (NUL in the first 8 KiB).
- The hook needs `<extensions>true</extensions>` and does not cover `release:prepare` (use `sync`). A project cannot
  declare the plugin with extensions at its own version (cyclic reference): this only affects the plugin's own build.

## Development

### Build and test

- Maven on JDK 11+. `~/.m2/toolchains.xml` (not in the repository; `setup-java` writes it in CI):

  | Maven runs on | Toolchains | Command |
  |---|---|---|
  | JDK 21+ | none | `mvn clean verify -Dtests.legacyJdks.skip=true` |
  | JDK 21+ | `1.8`, `11`, `17` | `mvn clean verify` (tests on 21, 8, 11 and 17) |
  | JDK 11-20 | `21` + `1.8`, `11`, `17` | `mvn clean verify` (profile `maven-jvm-below-21` uses the 21 toolchain) |

- `mvn.bat` / `mvn.sh` load `.env` (`JAVA_HOME`, `MAVEN_HOME`, `MAVEN_SETTINGS`); plain `mvn` works the same.
- `verify` runs the unit tests (temporary directories only) on every JVM, the `TaskRunner` contract test on both
  implementations, `MultiReleaseJarIT` on the packaged JAR per JVM, and JaCoCo (aggregated in
  `plugin/target/site/jacoco-aggregate`).

### Modules

```
pom.xml   parent: versions, plugin management, JaCoCo, test runs per JVM, profiles sonar / bump / hook
common/   release 8: version rules, POM parsing/editing, file replacement, BumpService, TaskRunner contract
jdk8/     release 8: PlatformTaskRunner, sequential (base of the Multi-Release JAR)
jdk21/    release 21: PlatformTaskRunner, virtual threads (META-INF/versions/21)
plugin/   published artifact: goals bump and sync, lifecycle hook, assembly of the Multi-Release JAR
```

No `jdk11`/`jdk17` modules: the code only uses `java.base` and `java.xml` with no internal, removed or deprecated APIs,
so the Java 8 base runs unchanged on 11 and 17 (verified by the test runs). Only the parent POM and the plugin are
published; the internal modules are embedded and declared `provided`.

### CI and release

- `ci.yml`: pushes and pull requests to `main`; JDKs 8, 11, 17, 21; `mvn -B clean verify`; coverage badges (committed
  on `main`) and Codecov.
- `release.yml`: tag `v<version>` equal to a non-SNAPSHOT POM version; `mvn -B deploy` to GitHub Packages.
- `bump.yml`: manual version bump of this project (dry run by default), committed with the workflow token.

### Bumping this project

The project uses the plugin published on GitHub Packages (profile `bump`), so the five POMs and `README.md` change
together:

```bash
bump.bat -Dbump.part=patch -Dbump.dryRun=true      # or ./bump.sh
# = mvn -s .mvn/bump-settings.xml -Pbump propagate:bump -Dbump.part=patch
mvn -s .mvn/bump-settings.xml -Pbump,hook release:update-versions -DdevelopmentVersion=X.Y.Z-SNAPSHOT
```

- In `README.md` only these patterns change: `message=vX.Y.Z` (badge),
  `io.github.eliaschacon:version-propagator-maven-plugin:X.Y.Z` and `propagate:X.Y.Z`. Keep other examples
  version-neutral.
- GitHub Packages needs a token even to read: set `GITHUB_ACTOR` and `GITHUB_TOKEN` (`read:packages`).
  `.mvn/bump-settings.xml` has no mirror, so a corporate `mirrorOf=*` cannot redirect the `github` repository.
- The plugin version in the `bump` and `hook` profiles is literal (`maven-release-plugin` fails on an expression) and
  must differ from the project version when `hook` is active. Until a release with the 1.1.0 options is published,
  run the locally installed plugin with its full coordinates.

### Releasing this project

```bash
release.bat --help                   # or ./release.sh; full usage
release.bat 1.2.0 --dry-run          # checks and shows the changes only
release.bat 1.2.0                    # next version: 1.2.1-SNAPSHOT
release.bat v1.2.0 1.3.0-SNAPSHOT -y # explicit next version, no confirmation
```

1. Checks: on a branch, clean working tree, tag `v<version>` absent locally and on `origin`, branch not behind
   `origin`. Nothing changes before they pass.
2. Builds and installs the working tree (`install -DskipTests`; tests run in `release.yml`) and runs that plugin
   offline with the `bump` profile: no GitHub token needed.
3. Sets the release version, commits `Release X`, creates the annotated tag `vX` and, after confirmation, pushes the
   branch and the tag with `git push --atomic` (the tag starts `release.yml`).
4. Sets the next SNAPSHOT, commits `Prepare next development version N` and pushes the branch.

If the release push fails, nothing reaches `origin`; the script prints how to undo the local commit and tag. The
literal plugin version of the `bump` and `hook` profiles is not changed by the script.

### SonarQube

```powershell
& "$env:MAVEN_HOME\bin\mvn.cmd" -s <settings.xml> -Psonar clean verify sonar:sonar `
    "-Dsonar.host.url=http://localhost:9000" "-Dsonar.token=$env:SONAR_TOKEN"
```

Maven on JDK 17+. Call `mvn.cmd` directly (the `mvn.bat` wrapper echoes the token). Project key
`io.github.eliaschacon:version-propagator-parent`; do not set `sonar.projectKey` (the scanner fails in multi-module
builds). Pass `-D` values when a corporate `settings.xml` sets the Sonar properties.

### Technical decisions

- **Multi-Release JAR:** Java 8 bytecode; Java 21 code behind the `TaskRunner` contract (results and failures in task
  order). `requiredJavaVersion` is set to 1.8 explicitly, otherwise it is derived from the release 21 module.
- **SAX instead of DOM:** DOM has no source positions and writing it back rewrites line endings, the XML declaration
  and character references. Lone CRs are normalised before reading positions (the JDK parser miscounts after them).
- **Core free of the Maven API** (except logging): the goals only map parameters to a `BumpRequest`.
- **Rules replace the default** and **per-file rules select their files**, so the configuration shows exactly what
  runs.
- **Lombok** at compile time only (`@Data`, `@Builder`); `lombok.config` marks generated code for JaCoCo and Sonar.
