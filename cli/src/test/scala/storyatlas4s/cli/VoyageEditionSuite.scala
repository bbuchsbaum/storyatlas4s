package storyatlas4s.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.align.{
  AlignError,
  AlignState,
  AlignmentMatrix,
  AlignmentRow,
  ExternalState,
  SourceNodeRef
}
import storymodel4s.codec.Canonical
import storymodel4s.codec.VoyageCodecs.given
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

/** The voyage edition: every file, a receipt that reads from the scene, determinism, and the
  * refusal of a document whose join does not hold.
  */
class VoyageEditionSuite extends FunSuite:
  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)
  private def okA[A](e: Either[AlignError, A]): A = e.fold(err => fail(err.toString), identity)
  private def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  private def secs(v: Double) = ok(Seconds.of(v))
  private val a = SourceNodeRef.Situation(SituationId.unsafe("a"))
  private val b = SourceNodeRef.Situation(SituationId.unsafe("b"))
  private val g = SourceNodeRef.Segment(SegmentId.unsafe("g1"))
  private val u1 = RecallUnitId.unsafe("u1")
  private val u2 = RecallUnitId.unsafe("u2")

  private val document =
    val timeline = ok(
      SourceTimeline.of(
        Vector(
          SourceTimelineNode(a, 0, Some(1), span(0, 10), "a"),
          SourceTimelineNode(b, 0, Some(1), span(10, 20), "b"),
          SourceTimelineNode(g, 1, Some(1), span(0, 20), "group 1")
        ),
        Vector(SourceTimelineGroup(1, "group 1", span(0, 20)))
      )
    )
    val units = Vector(
      VoyageUnit(u1, 0, "one </script> two", Some(secs(0.5)), Some(secs(1.25))),
      VoyageUnit(u2, 1, "two", Some(secs(4.0)), None)
    )
    val matrix = okA(
      AlignmentMatrix.of(
        Vector(
          okA(
            AlignmentRow.of(
              u1,
              Map(
                AlignState.Source(a) -> 0.6,
                AlignState.Source(b) -> 0.1,
                AlignState.External(ExternalState.Commentary) -> 0.3
              )
            )
          ),
          okA(AlignmentRow.of(u2, Map(AlignState.Source(b) -> 1.0)))
        )
      )
    )
    val decisions = Vector(
      VoyageDecision(u1, Some(a), Some(1), AnchorOrigin.PosteriorArgmax),
      VoyageDecision(u2, Some(b), Some(1), AnchorOrigin.PosteriorArgmax)
    )
    val coding =
      IndependentCoding("coding", Checksum.ofText("coding"), Vector(CodedInterval(span(0, 3), 1)))
    val provenance = ok(
      ViewProvenance.of(
        Checksum.ofText("source"),
        None,
        ViewBasis.AlignmentRun,
        VoyageCompiler.compilerVersion,
        Checksum.ofText("config")
      )
    )
    RecallVoyageDocument(
      ok(RecallVoyageInput.of(units, matrix, timeline, decisions, Some(coding), secs(30))),
      provenance
    )

  private val documentJson = Canonical.encode(document)

  test("the voyage edition writes four files, and the receipt reads from the scene"):
    val e = ok(VoyageEdition.build(document, documentJson, "voyage.json"))
    val dir = Files.createTempDirectory("voyage-edition")
    val paths = ok(VoyageEdition.write(e, dir))
    assertEquals(
      paths.map(_.getFileName.toString),
      Vector("voyage.svg", "voyage.txt", "voyage.html", "voyage-receipt.json")
    )
    val receipt = new String(Files.readAllBytes(dir.resolve("voyage-receipt.json")), UTF_8)
    assert(receipt.contains(s""""basis": "${ViewBasis.AlignmentRun.label}""""))
    assert(receipt.contains(""""units": 2"""))
    assert(receipt.contains(""""anchored": 2"""))
    assert(receipt.contains(""""marks": 3"""), receipt)
    assert(receipt.contains(""""names": 2"""), receipt)
    assert(receipt.contains(""""codedAgreement": {"""))
    assert(e.svg.contains("""data-name="voyage/anchor/u1""""))
    assert(e.twin.contains("voyage/anchor/u1 unit 0 at 0.5 -> sit:a"))

  test("the page embeds the document safely and loads app.js beside the static plate"):
    val e = ok(VoyageEdition.build(document, documentJson, "voyage.json"))
    assert(e.html.contains("""<script type="application/json" id="voyage-document">"""))
    assert(!e.html.contains("</script> two"), "a closing tag inside the document is escaped")
    val start = e.html.indexOf("""id="voyage-document">""") + """id="voyage-document">""".length
    val end = e.html.indexOf("</script>", start)
    val embedded = e.html.substring(start, end)
    assert(!embedded.contains("<"), "no raw < survives inside the inline document")
    assert(
      storymodel4s.codec.VoyageCodecs.decode(embedded).isRight,
      "the escaped inline document decodes back to a document"
    )
    assert(e.html.contains("""<script src="app.js"></script>"""))
    assert(e.html.contains("""data-name="voyage/anchor/u1""""))
    assert(!e.html.contains("googleapis"), "no external resource")

  test("the edition is deterministic"):
    val x = ok(VoyageEdition.build(document, documentJson, "voyage.json"))
    val y = ok(VoyageEdition.build(document, documentJson, "voyage.json"))
    assertEquals(x.svg, y.svg)
    assertEquals(x.twin, y.twin)
    assertEquals(x.receipt, y.receipt)

  test("the command reads a document from disk and refuses one whose join does not hold"):
    val dir = Files.createTempDirectory("voyage-cli")
    val doc = dir.resolve("voyage.json")
    Files.write(doc, documentJson.getBytes(UTF_8))
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val code = Main.run(
      List("voyage", "--document", doc.toString, "--out", dir.resolve("out").toString),
      new PrintStream(out),
      new PrintStream(err)
    )
    assertEquals(code, 0, err.toString(UTF_8))
    assert(Files.exists(dir.resolve("out").resolve("voyage.html")))
    // u2's anchor carries mass, so calling it decode-filled cannot describe its row.
    val broken = documentJson.replace(
      """"origin":"posterior_argmax","unit":"u2"""",
      """"origin":"decode_filled","unit":"u2""""
    )
    assert(broken != documentJson, "the mutation must change the document")
    val badDoc = dir.resolve("bad.json")
    Files.write(badDoc, broken.getBytes(UTF_8))
    val err2 = new ByteArrayOutputStream
    val code2 = Main.run(
      List("voyage", "--document", badDoc.toString, "--out", dir.resolve("out2").toString),
      new PrintStream(new ByteArrayOutputStream),
      new PrintStream(err2)
    )
    assertEquals(code2, 1)
    assert(err2.toString(UTF_8).contains("voyage"), err2.toString(UTF_8))
