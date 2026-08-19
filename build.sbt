import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val scala3Version     = "3.9.0-RC6"
val ironVersion       = "3.3.2"
val circeVersion      = "0.14.16"
val catsEffectVersion = "3.7.0"
val http4sVersion     = "0.23.36"
val munitVersion      = "1.3.5"
val munitCeVersion    = "2.2.0"

// Publishing metadata. sbt-ci-release forbids `version`, `publishTo`,
// `publishMavenStyle` and `credentials` here: the version comes from the git
// tag via sbt-dynver, and the destination from the plugin.
inThisBuild(
  List(
    organization := "io.github.danbills",
    homepage     := Some(uri("https://github.com/danbills/iron-mcp")),
    licenses     := List("MIT" -> uri("https://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        id = "danbills",
        name = "Dan Billings",
        email = "dan@megamote.com",
        url = uri("https://github.com/danbills")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        uri("https://github.com/danbills/iron-mcp"),
        "scm:git:https://github.com/danbills/iron-mcp.git",
        Some("scm:git:git@github.com:danbills/iron-mcp.git")
      )
    ),
    versionScheme := Some("early-semver")
  )
)

ThisBuild / scalaVersion := scala3Version

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard"
)

// No reflection anywhere in this build: every codec is derived at compile time
// from a Mirror, so the whole thing survives Scala Native and GraalVM
// native-image with no reachability metadata.
lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name        := "iron-mcp-core",
    description := "A Model Context Protocol server for Scala 3 — protocol revision 2026-07-28, " +
      "with Iron refinement types, compile-time derived JSON Schema, and no reflection.",
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron"              % ironVersion,
      "io.github.iltotore" %% "iron-circe"        % ironVersion,
      "io.circe"           %% "circe-core"        % circeVersion,
      "io.circe"           %% "circe-parser"      % circeVersion,
      "org.typelevel"      %% "cats-effect"       % catsEffectVersion,
      "co.fs2"             %% "fs2-io"            % "3.13.0",
      "org.scalameta"      %% "munit"             % munitVersion   % Test,
      "org.typelevel"      %% "munit-cats-effect" % munitCeVersion % Test
    )
  )

lazy val demo = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/demo"))
  .dependsOn(core)
  .settings(
    name := "iron-mcp-demo",
    Compile / mainClass := Some("ironmcp.demo.Main"),
    publish / skip := true
  )

lazy val root = (project in file("."))
  .aggregate(core.jvm, core.native, demo.jvm, demo.native)
  .settings(name := "iron-mcp", publish / skip := true)
