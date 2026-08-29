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
  // sbt-typelevel already sets -Wvalue-discard; repeating it is itself a warning.
  scalacOptions ++= Seq(
    "-Wunused:all",
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
//   storymodel4s view ─────▶ layout (JVM, JS)  pure paginator: CodexFlow + metrics → pages/lines
//                                 │
//   storymodel4s view ──┐         │
//   intaglio core/svg ──┴─────────┴─▶ intaglio (JVM, JS)  pure lowering: view artifacts and
//                                 │                        paginated codices → intaglio scenes
//   storymodel4s fixtures ────────┴─▶ cli (JVM)   `edition`: WOG fixture → SVG + HTML + twins + receipt
//
// Nothing here compiles a story or infers a claim: every artifact is compiled in storymodel4s.
// `layout` is the one place that lays out a page, and it does so as a pure function of the flow
// and measured text metrics (ADR 0002 D3/D13), receipted.

lazy val root = tlCrossRootProject.aggregate(intaglio, layout, cli)

/** Pure lowering of `NarrativeScene`, `CodexFlow`, and `PaginatedCodex` to Intaglio scenes;
  * `GraphicsName` is the mark, annotation, or fragment identity (ADR 0002 §6).
  */
lazy val intaglio = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("intaglio"))
  .dependsOn(layout)
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

/** Pure pagination of a `CodexFlow` under metrics-as-data (ADR 0002 D3): the owned deterministic
  * publication backend on the JVM, and the `Measurer` seam a DOM measurer implements on JS.
  * Platform sources live in `layout/.jvm` (optional AWT measurer) and `layout/.js` (DOM stub).
  */
lazy val layout = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("layout"))
  .settings(commonSettings)
  .settings(name := "storyatlas4s-layout")
  .jvmConfigure(_.dependsOn(storymodel4sViewJVM, storymodel4sFixturesJVM % Test))
  .jsConfigure(_.dependsOn(storymodel4sViewJS, storymodel4sFixturesJS % Test))

/** JVM command line: `edition --out <dir>` writes the War of the Ghosts static edition. */
lazy val cli = project
  .in(file("cli"))
  .settings(commonSettings)
  .settings(
    name := "storyatlas4s-cli",
    run / fork := true,
    // Relative `--out` paths resolve against the repository root, not `cli/`.
    run / baseDirectory := (ThisBuild / baseDirectory).value,
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
  // `test->test` lends the layout generators to the V-T2 law over generated flows.
  .dependsOn(
    intaglio.jvm,
    layout.jvm % "compile->compile;test->test",
    storymodel4sFixturesJVM,
    intaglioSvgJVM
  )

// Command aliases. storymodel4s and intaglio, loaded here as external builds, register their own
// `compileAll`/`testAll` aliases in the same global `onLoad` chain and the last registration wins,
// so a plain `addCommandAlias` would be shadowed. Instead, `onLoad` queues one command that
// (re)registers this build's aliases after every external build has run its hooks.
lazy val storyatlas4sAliases: Seq[(String, String)] = Seq(
  "compileAll" ->
    ";layoutJVM/compile;layoutJS/compile;intaglioJVM/compile;intaglioJS/compile;cli/compile",
  "testAll" -> ";layoutJVM/test;layoutJS/test;intaglioJVM/test;intaglioJS/test;cli/test",
  "checkAll" -> ";scalafmtCheckAll;scalafmtSbtCheck;compileAll;testAll"
)
lazy val registerAliases = Command.command("storyatlas4sAliases") { state =>
  storyatlas4sAliases.foldLeft(state) { case (s, (name, value)) =>
    BasicCommands.addAlias(s, name, value)
  }
}
Global / commands += registerAliases
Global / onLoad := (Global / onLoad).value.andThen(state => "storyatlas4sAliases" :: state)
