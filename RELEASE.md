# Release Process

Releases ship to users through the NetLogo Extension Manager, which reads
`libraries.conf` in [NetLogo/NetLogo-Libraries](https://github.com/NetLogo/NetLogo-Libraries).
A release is: a tagged commit here, a GitHub release with the zip, and a PR
to NetLogo-Libraries that adds the zip and bumps the version.

## Requirements

- **JDK 21 or newer.** NetLogo 7.0.4 is compiled for Java 21; JDK 17 fails with
  `UnsupportedClassVersionError` on `org.nlogo.api.APIVersion`.
- **sbt 1.10.1** (pinned in `project/build.properties`; NetLogo notes 1.10.2+ can
  hit a ZipException).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21+)
```

## 1. Set the version

In `build.sbt`:

```scala
version    := "1.1.0"
isSnapshot := false   // only for the release build; keep true while developing
```

With `isSnapshot := false` the plugin refuses to stage the extension from a
dirty working tree, so commit everything first.

Versioning is semantic: MAJOR for changes that break existing models, MINOR for
new primitives or providers, PATCH for fixes.

## 2. Build and check the zip

```bash
sbt clean test packageZip
scripts/check-zip.sh            # asserts the docs, config files and demos are inside
```

Output: `llm-<version>.zip` in the repo root (gitignored). It contains `llm.jar`,
its dependency jars, `docs/README.md`, `demos/config-reference.txt`, the curated
demos flattened into `demos/`, and `demos/config.txt` copied from the tracked
`demos/config-placeholder.txt` (never the developer's local config, which may
hold real keys). `sbt packageZip` also prints the `libraries.conf` entry to use.

Check the manifest says `NetLogo-Extension-API-Version: 7.0`:

```bash
unzip -p llm-<version>.zip llm.jar > /tmp/llm.jar && unzip -p /tmp/llm.jar META-INF/MANIFEST.MF
```

## 3. Test the release

1. `sbt test` (deterministic, no network) must be green; CI runs the same plus
   the zip check.
2. Live harness: open `demos/e2e-tests/e2e-tests.nlogox` with a local
   `config-<provider>.txt` (see the `.example` files) and run it against at least
   one cloud provider, Ollama, and TypeSafe (Jev).
3. Install the zip into a NetLogo 7.0.4 app
   (`<NetLogo 7.0.4>/extensions/llm/`), open a bundled demo, and run it.

## 4. Tag and publish on GitHub

```bash
git tag v<version>
git push upstream main v<version>
gh release create v<version> llm-<version>.zip -R NetLogo/Netlogo-LLM-Extension \
  --title "LLM Extension v<version>" --notes-file <notes.md>
```

## 5. Publish to the Extension Manager

1. Fork https://github.com/NetLogo/NetLogo-Libraries and branch from **`7.0`**
   (the branch NetLogo 7.0.x reads). Maintainers cherry-pick to `7.1`.
2. Add `extensions/llm-<version>.zip`.
3. In `libraries.conf`, update the `llm` entry's `version` (and descriptions if
   they changed) using the block `sbt packageZip` printed.
4. Open the PR against `7.0`. For reference, v1.0.0 was NetLogo-Libraries PR #87.

## Checklist

- [ ] `version` set, `isSnapshot := false`, tree committed
- [ ] `sbt clean test packageZip` green, `scripts/check-zip.sh` passes
- [ ] Manifest API version is 7.0
- [ ] Live e2e harness run (cloud provider, Ollama, Jev)
- [ ] Zip installed and a demo run in NetLogo 7.0.4
- [ ] Docs updated (`docs/API-REFERENCE.md`, `docs/CONFIGURATION.md`, `docs/PROVIDER-GUIDE.md`)
- [ ] Tag pushed, GitHub release published with the zip
- [ ] NetLogo-Libraries PR opened against `7.0`
- [ ] `isSnapshot := true` restored for development

## Development builds

```bash
sbt clean assembly     # fat jar at target/scala-3.7.0/llm.jar, for local installs
./build.sh             # same, plus install when NETLOGO_DIR is set
```
