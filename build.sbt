import Dependencies.all

lazy val scala3                 = "3.1.1"
lazy val scala213               = "2.13.8"
lazy val scala212               = "2.12.15"
lazy val supportedScalaVersions = List(scala3, scala213, scala212)
lazy val scmUrl                 = "https://github.com/sky-uk/kafka-topic-loader"

name                   := "kafka-topic-loader"
organization           := "uk.sky"
description            := "Reads the contents of provided Kafka topics"
sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository     := "https://s01.oss.sonatype.org/service/local"
homepage               := Some(url(scmUrl))
licenses               := List("BSD New" -> url("https://opensource.org/licenses/BSD-3-Clause"))
developers             := List(
  Developer(
    "Sky UK OSS",
    "Sky UK OSS",
    sys.env.getOrElse("SONATYPE_EMAIL", scmUrl),
    url(scmUrl)
  )
)

scalaVersion       := scala3
crossScalaVersions := supportedScalaVersions
semanticdbEnabled  := true
semanticdbVersion  := scalafixSemanticdb.revision

tpolecatScalacOptions ++= Set(ScalacOptions.source3)

ThisBuild / scalacOptions ++= Seq("-explaintypes") ++ {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) | Some((2, 13)) => Seq("-Wconf:msg=annotation:silent")
    case _                            => Nil
  }
}

ThisBuild / scalafixDependencies += Dependencies.Plugins.organizeImports

scalafixConfig           := {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Some((ThisBuild / baseDirectory).value / ".scalafix3.conf")
    case _            => None
  }
}

Test / parallelExecution := false
Test / fork              := true

Global / onChangedBuildSource := ReloadOnSourceChanges

libraryDependencies ++= all

excludeDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Dependencies.scala3Exclusions
    case _            => Seq.empty
  }
}

addCommandAlias("checkFix", "scalafixAll --check OrganizeImports; scalafixAll --check")
addCommandAlias("runFix", "scalafixAll OrganizeImports; scalafixAll")
addCommandAlias("checkFmt", "scalafmtCheckAll; scalafmtSbtCheck")
addCommandAlias("runFmt", "scalafmtAll; scalafmtSbt")

addCommandAlias("ciBuild", "checkFmt; checkFix; +test")
