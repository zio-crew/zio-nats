val ProjectVersion      = "0.0.1"
val ZioVersion          = "1.0.0-RC21-2"
val ZioCassandraVersion = "1.0.6"
val UzHttpVersion       = "0.2.3"
val ScalaVersion        = "2.13.2"

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

def subProjectSettings(pName: String) = Seq(
  organization := "GR",
  name := pName,
  version := ProjectVersion,
  scalaVersion := ScalaVersion,
  maxErrors := 3,
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots")
  ),
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"         % ZioVersion,
    "dev.zio" %% "zio-streams" % ZioVersion,
    "dev.zio" %% "zio-nio"     % "1.0.0-RC8" excludeAll (ExclusionRule("dev.zio", "zio"), ExclusionRule(
      "dev.zio",
      "zio-streams"
    )),
    "com.lihaoyi" %% "fastparse"    % "2.2.2",
    "dev.zio"     %% "zio-test"     % ZioVersion % "test",
    "dev.zio"     %% "zio-test-sbt" % ZioVersion % "test"
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  // Refine scalac params from tpolecat
  scalacOptions in (Compile, console) --= Seq("-Xfatal-warnings"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
)

lazy val `zio-nats` = (project in file("znats"))
  .settings(subProjectSettings("znats"))

lazy val `zio-nats-root` = (project in file("."))
  .aggregate(`zio-nats`)
  .settings(
    skip in publish := true
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("chk", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
