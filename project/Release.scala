import sbt.Keys._
import sbt.taskKey
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease._

object Release {

  lazy val releaseSettings = Seq(
    releaseUseGlobalVersion := false,
    releaseVersionBump := sbtrelease.Version.Bump.Minor,
    releaseTagName := s"${name.value}-${version.value}",
    releaseTagComment := s"Releasing ${version.value} of module: ${name.value}",
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      releaseStepCommand(ExtraReleaseCommands.initialVcsChecksCommand),
      inquireVersions,
      setReleaseVersion,
      runClean,
      runTest,
      commitReleaseVersion,
      tagRelease,
      ReleaseStep(releaseStepTask(publish)),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )
}
