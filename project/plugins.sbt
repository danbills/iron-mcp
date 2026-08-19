// Both plugins publish for sbt 2 under the `_sbt2_3` suffix.
// Note: in sbt 2, cross-built deps use plain `%%` — `%%%` (sbt-platform-deps)
// is gone and has no sbt 2 build.
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")

// Releases are cut locally, not from CI, so there is no sbt-ci-release here —
// only the two pieces it would have bundled. Uploading to the Central Portal
// is built into sbt 2 itself (`sonaUpload` / `sonaRelease`).
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")  // version from the git tag
addSbtPlugin("com.github.sbt" % "sbt-pgp"    % "2.3.1")  // publishSigned
