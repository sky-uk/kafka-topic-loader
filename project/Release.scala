import sbt.Keys._
import sbtrelease.ReleasePlugin.autoImport._

object Release {

  lazy val releaseSettings = Seq(
    releaseUseGlobalVersion := false,
    releaseVersionBump := sbtrelease.Version.Bump.Minor,
    releaseTagName := s"${name.value}-${version.value}",
    releaseTagComment := s"Releasing ${version.value} of module: ${name.value}")
}
