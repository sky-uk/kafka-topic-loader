import xerial.sbt.Sonatype._

import ReleaseTransformations._

// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "uk.sky"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// Open-source license of your choice
licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

sonatypeProjectHosting := Some(GitHubHosting("sky-uk", "kafka-topic-loader", "user@example.com"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ =>
  false
}

releaseCrossBuild := true // true if you cross-build the project for multiple Scala versions

autoAPIMappings := true

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
