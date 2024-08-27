import scalanative.build._
import Versions._

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization  := "ca.uwaterloo.plg"
ThisBuild / homepage      := Some(url("https://github.com/amsen20/gurl"))
ThisBuild / licenses      := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer(
    "amsen20",
    "Amirhossein Pashaeehir",
    "ahph1380@gmail.com",
    url("https://github.com/amsen20/")
  )
)

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local"

ThisBuild / crossScalaVersions := Seq(scala3)

val vcpkgBaseDir = "C:/vcpkg/"
val isDebug      = false

ThisBuild / nativeConfig ~= { c =>
  val osNameOpt = sys.props.get("os.name")
  val isMacOs   = osNameOpt.exists(_.toLowerCase().contains("mac"))
  val isWindows = osNameOpt.exists(_.toLowerCase().contains("windows"))
  var platformOptions = if (isMacOs) { // brew-installed curl
    c.withLinkingOptions(c.linkingOptions :+ "-L/usr/local/opt/curl/lib")
  } else if (isWindows) { // vcpkg-installed curl
    c.withCompileOptions(c.compileOptions :+ s"-I${vcpkgBaseDir}/installed/x64-windows/include/")
      .withLinkingOptions(c.linkingOptions :+ s"-L${vcpkgBaseDir}/installed/x64-windows/lib/")
  } else c

  platformOptions = platformOptions
    .withMultithreading(true)
    .withLTO(LTO.none)
    .withGC(GC.immix)
  if (isDebug)
    platformOptions
      .withSourceLevelDebuggingConfig(_.enableAll) // enable generation of debug informations
      .withOptimize(false)                         // disable Scala Native optimizer
      .withMode(scalanative.build.Mode.debug)      // compile using LLVM without optimizations
      .withCompileOptions(Seq("-DSCALANATIVE_DELIMCC_DEBUG"))
  else
    platformOptions
      .withMode(Mode.releaseFull)
      .withOptimize(true)
}

ThisBuild / envVars ++= {
  if (sys.props.get("os.name").exists(_.toLowerCase().contains("windows")))
    Map(
      "PATH" -> s"${sys.props.getOrElse("PATH", "")};${vcpkgBaseDir}/installed/x64-windows/bin/"
    )
  else Map.empty[String, String]
}

def when(pred: => Boolean)(refs: CompositeProject*) = if (pred) refs else Nil

lazy val modules = List(
  purl,
  example,
  testServer,
  multiTestSuite
)
// For now we do not support websocket on Scala Native
// ++ when(sys.env.get("EXPERIMENTAL").contains("yes"))(websocketTestSuite)

lazy val root =
  tlCrossRootProject
    .enablePlugins(NoPublishPlugin)
    .aggregate(modules: _*)

lazy val purl = project
  .in(file("purl"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "purl",
    libraryDependencies ++= Seq(
      "ch.epfl.lamp"     %%% "gears"      % gearsVersion,
      "ca.uwaterloo.plg" %%% "pollerbear" % pollerBearVersion,
      "org.scalameta"    %%% "munit"      % munitVersion % Test
    )
  )

lazy val example = project
  .in(file("example"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(purl)
  .settings(
    libraryDependencies ++= Seq(
      "ch.epfl.lamp" %%% "gears" % gearsVersion
    )
  )

lazy val testServer = project
  .in(file("test-server"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % catsEffectVersion,
      "org.http4s"    %% "http4s-dsl"          % http4sVersion,
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "ch.qos.logback" % "logback-classic"     % "1.4.14"
    )
  )

lazy val multiTestSuite = project
  .in(file("tests/multi"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(purl)
  .settings(
    libraryDependencies ++= Seq(
      "ch.epfl.lamp"  %%% "gears" % gearsVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val startTestServer = taskKey[Unit]("starts test server if not running")
lazy val stopTestServer  = taskKey[Unit]("stops test server if running")

ThisBuild / startTestServer := {
  (testServer / Compile / compile).value
  val cp = (testServer / Compile / fullClasspath).value.files
  TestServer.setClassPath(cp)
  TestServer.setLog(streams.value.log)
  TestServer.start()
}

ThisBuild / stopTestServer := {
  TestServer.stop()
}

addCommandAlias("integrate", "startTestServer; test")
