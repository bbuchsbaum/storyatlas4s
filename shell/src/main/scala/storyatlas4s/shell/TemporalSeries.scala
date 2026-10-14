package storyatlas4s.shell

import cats.syntax.all.*
import scala.collection.mutable
import storymodel4s.align.*
import storymodel4s.codec.TemporalInspectionCodecs
import storymodel4s.recall.*

/** Consumer-side rows for the recall-clock display. Values remain producer queries; this only keeps
  * their recall observations and refusal reasons together for the host.
  */
private[storyatlas4s] object TemporalSeries:
  private final case class CacheKey(
      projectionDigest: String,
      roi: Vector[TemporalQuery.Region],
      offset: Int,
      limit: Int
  )

  final case class Point(
      unit: RecallUnitId,
      observations: Vector[RecallTiming.Observation],
      regionMass: Either[String, Double],
      sourceWidePrecisionTicks: Either[String, BigInt]
  )

  final case class Readout(clock: RecallTiming.Clock, points: Vector[Point])

  private val cache =
    mutable.LinkedHashMap.empty[CacheKey, Either[TemporalInspectionCodecs.Error, Option[Readout]]]
  private val MaximumCachedPages = 8

  /** Reads one source-region result per recall unit without creating an aggregate, a clock, or a
    * value for an unavailable row. `None` means the packet has no declared recall clock.
    */
  def read(
      controller: TemporalWorkspaceController,
      offset: Int = 0,
      limit: Int = 24
  ): Either[TemporalInspectionCodecs.Error, Option[Readout]] =
    val key =
      CacheKey(controller.projection.digest.hex, controller.roi, offset.max(0), limit.max(0))
    cache.get(key).getOrElse {
      val result = readUncached(controller, key.offset, key.limit)
      cache.update(key, result)
      if cache.size > MaximumCachedPages then
        cache.remove(cache.head._1)
        ()
      result
    }

  private def readUncached(
      controller: TemporalWorkspaceController,
      offset: Int,
      limit: Int
  ): Either[TemporalInspectionCodecs.Error, Option[Readout]] =
    controller.session.timing.traverse { timing =>
      val projection = controller.projection
      TemporalQuery.Region
        .onTimeAxis(
          projection.coordinate.axis,
          controller.roi.flatMap(
            _.geometry.toVector.flatMap(_.intervals).map(i => i.start -> i.endExclusive)
          ),
          controller.roi.flatMap(_.geometry.toVector.flatMap(_.points).map(_.at))
        )
        .left
        .map(TemporalInspectionCodecs.Error.Query(_))
        .map { region =>
          val points = projection.rows.slice(offset, offset + limit).map { row =>
            val observations = controller.session.workspace.inventory.units
              .find(_.id == row.unit)
              .toVector
              .flatMap(_.words)
              .flatMap(word => timing.entry(word).map(_.observation))
            val queried = projection.query(row.unit, region)
            val mass = queried
              .map { result =>
                val ledger = result.prepared.ledger
                if ledger.supportWithoutAllocation > 0.0 && ledger.allocatedSource == 0.0 then
                  Left("Not allocated")
                else Right(result.allocatedRegionMass)
              }
              .fold(reason => Left(reason.toString), identity)
            // This concentration is deliberately evaluated on the producer's unchanged source
            // allocation. The ROI conditions region mass only; it does not renormalize this width.
            val sourceWidePrecision = queried
              .flatMap(result =>
                TemporalConcentration
                  .shortestInspectionWindow(
                    result.prepared,
                    TemporalConcentration.Target.ShareOfAllocatedSource(0.5)
                  )
                  .map(_.width)
              )
              .left
              .map(_.toString)
            Point(row.unit, observations, mass, sourceWidePrecision)
          }
          Readout(timing.clock, points)
        }
    }
