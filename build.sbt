import Dependencies.all

lazy val scala213               = "2.13.7"
lazy val scala212               = "2.12.15"
lazy val supportedScalaVersions = List(scala213, scala212)
lazy val scmUrl                 = "https://github.com/sky-uk/kafka-topic-loader"

name                   := "kafka-topic-loader"
organization           := "uk.sky"
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

scalaVersion       := scala213
crossScalaVersions := supportedScalaVersions
semanticdbEnabled  := true
semanticdbVersion  := scalafixSemanticdb.revision

// format: off
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "utf8",
  "-explaintypes",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-unchecked",
  "-Xcheckinit",
  "-Xfatal-warnings",
  "-Ywarn-dead-code",
  "-Ywarn-extra-implicit",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:imports",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:params",
  "-Ywarn-unused:patvars",
  "-Ywarn-unused:privates",
  "-Ywarn-value-discard"
) ++ {
  if (scalaBinaryVersion.value == "2.13") Seq("-Wconf:msg=annotation:silent")
  else Seq("-Xfuture", "-Ypartial-unification", "-Yno-adapted-args")
}
// format: on

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"

Test / parallelExecution := false
Test / fork              := true

Global / onChangedBuildSource := ReloadOnSourceChanges

libraryDependencies ++= all

addCommandAlias("checkFix", "scalafixAll --check OrganizeImports; scalafixAll --check")
addCommandAlias("runFix", "scalafixAll OrganizeImports; scalafixAll")
addCommandAlias("checkFmt", "scalafmtCheckAll; scalafmtSbtCheck")
addCommandAlias("runFmt", "scalafmtAll; scalafmtSbt")

addCommandAlias("ciBuild", "checkFmt; checkFix; +test")
