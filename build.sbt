import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val scala3Version     = "3.8.3"
val ironVersion       = "3.3.2"
val circeVersion      = "0.14.16"
val catsEffectVersion = "3.7.0"
val http4sVersion     = "0.23.36"
val munitVersion      = "1.3.5"
val munitCeVersion    = "2.2.0"

ThisBuild / organization := "com.megamote"
ThisBuild / version      := "0.1.0-SNAPSHOT"
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
    name := "iron-mcp-core",
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

lazy val http4s = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/http4s"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "iron-mcp-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core"         % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion % Test
    )
  )

lazy val demo = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/demo"))
  .dependsOn(http4s)
  .settings(
    name := "iron-mcp-demo",
    Compile / mainClass := Some("ironmcp.demo.Main")
  )
  .nativeSettings(
    nativeConfig ~= { _.withLTO(scala.scalanative.build.LTO.thin) }
  )

lazy val root = (project in file("."))
  .aggregate(core.jvm, core.native, http4s.jvm, http4s.native, demo.jvm, demo.native)
  .settings(name := "iron-mcp", publish / skip := true)
