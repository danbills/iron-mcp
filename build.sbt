val scala3Version    = "3.8.3"
val ironVersion      = "3.3.1"
val circeVersion     = "0.14.15"
val catsEffectVersion = "3.7.0"
val http4sVersion    = "0.23.32"
val mcpVersion       = "2.0.1"
val munitVersion     = "1.2.0"
val munitCeVersion   = "2.1.0"

ThisBuild / organization := "com.megamote"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3Version

lazy val root = (project in file("."))
  .settings(
    name := "iron-mcp",
    libraryDependencies ++= Seq(
      // Official MCP Java SDK. `mcp` is the facade: mcp-core + mcp-json-jackson3.
      "io.modelcontextprotocol.sdk" % "mcp"                  % mcpVersion,
      "io.github.iltotore"         %% "iron"                 % ironVersion,
      "io.github.iltotore"         %% "iron-circe"           % ironVersion,
      "io.circe"                   %% "circe-core"           % circeVersion,
      "io.circe"                   %% "circe-parser"         % circeVersion,
      "io.circe"                   %% "circe-generic"        % circeVersion,
      "org.typelevel"              %% "cats-effect"          % catsEffectVersion,
      "org.http4s"                 %% "http4s-ember-server"  % http4sVersion,
      "org.http4s"                 %% "http4s-dsl"           % http4sVersion,
      "org.http4s"                 %% "http4s-circe"         % http4sVersion,
      "org.scalameta"              %% "munit"                % munitVersion   % Test,
      "org.typelevel"              %% "munit-cats-effect"    % munitCeVersion % Test,
      "org.http4s"                 %% "http4s-ember-client"  % http4sVersion  % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all")
  )
