// sbt 2 builds of both plugins publish under the `_sbt2_3` suffix.
// Note: in sbt 2, cross-built deps use plain `%%` — `%%%` (sbt-platform-deps)
// is gone and has no sbt 2 build.
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")

// Publishing to Maven Central via the Central Portal. Sonatype sunset the
// legacy OSSRH endpoint on 2025-06-30, so 1.11.0+ is required.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.0")
