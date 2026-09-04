ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "1.0.0"
ThisBuild / organization := "snap"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Suites named `*SlowSuite` (e.g. Θ(n²)-replay stack-safety depth probes, T16) are phase-gate
// material, not per-task material: excluded from the default `test` task below, run explicitly
// with `sbt slowTest`.
addCommandAlias("slowTest", "testOnly *SlowSuite")

lazy val root = (project in file("."))
  .settings(
    name := "snap",
    Compile / mainClass := Some("Main"),
    assembly / mainClass := Some("Main"),
    // Default sbt-assembly jar name (<name>-assembly-<version>.jar under
    // target/scala-<v>/) already matches the layout snap/run's artifact-discovery
    // glob expects (SPEC-NOTES §3.1) — kept implicit rather than overridden.
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      // PR8: warnings are promoted to errors so a missing case in the 60+-case `SnapError.message`
      // match (or any other exhaustiveness gap) fails the build instead of shipping as a silent
      // `MatchError` at runtime (phase-1 review verified zero warnings under this flag).
      "-Werror"
    ),
    libraryDependencies ++= Seq(
      // Runtime (DESIGN D2): JSON tokenizer under our own AST.
      "org.typelevel" %% "jawn-parser" % "1.7.0",
      // Test only (DESIGN D3): excluded from the assembly.
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.3.1" % Test,
      "org.scalacheck" %% "scalacheck" % "1.20.0" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    // Keeps `*SlowSuite`s out of the default `test` task (see `slowTest` alias above) without also
    // hiding them from `testOnly`/`slowTest`: scoped to the `test` task specifically rather than
    // `Test / testOptions` (config-scoped), which `testOnly` shares and would filter too.
    Test / test / testOptions += Tests.Filter(name => !name.endsWith("SlowSuite"))
  )
