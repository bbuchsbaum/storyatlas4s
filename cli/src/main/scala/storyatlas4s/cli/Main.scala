package storyatlas4s.cli

import java.io.PrintStream
import java.nio.file.Paths

/** `storyatlas4s edition --out <dir>`: write the War of the Ghosts static edition. */
object Main:
  val usage: String =
    """usage: storyatlas4s edition --out <dir>
      |
      |  edition   compile the researcher-reviewed War of the Ghosts fixture (storymodel4s
      |            fixtures) to Discourse Atlas SVGs (Story, Episode, Scene zoom), Codex overlays
      |            (Reading, Overview lenses), their textual twins, and receipt.json in <dir>.
      |""".stripMargin

  def main(args: Array[String]): Unit =
    val code = run(args.toList, Console.out, Console.err)
    if code != 0 then sys.exit(code)

  /** Pure-ish entry point for tests: returns the process exit code. */
  def run(args: List[String], out: PrintStream, err: PrintStream): Int = args match
    case "edition" :: rest =>
      parseOut(rest) match
        case Left(problem) =>
          err.println(problem)
          err.print(usage)
          2
        case Right(dir) =>
          Edition.warOfTheGhosts.flatMap(e => Edition.write(e, Paths.get(dir))) match
            case Left(problem) =>
              err.println(s"edition failed: $problem")
              1
            case Right(paths) =>
              paths.foreach(p => out.println(p.toString))
              0
    case _ =>
      err.print(usage)
      2

  private def parseOut(args: List[String]): Either[String, String] = args match
    case "--out" :: dir :: Nil => Right(dir)
    case Nil                   => Left("missing --out <dir>")
    case other                 => Left(s"unrecognized arguments: ${other.mkString(" ")}")
