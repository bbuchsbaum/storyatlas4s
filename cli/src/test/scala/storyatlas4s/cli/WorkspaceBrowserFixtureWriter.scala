package storyatlas4s.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import io.circe.Json
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.features.{TokenLength, TokenTracks}
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.story.StoryModel

/** Test-only generated source/feature bundle for browser/filesystem parity; no copied story text. */
object WorkspaceBrowserFixtureWriter:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected output directory")
    def ok[A](value: Either[?, A]): A = value.fold(e => throw new IllegalArgumentException(e.toString), identity)
    val directory = Path.of(args(0))
    require(!Files.exists(directory), "do not overwrite a previous fixture")
    val model = WarOfTheGhostsModel.model
    val receipt = BuildReceipt(model.source.id, model.source.canonicalChecksum, StoryModel.SchemaVersion,
      Vector(StageId.unsafe("test/workspace-browser") -> Checksum.ofText("synthetic fixture packaging")), 0L)
    val draft = ok(StoryModel.draftText(model.atlas, model.graph, model.hierarchy, model.trajectory,
      model.featureSpaces, model.sidecars, model.featureRefs, model.descriptors, model.hypotheses,
      model.sensoryProfiles, Some(receipt)))
    val sequence = SurfaceSequence(draft.atlas)
    val raw = ok(TokenTracks.measure(sequence, TokenLength))
    val sentences = ok(TokenTracks.perSentence(raw, sequence))
    val materialized = ok(FeatureMaterializer.materialize(draft, Vector(raw, sentences)))
    val artifact = ok(FeaturesArtifact.of(materialized.model, materialized.tracks))
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve("storymodel.json"), StoryModelCodec.encode(materialized.model), UTF_8)
    val _ = Files.writeString(directory.resolve("features.json"), FeaturesRecordCodec.encode(artifact), UTF_8)
    artifact.tracks.foreach { entry =>
      val file = directory.resolve(entry.file)
      val _ = Files.createDirectories(file.getParent)
      val _ = Files.write(file, materialized.sidecars(entry.track.space.id))
    }
    val read = ok(ModelInput.read(directory.resolve("storymodel.json")))
    val expected = Json.obj("trackCount" -> Json.fromInt(read.features.trackCount.get),
      "spaces" -> Json.fromValues(read.features.spaces.map(Json.fromString)))
    val _ = Files.writeString(directory.resolveSibling(directory.getFileName.toString + ".expected.json"), Canonical.print(expected), UTF_8)
    println(s"generated source feature bundle: ${read.features.trackCount.get} checked tracks")
