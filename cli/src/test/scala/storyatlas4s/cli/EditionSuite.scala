package storyatlas4s.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.view.ViewBasis

class EditionSuite extends FunSuite:
  private def ok[E, A](either: Either[E, A]): A =
    either.fold(e => fail(s"unexpected failure: $e"), identity)

  private val expectedFiles = Vector(
    "atlas-story.svg",
    "atlas-story.txt",
    "atlas-episode.svg",
    "atlas-episode.txt",
    "atlas-scene.svg",
    "atlas-scene.txt",
    "codex-reading.svg",
    "codex-reading.txt",
    "codex-overview.svg",
    "codex-overview.txt"
  )

  private val dataName = """data-name="([^"]*)"""".r

  test("the WOG edition has every artifact, prints its basis, and is deterministic"):
    val a = ok(Edition.warOfTheGhosts)
    val b = ok(Edition.warOfTheGhosts)
    assertEquals(a.files.map(_.name), expectedFiles)
    assertEquals(a, b)
    assertEquals(a.receipt, b.receipt)
    a.files.foreach { f =>
      assert(f.content.contains(ViewBasis.ResearcherReviewedFixture.label), f.name)
      assert(f.content.contains(f.configChecksum.hex), f.name)
    }
    assert(a.files.filter(_.artifact == "atlas").forall(_.names > 0))
    assert(a.files.find(_.name == "codex-overview.svg").exists(_.names > 0))
    assert(a.files.find(_.name == "codex-reading.svg").exists(_.names == 0))

  test("each SVG's data-name count equals the receipt's name count and the twin lists each name"):
    val e = ok(Edition.warOfTheGhosts)
    e.files.filter(_.name.endsWith(".svg")).foreach { svg =>
      val names = dataName.findAllMatchIn(svg.content).map(_.group(1)).toVector
      assertEquals(names.length, svg.names, svg.name)
      assertEquals(names.distinct.length, names.length, svg.name)
      val twin =
        e.files.find(_.name == svg.name.stripSuffix(".svg") + ".txt").getOrElse(fail("twin"))
      names.foreach(n => assert(twin.content.contains(n), s"${twin.name} lacks $n"))
    }

  test("the receipt records basis, pins, shared state, and a checksum per file"):
    val e = ok(Edition.warOfTheGhosts)
    val receipt = e.receipt
    assert(receipt.contains(s""""basis": "${ViewBasis.ResearcherReviewedFixture.label}""""))
    assert(receipt.contains(s""""storymodel4sRevision": "${Pins.storymodel4sRevision}""""))
    assert(receipt.contains(s""""intaglioRevision": "${Pins.intaglioRevision}""""))
    assert(receipt.contains("\"modelReceiptChecksum\": null"))
    assert(receipt.contains("relation:Causal"))
    e.files.foreach(f => assert(receipt.contains(f.checksum.hex), f.name))

  test("`edition --out <dir>` writes the files and returns exit code 0"):
    val dir: Path = Files.createTempDirectory("storyatlas4s-edition")
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val code =
      Main.run(List("edition", "--out", dir.toString), new PrintStream(out), new PrintStream(err))
    assertEquals(code, 0, new String(err.toByteArray, UTF_8))
    val written = (expectedFiles :+ Edition.ReceiptFile).map(dir.resolve)
    written.foreach(p => assert(Files.exists(p), p.toString))
    val e = ok(Edition.warOfTheGhosts)
    e.files.foreach { f =>
      assertEquals(new String(Files.readAllBytes(dir.resolve(f.name)), UTF_8), f.content, f.name)
    }
    assertEquals(new String(Files.readAllBytes(dir.resolve(Edition.ReceiptFile)), UTF_8), e.receipt)

  test("bad arguments produce usage and a non-zero exit code"):
    val err = new ByteArrayOutputStream
    assertEquals(
      Main.run(List("edition"), new PrintStream(new ByteArrayOutputStream), new PrintStream(err)),
      2
    )
    assert(new String(err.toByteArray, UTF_8).contains("usage"))
    assertEquals(Main.run(Nil, new PrintStream(new ByteArrayOutputStream), new PrintStream(err)), 2)
