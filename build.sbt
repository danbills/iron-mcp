import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val scala3Version     = "3.9.0"
val ironVersion       = "3.3.2"
val circeVersion      = "0.14.16"
val catsEffectVersion = "3.7.0"
val http4sVersion     = "0.23.37"
val otel4sVersion     = "1.1.0"
val munitVersion      = "1.3.5"
val munitCeVersion    = "2.2.0"
val fs2Version        = "3.13.0"

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
    versionScheme := Some("early-semver"),
    // sbt 2 uploads to the Sonatype Central Portal itself: `publishSigned`
    // writes a signed bundle into localStaging, then `sonaUpload` /
    // `sonaRelease` push it. Credentials come from SONATYPE_USERNAME /
    // SONATYPE_PASSWORD in the environment.
    publishTo := {
      val snapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if (isSnapshot.value) Some("central-snapshots".at(snapshots)) else localStaging.value
    }
  )
)

ThisBuild / scalaVersion := scala3Version

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror"
)

// No reflection anywhere in this build: every codec is derived at compile time
// from a Mirror, so the whole thing survives Scala Native and GraalVM
// native-image with no reachability metadata.

// The wire types (`ironmcp.protocol`) and the JSON Schema derivation
// (`ironmcp.schema`): circe and Iron only, so they also build for Scala.js,
// where MCP Apps Views (`appsView`) use the server's own types.
lazy val protocol = crossProject(JVMPlatform, NativePlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/protocol"))
  .settings(
    name        := "iron-mcp-protocol",
    description := "The Model Context Protocol's wire types (revision 2026-07-28) and JSON Schema derived from " +
      "Iron refinement types, for the JVM, Scala Native and Scala.js.",
    libraryDependencies ++= Seq(
      "io.github.iltotore" %% "iron"         % ironVersion,
      "io.github.iltotore" %% "iron-circe"   % ironVersion,
      "io.circe"           %% "circe-core"   % circeVersion,
      "io.circe"           %% "circe-parser" % circeVersion,
      "org.scalameta"      %% "munit"        % munitVersion % Test
    )
  )

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .dependsOn(protocol)
  .settings(
    name        := "iron-mcp-core",
    description := "A Model Context Protocol server for Scala 3 — protocol revision 2026-07-28, " +
      "with Iron refinement types, compile-time derived JSON Schema, and no reflection.",
    libraryDependencies ++= Seq(
      "org.typelevel"      %% "cats-effect"       % catsEffectVersion,
      "co.fs2"             %% "fs2-io"            % fs2Version,
      "org.scalameta"      %% "munit"             % munitVersion   % Test,
      "org.typelevel"      %% "munit-cats-effect" % munitCeVersion % Test
    )
  )

lazy val mcpbTools = settingKey[Seq[Mcpb.Tool]]("The tools an MCP Bundle's manifest lists; checked against the linked server")
lazy val mcpb      = taskKey[File]("Links the server and packs it as an MCP Bundle (.mcpb, manifest 0.3)")

lazy val demo = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/demo"))
  .dependsOn(core)
  .settings(
    name := "iron-mcp-demo",
    Compile / mainClass := Some("ironmcp.demo.Main"),
    publish / skip := true
  )
  .nativeSettings(
    // `demoNative/mcpb`: the native binary as a one-file MCP Bundle (Handbook §5.5).
    mcpbTools := Seq(
      Mcpb.Tool("greet", "Greet someone, one to ten times."),
      Mcpb.Tool("long_operation", "Simulates work for a few seconds, reporting progress each second.")
    ),
    mcpb := Def.uncached {
      val binary = fileConverter.value.toPath((Compile / nativeLink).value)
      val manifest = Mcpb.Manifest(
        name = "iron-mcp-demo",
        displayName = "iron-mcp demo",
        version = version.value,
        description = "The iron-mcp demo server: typed tools with schemas derived from Iron refinement types.",
        author = "Dan Billings",
        license = "MIT",
        homepage = homepage.value.map(_.toString),
        binaryName = "iron-mcp-demo",
        tools = mcpbTools.value
      )
      val out = target.value / s"iron-mcp-demo-${Mcpb.semver(version.value)}.mcpb"
      Mcpb.pack(manifest, binary, target.value / "mcpb", out)
      streams.value.log.info(s"MCP Bundle: $out")
      out
    }
  )

// Streamable HTTP. JVM-only: http4s ember does not run on Scala Native.
lazy val http = (project in file("modules/http"))
  .dependsOn(core.jvm)
  .settings(
    name        := "iron-mcp-http",
    description := "Streamable HTTP (revision 2026-07-28, stateless) for iron-mcp, server and client, on http4s ember.",
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-ember-client" % http4sVersion,
      "org.scalameta" %% "munit"               % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect"   % munitCeVersion % Test
    )
  )

lazy val otel = (project in file("modules/otel"))
  .dependsOn(core.jvm, http)
  .settings(
    name        := "iron-mcp-otel",
    description := "OpenTelemetry instrumentation for iron-mcp, adhering to MCP GenAI semantic conventions, with W3C trace propagation, error ladder metrics, and cost histograms.",
    libraryDependencies ++= Seq(
      "org.typelevel"    %% "otel4s-core"               % otel4sVersion,
      "org.typelevel"    %% "otel4s-oteljava"           % otel4sVersion,
      "io.opentelemetry" %  "opentelemetry-sdk-testing" % "1.64.0"       % Test,
      "org.scalameta"    %% "munit"                     % munitVersion   % Test,
      "org.typelevel"    %% "munit-cats-effect"         % munitCeVersion % Test
    )
  )

lazy val tasks = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/tasks"))
  .dependsOn(core)
  .settings(
    name        := "iron-mcp-tasks",
    description := "The Tasks extension (io.modelcontextprotocol/tasks, SEP-2663) for iron-mcp, client and server.",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"             % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCeVersion % Test
    )
  )

// MCP Apps (io.modelcontextprotocol/ui): metadata and a View resource provider.
// Pure protocol, so it cross-builds like core.
lazy val apps = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/apps"))
  .dependsOn(core)
  .settings(
    name        := "iron-mcp-apps",
    description := "MCP Apps (io.modelcontextprotocol/ui) for iron-mcp: tools linked to ui:// HTML Views.",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"             % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCeVersion % Test
    )
  )

// MCP Apps Views in Scala.js: the View's side of the Apps protocol (JSON-RPC
// over postMessage with the host), on the same wire types as the server.
lazy val appsView = (project in file("modules/apps-view"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(protocol.js)
  .settings(
    name        := "iron-mcp-apps-view",
    description := "MCP Apps (io.modelcontextprotocol/ui) Views in Scala.js: the View side of the Apps protocol, on cats-effect and fs2.",
    libraryDependencies ++= Seq(
      "org.scala-js"  %% "scalajs-dom"       % "2.8.1",
      "io.circe"      %% "circe-scalajs"     % circeVersion,
      "org.typelevel" %% "cats-effect"       % catsEffectVersion,
      "co.fs2"        %% "fs2-core"          % fs2Version,
      "org.scalameta" %% "munit"             % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCeVersion % Test
    )
  )

lazy val auth = (project in file("modules/auth"))
  .dependsOn(core.jvm, http)
  .settings(
    name        := "iron-mcp-auth",
    description := "OAuth 2.1 and Step-Up authorization for iron-mcp (RFC 9728, RFC 8707, RFC 6750, SEP-2350) with Keycloak coordination.",
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-ember-client" % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      "org.http4s"    %% "http4s-dsl"          % http4sVersion  % Test,  // the fake authorization server
      "org.scalameta" %% "munit"               % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect"   % munitCeVersion % Test
    )
  )

lazy val docs = (project in file("modules/docs"))
  .dependsOn(core.jvm, http, auth, tasks.jvm, apps.jvm)
  .settings(
    name := "iron-mcp-docs",
    publish / skip := true,
    fork := true,
    libraryDependencies ++= Seq(
      "org.scalameta"  %% "mdoc"            % "2.9.2",
      "org.scala-lang" %% "scala3-compiler" % scala3Version
    ),
    dependencyOverrides += "org.scala-lang" %% "scala3-compiler" % scala3Version
  )

lazy val weather = (project in file("modules/weather"))
  .dependsOn(core.jvm)
  .settings(
    name := "iron-mcp-weather",
    Compile / mainClass := Some("ironmcp.weather.Main"),
    publish / skip := true,
    // A real-world example that calls out over HTTP, so it is JVM-only:
    // the ember client does not run on Scala Native.
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-client" % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      // Tools are programs over an algebra of the API's requests (see NwsOp).
      "org.typelevel" %% "cats-free"           % "2.13.0",
      "org.scalameta" %% "munit"               % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect"   % munitCeVersion % Test
    )
  )

lazy val nasa = (project in file("modules/nasa"))
  .dependsOn(core.jvm)
  .settings(
    name := "iron-mcp-nasa",
    Compile / mainClass := Some("ironmcp.nasa.Main"),
    publish / skip := true,
    // A real-world example that calls out over HTTP, so it is JVM-only:
    // the ember client does not run on Scala Native.
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-client" % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      // Tools are programs over an algebra of the API's requests (see ApodOp).
      "org.typelevel" %% "cats-free"           % "2.13.0",
      "org.scalameta" %% "munit"               % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect"   % munitCeVersion % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(
    protocol.jvm, protocol.native, protocol.js, core.jvm, core.native, demo.jvm, demo.native, http, weather, nasa, otel, auth,
    tasks.jvm, tasks.native, apps.jvm, apps.native, appsView
  )
  .settings(name := "iron-mcp", publish / skip := true)
