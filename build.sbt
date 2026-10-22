import sbt.nio.file.FileTreeView

enablePlugins(org.nlogo.build.NetLogoExtension)

name := "LLM-Extension"
version := "1.1.0"
isSnapshot := true

netLogoExtName      := "llm"
netLogoClassManager := "org.nlogo.extensions.llm.LLMExtension"
netLogoVersion      := "7.0.4"
netLogoShortDescription := "Multi-provider LLM extension for NetLogo agents"
netLogoLongDescription := "A NetLogo extension providing a unified interface to multiple LLM providers — OpenAI, Anthropic/Claude, Google/Gemini, local Ollama, OpenRouter, Together AI, and Groq — plus TypeSafe (Jev) for calibrated typed decisions, with per-agent conversation memory, synchronous, asynchronous and batched chat, prompt templates, constrained choices, typed decisions with probabilities, structured outputs, token and cost tracking, and reasoning/thinking-model support."
netLogoHomepage := "https://github.com/NetLogo/Netlogo-LLM-Extension"

// Package essential files in the zip distribution: docs, the config reference,
// and curated demo models. Demo models + their YAML templates are flattened
// directly into the `demos/` folder so the NetLogo Models Library can
// auto-discover them in the unzipped extension folder.
//
// The NetLogo extension plugin places each extra at its path relative to
// baseDirectory, so to land files flat under `demos/` we stage flat copies in
// `demos/` rather than referencing their subfolders. A missing extra fails the
// build instead of being silently dropped from the zip.
netLogoZipExtras := {
  val base = baseDirectory.value

  // Top-level extras kept at their existing relative paths.
  val topLevel = Seq(
    base / "docs" / "README.md",
    base / "demos" / "config-reference.txt"
  )

  // Curated demo files to flatten into `demos/`. Source -> flat target name.
  val demoSources = Seq(
    base / "demos" / "color-sharing" / "color-sharing.nlogox",
    base / "demos" / "social-deduction-game" / "social-deduction-game.nlogox",
    base / "demos" / "social-deduction-game" / "description-template.yaml",
    base / "demos" / "social-deduction-game" / "vote-template.yaml",
    base / "demos" / "topology-tournament" / "topology-tournament.nlogox",
    base / "demos" / "provider-sensitivity" / "provider-sensitivity.nlogox",
    base / "demos" / "el-farol-decisions" / "el-farol-decisions.nlogox"
  )

  val missing = (topLevel ++ demoSources).filterNot(_.exists())
  if (missing.nonEmpty)
    sys.error(s"netLogoZipExtras: missing files: ${missing.mkString(", ")}")

  val demosDir = base / "demos"
  val flattened = demoSources.map { src =>
    val dest = demosDir / src.getName
    IO.copyFile(src, dest)
    dest
  }

  topLevel ++ flattened
}

// Every bundled demo loads `demos/config.txt`. The developer's local copy may
// hold real API keys, so the zip gets the tracked placeholder instead: it is
// written into the zip after the plugin builds it.
packageZip := {
  val zip         = packageZip.value
  val placeholder = baseDirectory.value / "demos" / "config-placeholder.txt"
  if (!placeholder.exists())
    sys.error(s"missing $placeholder")
  val uri = java.net.URI.create("jar:" + zip.toURI)
  val fs  = java.nio.file.FileSystems.newFileSystem(uri, new java.util.HashMap[String, String]())
  try {
    val target = fs.getPath("demos", "config.txt")
    java.nio.file.Files.createDirectories(target.getParent)
    java.nio.file.Files.copy(placeholder.toPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  } finally fs.close()
  zip
}

scalaVersion          := "3.7.0"
Compile / scalaSource := baseDirectory.value / "src" / "main"
Test / scalaSource    := baseDirectory.value / "src" / "test"

// Ollama integration tests excluded from default `sbt test`.
// Run them with: OLLAMA_TESTS=true sbt test
// Or directly:   OLLAMA_TESTS=true sbt "testOnly org.nlogo.extensions.llm.OllamaIntegrationTests"
Test / testOptions += Tests.Filter { name =>
  if (name.contains("OllamaIntegration"))
    sys.env.contains("OLLAMA_TESTS")
  else
    true
}
scalacOptions        ++= Seq("-deprecation", "-unchecked", "-Xfatal-warnings", "-encoding", "utf-8", "-release", "17")

libraryDependencies ++= Seq(
  "io.circe" %% "circe-yaml" % "0.15.0",
  "com.lihaoyi" %% "ujson" % "4.0.0"
)

// NetLogo 7.0.4's tests jar is built against scalatest 3.2.19; the extension
// plugin injects 3.2.10, so pin the version NetLogo itself uses.
dependencyOverrides += "org.scalatest" %% "scalatest" % "3.2.19"

// netlogo-prims-json 0.1.1 (added by the extension plugin) depends on NetLogo
// 7.1.0-alpha1, which would otherwise evict 7.0.4 and make the extension
// compile against, and declare the API version of, an unreleased NetLogo.
dependencyOverrides += "org.nlogo" % "netlogo" % netLogoVersion.value

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
// OrganizeImports is a semantic rule, so `sbt scalafixAll` needs semanticdb output
ThisBuild / semanticdbEnabled := true

// Assembly configuration for fat JAR with all dependencies
assembly / assemblyMergeStrategy := {
  case "module-info.class" => MergeStrategy.discard
  case x if x.endsWith("/module-info.class") => MergeStrategy.discard
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

// Include Scala library in the assembly
assembly / assemblyOption := (assembly / assemblyOption).value.withIncludeScala(true)

// Output JAR with extension name (llm.jar)
assembly / assemblyJarName := s"${netLogoExtName.value}.jar"
