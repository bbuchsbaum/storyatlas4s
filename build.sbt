import org.typelevel.sbt.gha.JavaSpec

val Scala3 = "3.7.4"
val munitV = "1.3.4"
val munitCheckV = "1.3.0"

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "io.github.canardlapin"
ThisBuild / organizationName := "Bradley Buchsbaum"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  tlGitHubDev("canardlapin", "Bradley Buchsbaum")
)

ThisBuild / scalaVersion := Scala3
ThisBuild / crossScalaVersions := Seq(Scala3)
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21")
)

// storymodel4s (the scientific compilers; ADR 0002) is consumed as an immutable source pin of its
// `view` and `fixtures` modules. Its own build pins grakern by ProjectRef and, until grakern has a
// remote, REQUIRES `-Dstorymodel4s.grakern.build=/path/to/grakern` on every command line that
// loads it. The local override below points at a checkout for coordinated development.
lazy val storymodel4sRevision = "8492e43bd0adf7515b837539493217b20391a0fc"
lazy val storymodel4sBuild =
  sys.props
    .get("storyatlas4s.storymodel4s.build")
    .orElse(sys.env.get("STORYATLAS4S_STORYMODEL4S_BUILD"))
    .map(p => file(p).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/bbuchsbaum/storymodel4s.git#$storymodel4sRevision"))
lazy val storymodel4sViewJVM = ProjectRef(storymodel4sBuild, "viewJVM")
lazy val storymodel4sViewJS = ProjectRef(storymodel4sBuild, "viewJS")
lazy val storymodel4sFixturesJVM = ProjectRef(storymodel4sBuild, "fixturesJVM")
lazy val storymodel4sFixturesJS = ProjectRef(storymodel4sBuild, "fixturesJS")

// Intaglio (renderer-neutral scene + SVG backend) is consumed as an immutable source pin.
lazy val intaglioRevision = "596b398af380079e4b251535230d0bc03cd88c51"
lazy val intaglioBuild =
  sys.props
    .get("storyatlas4s.intaglio.build")
    .orElse(sys.env.get("STORYATLAS4S_INTAGLIO_BUILD"))
    .map(p => file(p).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/intaglio.git#$intaglioRevision"))
lazy val intaglioCoreJVM = ProjectRef(intaglioBuild, "coreJVM")
lazy val intaglioCoreJS = ProjectRef(intaglioBuild, "coreJS")
lazy val intaglioSvgJVM = ProjectRef(intaglioBuild, "svgJVM")
lazy val intaglioSvgJS = ProjectRef(intaglioBuild, "svgJS")

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-Wunused:all",
    "-Wvalue-discard",
    "-Wconf:msg=package scala contains object and package with same name.*caps:silent"
  ),
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % munitV % Test,
    "org.scalameta" %%% "munit-scalacheck" % munitCheckV % Test
  ),
  Test / parallelExecution := false
)

// Module graph (docs/plans/2026-08-28-storyatlas4s-scaffold.md §2):
//
//   storymodel4s view ──┐
//   intaglio core/svg ──┴─▶ intaglio (JVM, JS)  pure lowering: view artifacts → intaglio scenes
//                                 │
//   storymodel4s fixtures ────────┴─▶ cli (JVM)   `edition`: WOG fixture → SVG + twins + receipt
//
// Nothing here compiles a story, infers a claim, or lays out a page: every artifact is compiled
// in storymodel4s, and this repository only lowers and writes it.

lazy val root = tlCrossRootProject.aggregate(intaglio, cli)

/** Pure lowering of `NarrativeScene` and `CodexFlow` to Intaglio scenes; `GraphicsName` is the mark
  * or annotation identity (ADR 0002 §6).
  */
lazy val intaglio = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("intaglio"))
  .settings(commonSettings)
  .settings(name := "storyatlas4s-intaglio")
  .jvmConfigure(
    _.dependsOn(
      storymodel4sViewJVM,
      intaglioCoreJVM,
      intaglioSvgJVM,
      storymodel4sFixturesJVM % Test
    )
  )
  .jsConfigure(
    _.dependsOn(
      storymodel4sViewJS,
      intaglioCoreJS,
      intaglioSvgJS,
      storymodel4sFixturesJS % Test
    )
  )

/** JVM command line: `edition --out <dir>` writes the War of the Ghosts static edition. */
lazy val cli = project
  .in(file("cli"))
  .settings(commonSettings)
  .settings(
    name := "storyatlas4s-cli",
    run / fork := true,
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "storyatlas4s" / "cli" / "Pins.scala"
      IO.write(
        file,
        s"""package storyatlas4s.cli
           |
           |/** The immutable sibling revisions this build compiles against (generated from build.sbt). */
           |object Pins:
           |  val storymodel4sRevision: String = "$storymodel4sRevision"
           |  val intaglioRevision: String = "$intaglioRevision"
           |""".stripMargin
      )
      Seq(file)
    }.taskValue
  )
  .dependsOn(intaglio.jvm, storymodel4sFixturesJVM, intaglioSvgJVM)

addCommandAlias("compileAll", ";intaglioJVM/compile;intaglioJS/compile;cli/compile")
addCommandAlias("testAll", ";intaglioJVM/test;intaglioJS/test;cli/test")
addCommandAlias("checkAll", ";scalafmtCheckAll;scalafmtSbtCheck;compileAll;testAll")
