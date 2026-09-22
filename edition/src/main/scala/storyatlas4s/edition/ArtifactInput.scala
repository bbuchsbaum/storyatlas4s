package storyatlas4s.edition

import java.nio.charset.StandardCharsets.UTF_8
import cats.syntax.all.*
import storymodel4s.codec.*
import storymodel4s.story.StoryModel
import storymodel4s.view.{RecallVoyageDocument, WorkspaceRefusal}

enum ImportedArtifact:
  case Workspace(value: SourceRecallWorkspace)
  case Source(value: ImportedSource)
  case Voyage(value: RecallVoyageDocument)

/** Local input dispatch uses canonical producer formats. No extension guesses, estimator, network
  * access, or built-in example fallback. Refusals never echo unadmitted content.
  */
object ArtifactInput:
  val MaxBytes: Long = 32L * 1024L * 1024L
  val MaxFiles: Int = 256
  private type Result[A] = Either[WorkspaceRefusal, A]
  private def utf8(bytes: Vector[Byte]): Result[String] =
    val value = new String(bytes.toArray, UTF_8)
    Either.cond(value.getBytes(UTF_8).toVector == bytes, value, WorkspaceRefusal.UnsupportedContent)

  def open(files: Vector[(String, Vector[Byte])]): Result[ImportedArtifact] =
    val names = files.map(_._1)
    if files.isEmpty || files.size > MaxFiles || files.map(_._2.size.toLong).sum > MaxBytes then
      Left(WorkspaceRefusal.UnsupportedContent)
    else if names.map(_.toLowerCase(java.util.Locale.ROOT)).distinct.size != names.size then
      Left(WorkspaceRefusal.DuplicatePath)
    else if files.size == 1 then
      utf8(files.head._2).flatMap { value =>
        Canonical.parse(value).left.map(_ => WorkspaceRefusal.UnsupportedContent).flatMap { j =>
          j.hcursor.get[String]("schemaVersion").toOption match
            case Some(WorkspaceArchiveCodec.SchemaVersion) => WorkspaceCodecs.decode(value).map(ImportedArtifact.Workspace(_))
            case Some(StoryModel.SchemaVersion) => SourceInput.decode(value).left
              .map(_ => WorkspaceRefusal.UnsupportedContent).map(ImportedArtifact.Source(_))
            case _ if j.hcursor.get[Int]("schemaVersion").contains(VoyageCodecs.schemaVersion) =>
              VoyageCodecs.decode(value).left.map(_ => WorkspaceRefusal.UnsupportedContent).map(ImportedArtifact.Voyage(_))
            case _ => Left(WorkspaceRefusal.UnsupportedVersion)
        }
      }
    else
      val supplied = files.toMap
      def optional(name: String): Result[Option[String]] = supplied.get(name).traverse(utf8)
      for
        bytes <- supplied.get("storymodel.json").toRight(WorkspaceRefusal.MissingRequiredRole)
        model <- utf8(bytes)
        derivation <- optional("derivation.json")
        features <- optional("features.json")
        source <- SourceInput.decode(model, derivation, features,
          name => supplied.get(name).map(_.toArray).toRight("required sidecar not supplied"))
          .left.map(_ => WorkspaceRefusal.SemanticJoinMismatch)
        sidecars = source.features match
          case FeatureRecord.NotSupplied => Set.empty[String]
          case FeatureRecord.Supplied(artifact, _) => artifact.tracks.map(_.file).toSet
        _ <- Either.cond(names.toSet.subsetOf(sidecars ++ Set("storymodel.json", "derivation.json", "features.json")),
          (), WorkspaceRefusal.UnexpectedBytes)
      yield ImportedArtifact.Source(source)
