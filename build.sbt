lazy val scala3                 = "3.3.1"
lazy val scala213               = "2.13.12"
lazy val supportedScalaVersions = List(scala3, scala213)
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

ThisBuild / scalacOptions ++= Seq("-explaintypes", "-Wconf:msg=annotation:silent")

ThisBuild / scalafixDependencies += Dependencies.Plugins.organizeImports

/** Scala 3 doesn't support two rules yet - RemoveUnused and ProcedureSyntax. So we require a different scalafix config
  * for Scala 3
  *
  * RemoveUnused relies on -warn-unused which isn't available in scala 3 yet -
  * https://scalacenter.github.io/scalafix/docs/rules/RemoveUnused.html
  *
  * ProcedureSyntax doesn't exist in Scala 3 - https://scalacenter.github.io/scalafix/docs/rules/ProcedureSyntax.html
  */
scalafixConfig := {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Some((ThisBuild / baseDirectory).value / ".scalafix3.conf")
    case _            => None
  }
}

Test / parallelExecution := false
Test / fork              := true

Global / onChangedBuildSource := ReloadOnSourceChanges

libraryDependencies ++= Dependencies.all

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
