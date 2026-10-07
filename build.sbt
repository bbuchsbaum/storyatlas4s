import org.typelevel.sbt.gha.JavaSpec

val Scala3 = "3.7.4"
val munitV = "1.3.4"
val munitCheckV = "1.3.0"
// Pinned to the workspace's Laminar precedent (cardsaplenty): Laminar 17.2.1 / scalajs-dom 2.8.1 on
// Scala.js 1.22 (sbt-scalajs in project/plugins.sbt).
val laminarV = "17.2.1"
val scalaJsDomV = "2.8.1"

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
// Keep external build Scala lines independent; CI selects this exact compiler.
ThisBuild / githubWorkflowScalaVersions := Seq(Scala3)
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21")
)

// Source-only development CI; release publishing and artifact cleanup are not enabled.
ThisBuild / tlCiReleaseBranches := Seq()
ThisBuild / tlCiReleaseTags := false
ThisBuild / githubWorkflowArtifactUpload := false
ThisBuild / githubWorkflowIncludeClean := false
ThisBuild / tlCiHeaderCheck := false
ThisBuild / tlCiDependencyGraphJob := false

// storymodel4s (the scientific compilers; ADR 0002) is consumed as an immutable source pin of its
// `view`, `fixtures`, and `codec` modules. Its own build pins published grakern sources.
// The local override below points at a clean checkout for coordinated development; qualification
// binds its exact revision to the declared pin and exported receipts.
//
// The pin moves with the change (docs/plans/2026-09-03-visualization-recovery-plan.md §5): any
// storymodel4s `view` change bumps this revision in the same slice, so the viewer can never again
// drift behind the model it draws.
lazy val storymodel4sRevision = "a2d795b26e64ca9cf304c861216c7622bb807190"
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
// `codec` is the canonical JSON of every artifact, and the only supported way to read back a
// `storymodel.json` the pipeline wrote. JVM only: the static edition reads a file, the browser
// shell links the fixture (V0; a fetched model in the shell is a later slice).
lazy val storymodel4sCodecJVM = ProjectRef(storymodel4sBuild, "codecJVM")
// The browser shell decodes exactly one artifact, the Recall Voyage document embedded in its own
// page (ADR 0002 §14 D4). It still fetches nothing: the document is an inline script the CLI wrote.
lazy val storymodel4sCodecJS = ProjectRef(storymodel4sBuild, "codecJS")

// Intaglio (renderer-neutral scene + SVG backend) is consumed as an immutable source pin.
lazy val intaglioRevision = "2fa5c682f4a95b8e73daad78dd9302d7aff46e11"
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
//   storymodel4s fixtures ────────┤
//   storymodel4s codec ───────────┴─▶ cli (JVM)   `edition`: the WOG fixture, or a storymodel.json
//                                 │               read off disk → SVG + HTML + twins + receipt
//                                 └─▶ shell (JVM, JS)  host-neutral core (StoryAtlas ADR 0001):
//                                          │        view choice and transitions, semantic zoom,
//                                          │        intent gate, compile → renderer-neutral plates,
//                                          │        interaction presentation
//                                          └─▶ app (JS)  Laminar web host: DOM, SVG, hit-testing
//
// Nothing here compiles a story or infers a claim: every artifact is compiled in storymodel4s.
// `codec` is likewise storymodel4s's: `cli` decodes a model, it never parses one.
// `layout` is the one place that lays out a page, and it does so as a pure function of the flow
// and measured text metrics (ADR 0002 D3/D13), receipted.

lazy val root = tlCrossRootProject.aggregate(intaglio, layout, edition, shell, cli, app)

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
  // The DOM measurer measures on a 2-D canvas context; the seam itself stays platform-free.
  .jsSettings(libraryDependencies += "org.scala-js" %%% "scalajs-dom" % scalaJsDomV)

/** `Pins.scala` in package `pkg`: the sibling revisions this build compiles against, so a receipt
  * can name them without reading the build.
  */
def pinsGenerator(pkg: String) = Def.task {
  val file =
    (Compile / sourceManaged).value / "storyatlas4s" / pkg.stripPrefix(
      "storyatlas4s."
    ) / "Pins.scala"
  IO.write(
    file,
    s"""package $pkg
       |
       |/** The immutable sibling revisions this build compiles against (generated from build.sbt). */
       |object Pins:
       |  val storymodel4sRevision: String = "$storymodel4sRevision"
       |  val intaglioRevision: String = "$intaglioRevision"
       |""".stripMargin
  )
  Seq(file)
}

/** The same producer-written bytes are decoded by the external JVM and JavaScript consumers. */
def workspaceTestData = Def.task {
  val producer = (ProjectRef(storymodel4sBuild, "root") / baseDirectory).value
  val directory = sys.props
    .get("storyatlas4s.workspace.fixtures")
    .map(file)
    .getOrElse(
      producer / "docs" / "refactor" / "evidence" / "workspace-m1-packet-20260922" / "fixtures"
    )
  val fixtures = (directory ** "*.workspace.json").get.sortBy(_.getName)
  require(fixtures.size == 2, "M1 requires both producer-written fixture archives")
  def quoted(value: String): String = "\"" + value.flatMap {
    case '\\'                    => "\\\\"
    case '"'                     => "\\\""
    case '\n'                    => "\\n"
    case '\r'                    => "\\r"
    case '\t'                    => "\\t"
    case c if c < ' ' || c > '~' => f"\\u${c.toInt}%04x"
    case c                       => c.toString
  } + "\""
  val values = fixtures
    .map { input =>
      val parts = IO.read(input).grouped(8192).map(quoted).mkString(",\n")
      quoted(input.getName.stripSuffix(".workspace.json")) + " -> Vector(" + parts + ").mkString"
    }
    .mkString(",\n")
  val output = (Test / sourceManaged).value / "storyatlas4s" / "edition" / "WorkspaceTestData.scala"
  IO.write(
    output,
    "package storyatlas4s.edition\nprivate[storyatlas4s] object WorkspaceTestData:\n" +
      "  val archives: Map[String, String] = Map(\n" + values + "\n)\n"
  )
  Seq(output)
}

/** The edition's fixed configuration (`EditionSpec`: page box, font, relation layers, thread
  * budget, lenses, zoom levels, SVG boxes) and the sibling pins (`Pins`), shared by `cli` (JVM) and
  * `app` (JS) so the static edition and the browser compile the same artifacts.
  */
lazy val edition = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("edition"))
  .dependsOn(layout)
  .settings(commonSettings)
  .settings(
    name := "storyatlas4s-edition",
    Compile / sourceGenerators += pinsGenerator("storyatlas4s.edition").taskValue,
    Test / sourceGenerators += workspaceTestData.taskValue
  )
  .jvmConfigure(_.dependsOn(storymodel4sCodecJVM))
  .jsConfigure(_.dependsOn(storymodel4sCodecJS))

/** The host-neutral shell core (StoryAtlas ADR 0001): every decision the interactive shell makes
  * that does not depend on a host — the view choice and its transitions, semantic-zoom hysteresis,
  * the last-intent-wins compilation gate, the compile step (the same compilers, paginator, and
  * lowerings as `cli/Edition`) producing renderer-neutral `Plate`s, the checked renderer-name
  * index, and each target's interaction presentation. No DOM, toolkit, or file-system dependency;
  * its suites run on the JVM and on Scala.js, which is the portability witness for any future host.
  */
lazy val shell = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("shell"))
  .dependsOn(edition % "compile->compile;test->test", intaglio, layout)
  .settings(commonSettings)
  .settings(name := "storyatlas4s-shell")
  .jvmConfigure(_.dependsOn(storymodel4sFixturesJVM % Test))
  .jsConfigure(_.dependsOn(storymodel4sFixturesJS % Test))

/** JVM command line: `edition --out <dir>` writes a static edition, from the linked War of the
  * Ghosts fixture or, with `--model <storymodel.json>`, from a model the pipeline built.
  */
lazy val cli = project
  .in(file("cli"))
  .settings(commonSettings)
  .settings(
    name := "storyatlas4s-cli",
    run / fork := true,
    // Relative `--out` paths resolve against the repository root, not `cli/`.
    run / baseDirectory := (ThisBuild / baseDirectory).value
  )
  // `test->test` lends the layout generators to the V-T2 law over generated flows.
  .dependsOn(
    edition.jvm,
    intaglio.jvm,
    layout.jvm % "compile->compile;test->test",
    storymodel4sFixturesJVM,
    storymodel4sCodecJVM,
    intaglioSvgJVM
  )

/** Copies the linked app (`app.js`) and the static shell (`app/index.html`) into the edition
  * directory, next to the files `cli/run edition --out target/edition` wrote.
  */
lazy val editionBundle =
  taskKey[Seq[File]]("Copy the fast-linked app and index.html into target/edition")

/** Browser host (Laminar) over `shell`: loads the War of the Ghosts fixture from storymodel4s
  * `fixtures` (the same object `cli/Edition` compiles — compiled into the JS bundle at link time,
  * never copied here), compiles Codex and Atlas in the browser through `shell`, paginates under the
  * live `DomMeasurer`, and draws the Intaglio overlays. Plain script output (`NoModule`), so the
  * edition's `index.html` opens from `file://` as well as from a static server. Linking it after a
  * full-repo compile and test run exceeds the sbt launcher's default 1g heap, hence `.jvmopts` (4g,
  * G1) at the root.
  */
lazy val app = project
  .in(file("app"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    name := "storyatlas4s-app",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % scalaJsDomV,
      "com.raquo" %%% "laminar" % laminarV
    ),
    editionBundle := {
      val report = (Compile / fastLinkJS).value.data
      val linked = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val edition = (ThisBuild / baseDirectory).value / "target" / "edition"
      val module = report.publicModules.headOption
        .getOrElse(sys.error("fastLinkJS produced no public module"))
      IO.createDirectory(edition)
      IO.copyFile(linked / module.jsFileName, edition / "app.js")
      IO.copyFile(baseDirectory.value / "index.html", edition / "index.html")
      Seq(edition / "app.js", edition / "index.html")
    }
  )
  .dependsOn(
    shell.js,
    edition.js,
    intaglio.js,
    layout.js,
    storymodel4sFixturesJS,
    storymodel4sCodecJS,
    intaglioSvgJS
  )

// Command aliases. storymodel4s and intaglio, loaded here as external builds, register their own
// `compileAll`/`testAll` aliases in the same global `onLoad` chain and the last registration wins,
// so a plain `addCommandAlias` would be shadowed. Instead, `onLoad` queues one command that
// (re)registers this build's aliases after every external build has run its hooks.
lazy val storyatlas4sAliases: Seq[(String, String)] = Seq(
  "compileAll" ->
    ";layoutJVM/compile;layoutJS/compile;intaglioJVM/compile;intaglioJS/compile;editionJVM/compile;editionJS/compile;shellJVM/compile;shellJS/compile;cli/compile;app/compile",
  "testAll" ->
    ";layoutJVM/test;layoutJS/test;intaglioJVM/test;intaglioJS/test;editionJVM/test;editionJS/test;shellJVM/test;shellJS/test;cli/test;app/test",
  "checkAll" -> ";scalafmtCheckAll;scalafmtSbtCheck;compileAll;testAll"
)
lazy val registerAliases = Command.command("storyatlas4sAliases") { state =>
  storyatlas4sAliases.foldLeft(state) { case (s, (name, value)) =>
    BasicCommands.addAlias(s, name, value)
  }
}
Global / commands += registerAliases
Global / onLoad := (Global / onLoad).value.andThen(state => "storyatlas4sAliases" :: state)
