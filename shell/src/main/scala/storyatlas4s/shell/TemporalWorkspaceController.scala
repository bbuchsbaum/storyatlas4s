package storyatlas4s.shell

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, TemporalInspectionCodecs}
import storymodel4s.core.*
import storymodel4s.view.ArtifactId

/** Host-neutral controls for a checked temporal inspection projection. */
enum TemporalWorkspaceAction:
  case Request(value: TemporalProjection.Request)
  case Region(value: Vector[TemporalQuery.Region])
  case TopK(value: Int)
  case Selection(value: Set[Address])
  case Allocation(value: TemporalQuery.Allocation)
  case Bins(value: Vector[(Long, Long)])
  case RecallWindow(value: Option[TemporalProjection.RecallWindow])

/** Presents producer-compiled temporal quantities without deriving timing or allocation locally. */
final class TemporalWorkspaceController private (
    val session: TemporalInspectionCodecs.Session,
    val projection: TemporalInspectionCodecs.Projection,
    val selection: Set[Address],
    val roi: Vector[TemporalQuery.Region],
    val topK: Int
):
  /** Recompile the unchanged request against another admitted policy and its supported measure. */
  def switchPolicy(
      policy: ArtifactId,
      selected: Set[Address]
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] =
    TemporalWorkspaceController.switchPolicy(
      session,
      policy,
      projection.request,
      roi,
      topK,
      selected
    )

  def dispatch(
      action: TemporalWorkspaceAction
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] = action match
    case TemporalWorkspaceAction.Request(value) =>
      TemporalWorkspaceController
        .bounded(value.bins.size)
        .flatMap(_ => session.compile(projection.policy.id, projection.measure, value))
        .flatMap { next =>
          val nextRoi =
            if next.coordinate.axis == projection.coordinate.axis then roi else next.regions
          TemporalWorkspaceController.checked(session, next, selection, nextRoi, topK)
        }
    case TemporalWorkspaceAction.Region(value) =>
      TemporalWorkspaceController.checked(session, projection, selection, value, topK)
    case TemporalWorkspaceAction.TopK(value) =>
      TemporalWorkspaceController.checked(session, projection, selection, roi, value)
    case TemporalWorkspaceAction.Selection(value) =>
      TemporalWorkspaceController.checked(session, projection, value, roi, topK)
    case TemporalWorkspaceAction.Allocation(value) =>
      val declarations = projection.request.declarations.map(_.copy(allocation = value))
      dispatch(
        TemporalWorkspaceAction.Request(projection.request.copy(declarations = declarations))
      )
    case TemporalWorkspaceAction.Bins(value) =>
      dispatch(TemporalWorkspaceAction.Request(projection.request.copy(bins = value)))
    case TemporalWorkspaceAction.RecallWindow(value) =>
      dispatch(TemporalWorkspaceAction.Request(projection.request.copy(recallWindow = value)))

  /** Producer-owned canonical descriptor; export permission is enforced by the admitted packet. */
  def encode: Either[TemporalInspectionCodecs.Error, String] =
    TemporalInspectionCodecs.saveView(projection).flatMap { view =>
      TemporalWorkspaceController.descriptor(session, selection, roi, topK, view)
    }

object TemporalWorkspaceController:
  val Version = "storyatlas-temporal-workspace/v1"

  /** Interactive views admit at most 128 source bins and region pieces; larger requests refuse. */
  val MaximumRegions: Int = 128

  private def bounded(count: Int): Either[TemporalInspectionCodecs.Error, Unit] =
    Either.cond(
      count <= MaximumRegions,
      (),
      TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.TooManyBins)
    )

  private def invalid[A]: Either[TemporalInspectionCodecs.Error, A] =
    Left(TemporalInspectionCodecs.Error.Invalid)

  private def addresses(session: TemporalInspectionCodecs.Session): Set[Address] =
    session.recallAddresses.keySet ++ session.sourceAddresses.keySet

  private def regions(
      projection: TemporalInspectionCodecs.Projection,
      values: Vector[TemporalQuery.Region]
  ): Either[TemporalInspectionCodecs.Error, Unit] =
    def intersects(a: TemporalQuery.Region, b: TemporalQuery.Region): Boolean =
      (a.geometry, b.geometry) match
        case (Some(x), Some(y)) =>
          x.intervals.exists(i =>
            y.intervals.exists(j => i.start < j.endExclusive && j.start < i.endExclusive)
          ) ||
          x.points.exists(p =>
            y.points.exists(_.at == p.at) || y.intervals.exists(i =>
              i.start <= p.at && p.at < i.endExclusive
            )
          ) ||
          y.points.exists(p => x.intervals.exists(i => i.start <= p.at && p.at < i.endExclusive))
        case _ => false
    val pieces =
      values.foldLeft(0L)((n, r) => n + r.geometry.fold(0)(g => g.intervals.size + g.points.size))
    if values.size > MaximumRegions || pieces > MaximumRegions then
      Left(TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.TooManyBins))
    else if values.exists(_.axis != projection.coordinate.axis) then invalid
    else if values.indices.exists(i =>
        values.indices.drop(i + 1).exists(j => intersects(values(i), values(j)))
      )
    then Left(TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.Overlap))
    else Right(())

  private def checked(
      session: TemporalInspectionCodecs.Session,
      projection: TemporalInspectionCodecs.Projection,
      selection: Set[Address],
      roi: Vector[TemporalQuery.Region],
      topK: Int
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] =
    for
      _ <- Either.cond(
        selection.subsetOf(addresses(session)),
        (),
        TemporalInspectionCodecs.Error.Invalid
      )
      _ <- Either.cond(
        topK >= 0,
        (),
        TemporalInspectionCodecs.Error.Query(TemporalQuery.Refusal.InvalidTopK)
      )
      _ <- regions(projection, roi)
      _ <- selection.toVector.sortBy(_.render).traverse_ { address =>
        session.recallAddresses
          .get(address)
          .traverse { unit =>
            projection.rows
              .find(_.unit == unit)
              .traverse { row =>
                row.grid.toOption.traverse { _ =>
                  roi.traverse_(region =>
                    projection
                      .query(unit, region)
                      .flatMap(_.topK(topK).left.map(TemporalInspectionCodecs.Error.Query(_)))
                  )
                }
              }
              .void
          }
          .void
      }
    yield new TemporalWorkspaceController(session, projection, selection, roi, topK)

  private def compile(
      session: TemporalInspectionCodecs.Session,
      policy: ArtifactId,
      measure: TemporalQuery.Measure,
      request: TemporalProjection.Request,
      selection: Set[Address],
      roi: Vector[TemporalQuery.Region],
      topK: Int
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] =
    session.compile(policy, measure, request).flatMap(checked(session, _, selection, roi, topK))

  private def defaultBins(
      axis: PresentationAxis
  ): Either[TemporalInspectionCodecs.Error, Vector[(Long, Long)]] =
    axis.extent match
      case ticks: AxisExtent.PlaybackTicks =>
        val start = BigInt(ticks.start)
        val width = BigInt(ticks.endExclusive) - start
        val count = width.min(BigInt(32)).toInt
        Either.cond(count > 0, (), TemporalInspectionCodecs.Error.Invalid).map { _ =>
          Vector.tabulate(count) { i =>
            ((start + width * i / count).toLong, (start + width * (i + 1) / count).toLong)
          }
        }
      case _ => invalid

  private def defaultSelection(
      session: TemporalInspectionCodecs.Session
  ): Either[TemporalInspectionCodecs.Error, TemporalSupport.Selection] =
    session.workspace.source.bundles.toVector
      .collectFirst { case media: BundleEntry.Media =>
        TemporalSupport.Selection.Part(media.bundle.identity)
      }
      .toRight(TemporalInspectionCodecs.Error.Invalid)

  private def defaultMeasure(
      policy: storymodel4s.codec.SurfaceWorkspaceCodecs.Policy
  ): Either[TemporalInspectionCodecs.Error, TemporalQuery.Measure] =
    // The admitted surface packet fixes the family for each member, even when every row failed.
    policy.id.value match
      case "reference"      => Right(TemporalQuery.Measure.NormalizedScoreMass)
      case "reconstruction" => Right(TemporalQuery.Measure.ModelPosterior)
      case _                => invalid

  def open(
      session: TemporalInspectionCodecs.Session,
      policy: ArtifactId
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] =
    for
      selected <- defaultSelection(session)
      coordinate <- TemporalSupport
        .select(session.workspace.source, selected)
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      bins <- defaultBins(coordinate.axis)
      measure <- session.workspace
        .policy(policy)
        .toRight(TemporalInspectionCodecs.Error.ForeignPolicy)
        .flatMap(defaultMeasure)
      request = TemporalProjection.Request(
        selected,
        session.workspace.source.targets.map(target =>
          TemporalQuery.Declaration(
            target.ref,
            TemporalQuery.Assumption.SuppliedSupportContainsReferent,
            TemporalQuery.Allocation.SupportOnly
          )
        ),
        bins
      )
      projection <- session.compile(policy, measure, request)
      roi <- bins.traverse((a, b) =>
        TemporalQuery.Region
          .onTimeAxis(coordinate.axis, Vector(a -> b), Vector.empty)
          .left
          .map(TemporalInspectionCodecs.Error.Query(_))
      )
      result <- checked(session, projection, Set.empty, roi, 10)
    yield result

  private def switchPolicy(
      session: TemporalInspectionCodecs.Session,
      policy: ArtifactId,
      request: TemporalProjection.Request,
      roi: Vector[TemporalQuery.Region],
      topK: Int,
      selection: Set[Address]
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] =
    for
      supplied <- session.workspace
        .policy(policy)
        .toRight(TemporalInspectionCodecs.Error.ForeignPolicy)
      measure <- defaultMeasure(supplied)
      result <- compile(session, policy, measure, request, selection, roi, topK)
    yield result

  private def descriptor(
      session: TemporalInspectionCodecs.Session,
      selection: Set[Address],
      roi: Vector[TemporalQuery.Region],
      topK: Int,
      view: String
  ): Either[TemporalInspectionCodecs.Error, String] =
    // The independent ROI survives separately from projection bins and exact producer request.
    Right(
      Canonical.print(
        Json.obj(
          "schemaVersion" -> Json.fromString(Version),
          "session" -> Json.fromString(session.digest.hex),
          "selection" -> Json.fromValues(
            selection.toVector.sortBy(_.render).map(a => Json.fromString(a.render))
          ),
          "roi" -> Json.fromValues(
            roi.map(region =>
              Json.obj(
                "intervals" -> Json.fromValues(
                  region.geometry.toVector
                    .flatMap(_.intervals)
                    .map(i => Json.arr(Json.fromLong(i.start), Json.fromLong(i.endExclusive)))
                ),
                "points" -> Json.fromValues(
                  region.geometry.toVector.flatMap(_.points).map(p => Json.fromLong(p.at))
                )
              )
            )
          ),
          "topK" -> Json.fromInt(topK),
          "view" -> Json.fromString(view)
        )
      )
    )

  def restore(
      text: String,
      session: TemporalInspectionCodecs.Session
  ): Either[TemporalInspectionCodecs.Error, TemporalWorkspaceController] =
    for
      json <- Canonical.parse(text).left.map(_ => TemporalInspectionCodecs.Error.Invalid)
      schema <- json.hcursor
        .get[String]("schemaVersion")
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      _ <- Either.cond(schema == Version, (), TemporalInspectionCodecs.Error.ForeignView)
      digest <- json.hcursor
        .get[String]("session")
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      _ <- Either.cond(digest == session.digest.hex, (), TemporalInspectionCodecs.Error.ForeignView)
      view <- json.hcursor.get[String]("view").left.map(_ => TemporalInspectionCodecs.Error.Invalid)
      saved <- Canonical.parse(view).left.map(_ => TemporalInspectionCodecs.Error.Invalid)
      requestBytes <- saved.hcursor
        .get[String]("request")
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      request <- storymodel4s.codec.TemporalProjectionCodecs
        .decodeRequest(requestBytes, session.workspace.source)
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      _ <- bounded(request.bins.size)
      projection <- TemporalInspectionCodecs.restoreView(view, session)
      topK <- json.hcursor.get[Int]("topK").left.map(_ => TemporalInspectionCodecs.Error.Invalid)
      rendered <- json.hcursor
        .get[Vector[String]]("selection")
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      encodedRoi <- json.hcursor
        .get[Vector[Json]]("roi")
        .left
        .map(_ => TemporalInspectionCodecs.Error.Invalid)
      _ <- bounded(encodedRoi.size)
      roiPieces <- encodedRoi.traverse { value =>
        for
          intervals <- value.hcursor
            .get[Vector[Json]]("intervals")
            .left
            .map(_ => TemporalInspectionCodecs.Error.Invalid)
          points <- value.hcursor
            .get[Vector[Json]]("points")
            .left
            .map(_ => TemporalInspectionCodecs.Error.Invalid)
        yield intervals.size.toLong + points.size.toLong
      }
      _ <- Either.cond(
        roiPieces.sum <= MaximumRegions,
        (),
        TemporalInspectionCodecs.Error.Grid(TemporalInspection.Refusal.TooManyBins)
      )
      roi <- encodedRoi.traverse { value =>
        for
          intervals <- value.hcursor
            .get[Vector[Vector[Long]]]("intervals")
            .left
            .map(_ => TemporalInspectionCodecs.Error.Invalid)
          points <- value.hcursor
            .get[Vector[Long]]("points")
            .left
            .map(_ => TemporalInspectionCodecs.Error.Invalid)
          region <- TemporalQuery.Region
            .onTimeAxis(
              projection.coordinate.axis,
              intervals.collect { case Vector(a, b) => a -> b },
              points
            )
            .left
            .map(TemporalInspectionCodecs.Error.Query(_))
          _ <- Either.cond(
            intervals.forall(_.size == 2),
            (),
            TemporalInspectionCodecs.Error.Invalid
          )
        yield region
      }
      allowed = addresses(session)
      selection = rendered.toSet.flatMap(value => Address.parse(value).toOption.filter(allowed))
      _ <- Either.cond(
        selection.size == rendered.distinct.size,
        (),
        TemporalInspectionCodecs.Error.Invalid
      )
      result <- checked(session, projection, selection, roi, topK)
      _ <- Either.cond(
        result.encode == Right(text.trim),
        (),
        TemporalInspectionCodecs.Error.ForeignView
      )
    yield result
