# Building the LLM Extension

## Prerequisites

- **Java 21** (required for building; NetLogo 7.0.4 is compiled for Java 21)
- **SBT 1.10.1** (Scala Build Tool)

## Run Tests

```bash
# Automated core extension tests (deterministic, no API/network required)
sbt test
```

What this validates:
- primitive wiring and behavior via NetLogo headless language tests
- configuration, chat/history flows, templates, and multi-agent isolation

What this does not validate:
- live provider API/auth/network behavior (use `demos/tests/tests.nlogox` for manual integration checks)

## Build Commands

### For Development (Fat JAR)

```bash
# Set Java 21 (NetLogo 7.0.4 is compiled for Java 21)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Build single JAR with all dependencies
sbt clean assembly
```

**Output:** `target/scala-3.7.0/llm.jar` (172MB)

### For Distribution (ZIP Package)

```bash
# Set Java 21 (NetLogo 7.0.4 is compiled for Java 21)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Build distribution package
sbt clean packageZip
```

**Output:** `llm-0.1.0.zip` (11MB)

## Build Outputs Explained

### Fat JAR (assembly)
- **Location**: `target/scala-3.7.0/llm.jar`
- **Size**: ~172MB
- **Type**: Single JAR with all dependencies bundled inside
- **Use**: Local development, simple installation
- **Install**: Copy one file to NetLogo extensions

### ZIP Package (packageZip)
- **Location**: `llm-0.1.0.zip` (project root)
- **Size**: ~11MB
- **Type**: Multiple JARs + documentation
- **Use**: Official releases, NetLogo Extension Library
- **Install**: Extract entire folder to NetLogo extensions

## Which Build Should I Use?

| Purpose | Command | Output |
|---------|---------|--------|
| **Testing locally** | `sbt assembly` | Fat JAR (172MB) |
| **Quick install** | `sbt assembly` | Fat JAR (172MB) |
| **Official release** | `sbt packageZip` | ZIP (11MB) |
| **Publish to library** | `sbt packageZip` | ZIP (11MB) |

## Common Issues

**Problem**: Build fails with SBT parser errors
**Solution**: Use the SBT version pinned in `project/build.properties`:
```
sbt.version=1.10.1
```

**Problem**: Build fails with Java version errors
**Solution**: Make sure you're using Java 21:
```bash
java -version  # Should show 21.x.x or newer
```

**Problem**: Old JAR still being used
**Solution**: Always use `clean` before building:
```bash
sbt clean assembly  # Not just 'assembly'
```
