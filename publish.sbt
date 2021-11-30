import ReleaseTransformations._

// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "uk.sky"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// Open-source license of your choice
licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

homepage := Some(url("https://github.com/sky-uk/kafka-topic-loader"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/sky-uk/kafka-topic-loader"),
    "scm:git@github.com:sky-uk/kafka-topic-loader.git"
  )
)
developers := List(
  Developer(id = "bcarter97",
            name = "Ben Carter",
            email = "ben.carter@sky.uk",
            url = url("https://github.com/bcarter97"))
)

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
