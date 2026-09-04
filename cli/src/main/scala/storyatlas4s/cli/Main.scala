package storyatlas4s.cli

import java.io.PrintStream
import java.nio.file.{Path, Paths}

/** `storyatlas4s edition --out <dir> [--model <storymodel.json>]`. */
object Main:
  val usage: String =
    """usage: storyatlas4s edition --out <dir> [--model <storymodel.json>]
      |
      |  edition   compile a story model to Discourse Atlas SVGs (Story, Episode, Scene zoom),
      |            Codex overlays and paginated Codex HTML pages (Reading, Overview lenses),
      |            their textual twins, and receipt.json in <dir>. Without --model this is the
      |            researcher-reviewed War of the Ghosts fixture linked into this build.
      |
      |  --model   read the model from a storymodel.json the storymodel4s pipeline wrote. The file
      |            is decoded through storymodel4s codec and put to the validator; a model that
      |            does not validate is reported, with its violations, and not drawn.
      |
      |usage: storyatlas4s voyage --document <voyage.json> --out <dir>
      |
      |  voyage    compile a Recall Voyage document the storymodel4s pipeline wrote beside a
      |            recall-to-video report (ADR 0002 §14) to voyage.svg, its textual twin
      |            voyage.txt, the standalone page voyage.html (put app.js beside it for the
      |            interactive pane), and voyage-receipt.json in <dir>.
      |""".stripMargin

  /** What `edition` was asked to draw. */
  private enum Input:
    case Fixture
    case ModelFile(path: Path)

  def main(args: Array[String]): Unit =
    val code = run(args.toList, Console.out, Console.err)
    if code != 0 then sys.exit(code)

  /** Pure-ish entry point for tests: returns the process exit code. */
  def run(args: List[String], out: PrintStream, err: PrintStream): Int = args match
    case "edition" :: rest =>
      parse(rest) match
        case Left(problem) =>
          err.println(problem)
          err.print(usage)
          2
        case Right((dir, input)) =>
          compile(input, out, err).flatMap(e => Edition.write(e, Paths.get(dir))) match
            case Left(problem) =>
              err.println(s"edition failed: $problem")
              1
            case Right(paths) =>
              paths.foreach(p => out.println(p.toString))
              0
    case "voyage" :: rest =>
      parseVoyage(rest) match
        case Left(problem) =>
          err.println(problem)
          err.print(usage)
          2
        case Right((document, dir)) =>
          val path = Paths.get(document)
          val written =
            for
              read <- VoyageEdition.read(path)
              (doc, text) = read
              edition <- VoyageEdition.build(doc, text, path.toString)
              paths <- VoyageEdition.write(edition, Paths.get(dir))
            yield (edition, paths)
          written match
            case Left(problem) =>
              err.println(s"voyage failed: $problem")
              1
            case Right((edition, paths)) =>
              val s = edition.scene.summary
              out.println(
                s"voyage $path: ${s.units} units, ${s.anchored} anchored " +
                  s"(${s.posteriorArgmax} argmax, ${s.decodeBound} decode-bound, " +
                  s"${s.decodeFilled} decode-filled), ${s.unanchored} unanchored, " +
                  s"${s.untimed} untimed" +
                  s.codedAgreement.fold("")((a, t) => s"; coded group agreement $a/$t")
              )
              paths.foreach(p => out.println(p.toString))
              0
    case _ =>
      err.print(usage)
      2

  private def parseVoyage(args: List[String]): Either[String, (String, String)] =
    def go(
        rest: List[String],
        document: Option[String],
        dir: Option[String]
    ): Either[String, (String, String)] = rest match
      case "--document" :: value :: tail => go(tail, Some(value), dir)
      case "--out" :: value :: tail      => go(tail, document, Some(value))
      case "--document" :: Nil           => Left("missing path after --document")
      case "--out" :: Nil                => Left("missing directory after --out")
      case Nil                           =>
        for
          d <- document.toRight("missing --document <voyage.json>")
          o <- dir.toRight("missing --out <dir>")
        yield (d, o)
      case other => Left(s"unrecognized arguments: ${other.mkString(" ")}")
    go(args, None, None)

  /** Reading a model reports what the validator found before anything is drawn, so a partial model
    * is announced rather than discovered later inside a picture.
    */
  private def compile(input: Input, out: PrintStream, err: PrintStream): Either[String, Edition] =
    input match
      case Input.Fixture         => Edition.warOfTheGhosts
      case Input.ModelFile(path) =>
        ModelInput.read(path).flatMap { read =>
          out.println(
            s"model $path: ${read.report.errors.length} errors, " +
              s"${read.report.warnings.length} warnings, structurally-validated=${read.isValidated}, draft-view=${read.needsDraftView}, " +
              s"derivation=${read.derivation.render}, features=${read.features.render}"
          )
          if !read.isValidated then read.violationSummary.foreach(err.println)
          Edition.fromRead(read)
        }

  private def parse(args: List[String]): Either[String, (String, Input)] =
    def go(
        rest: List[String],
        dir: Option[String],
        model: Option[String]
    ): Either[String, (String, Input)] = rest match
      case "--out" :: value :: tail   => go(tail, Some(value), model)
      case "--model" :: value :: tail => go(tail, dir, Some(value))
      case "--out" :: Nil             => Left("missing directory after --out")
      case "--model" :: Nil           => Left("missing path after --model")
      case Nil                        =>
        dir
          .toRight("missing --out <dir>")
          .map(d => d -> model.fold[Input](Input.Fixture)(m => Input.ModelFile(Paths.get(m))))
      case other => Left(s"unrecognized arguments: ${other.mkString(" ")}")
    go(args, None, None)
