package storyatlas4s.app

import cats.syntax.all.*
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import storyatlas4s.shell.*
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, TemporalInspectionCodecs, TemporalProjectionCodecs}
import storymodel4s.core.*
import storymodel4s.recall.*

/** Linked temporal inspection draws producer quantities on independently declared source and recall
  * axes.
  */
object TemporalWorkspaceView:
  private def number(value: Double): String = if value == 0 then "0" else f"$value%.5g"
  private def rational(value: ExactRational): String = s"${value.numerator}/${value.denominator}"
  private def regionText(region: TemporalQuery.Region): String = region.geometry.fold("empty") {
    g =>
      (g.intervals.map(i => s"${i.start}:${i.endExclusive}") ++ g.points.map(p => s"@${p.at}"))
        .mkString(", ")
  }
  private def csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
  private def download(name: String, content: String, mime: String): Unit =
    val blob = new dom.Blob(js.Array(content), new dom.BlobPropertyBag { `type` = mime })
    val url = dom.URL.createObjectURL(blob)
    val anchor = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    anchor.href = url
    anchor.download = name
    val _ = dom.document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    val _ = dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 1000)

  def apply(
      surface: SurfaceWorkspaceController,
      dispatch: SurfaceWorkspaceAction => Unit,
      notice: Var[String],
      page: Var[Int]
  ): HtmlElement = surface.temporal match
    case None =>
      sectionTag(
        cls("temporal-unavailable"),
        h2("Time views"),
        p(
          "This packet does not supply an inspectable temporal axis. Source and recall evidence remain available below."
        )
      )
    case Some(current) => render(surface, current, dispatch, notice, page)

  private def render(
      surface: SurfaceWorkspaceController,
      current: TemporalWorkspaceController,
      dispatch: SurfaceWorkspaceAction => Unit,
      notice: Var[String],
      page: Var[Int]
  ): HtmlElement =
    val projection = current.projection
    val workspace = current.session.workspace
    val axis = projection.coordinate.axis
    def action(value: TemporalWorkspaceAction): Unit = dispatch(
      SurfaceWorkspaceAction.Temporal(value)
    )
    def refused(): Unit = notice.set(
      "These coordinates are unavailable on the selected axis. The current view has been retained."
    )
    val extent = axis.extent.asInstanceOf[AxisExtent.PlaybackTicks]
    val selections = workspace.source.bundles.toVector.collect { case m: BundleEntry.Media =>
      (s"Part ${m.bundle.id.value}", TemporalSupport.Selection.Part(m.bundle.identity))
    } ++ workspace.source.composition.toVector.flatMap(
      _.composed.mappings
        .collect { case track: TrackComposition =>
          track.segments.toVector.map(s =>
            (
              s"Occurrence ${s.occurrence.value}",
              TemporalSupport.Selection.Occurrence(track.identity, s.occurrence)
            )
          )
        }
        .flatten
    )
    val sourceStart = Var(projection.request.bins.headOption.fold(extent.start)(_._1).toString)
    val sourceEnd = Var(projection.request.bins.lastOption.fold(extent.endExclusive)(_._2).toString)
    val binCount = Var(projection.request.bins.size.toString)
    val roiInput = Var(current.roi.map(regionText).mkString(", "))
    val recallStart = Var(projection.request.recallWindow.fold("0")(w => rational(w.start)))
    val recallEnd = Var(projection.request.recallWindow.fold("10")(w => rational(w.endExclusive)))
    val top = Var(current.topK.toString)
    def seconds(text: String): Option[ExactRational] = text.split("/", -1).toVector match
      case Vector(n, d) =>
        for x <- n.trim.toLongOption; y <- d.trim.toLongOption; r <- ExactRational.of(x, y).toOption
        yield r
      case _ => RecallTiming.decimalSeconds(text.trim).toOption
    def geometry(text: String): Option[TemporalQuery.Region] =
      val fields = text.split(',').toVector.map(_.trim).filter(_.nonEmpty)
      val parsed = fields.traverse { field =>
        if field.startsWith("@") then field.drop(1).toLongOption.map(n => Right(n))
        else
          field.split(':').toVector match
            case Vector(a, b) => (a.toLongOption, b.toLongOption).mapN((x, y) => Left(x -> y))
            case _            => None
      }
      parsed.flatMap(values =>
        TemporalQuery.Region
          .onTimeAxis(
            axis,
            values.collect { case Left(i) => i },
            values.collect { case Right(p) => p }
          )
          .toOption
      )
    def requestBins(): Unit =
      val parsed = for
        start <- sourceStart.now().toLongOption
        end <- sourceEnd.now().toLongOption
        count <- binCount.now().toIntOption.filter(n => n > 0 && n <= 128)
        length = BigInt(end) - start
        if length >= count
      yield Vector.tabulate(count)(i =>
        (
          (BigInt(start) + length * i / count).toLong,
          (BigInt(start) + length * (i + 1) / count).toLong
        )
      )
      parsed.fold(refused())(bins => action(TemporalWorkspaceAction.Bins(bins)))
    def field(labelText: String, state: Var[String], fieldId: String): HtmlElement = label(
      span(labelText),
      input(idAttr(fieldId), typ("text"), value := state.now(), onInput.mapToValue --> state.writer)
    )
    // Pixel layout uses supplied observations only. Exact rationals remain in labels and receipts.
    def decimal(t: ExactRational): Double = t.numerator.toDouble / t.denominator.toDouble
    val observed = current.session.timing.toVector.flatMap(_.entries.flatMap(_.observation match
      case RecallTiming.Observation.Interval(a, b, _) => Vector(decimal(a), decimal(b))
      case RecallTiming.Observation.OnsetOnly(at, _)  => Vector(decimal(at))
      case _                                          => Vector.empty))
    val recallLimits = projection.request.recallWindow
      .map(w => decimal(w.start) -> decimal(w.endExclusive))
      .orElse(Option.when(observed.nonEmpty)(observed.min -> observed.max))
    def timingLane(unit: RecallUnitId): HtmlElement =
      val entries = for
        timing <- current.session.timing.toVector
        u <- workspace.inventory.units.find(_.id == unit).toVector
        word <- u.words
        entry <- timing.entry(word).toVector
      yield entry
      val labels = entries.map(_.observation match
        case RecallTiming.Observation.Interval(a, b, _) =>
          s"[${rational(a)}, ${rational(b)}) seconds"
        case RecallTiming.Observation.OnsetOnly(at, _) =>
          s"onset ${rational(at)} seconds; duration unavailable"
        case RecallTiming.Observation.Missing(reason) => s"untimed: $reason")
      val marks = entries.flatMap(_.observation match
        case RecallTiming.Observation.Interval(a, b, _) => Some((decimal(a), decimal(b), false))
        case RecallTiming.Observation.OnsetOnly(at, _)  => Some((decimal(at), decimal(at), true))
        case _                                          => None)
      val visibleMarks = marks.filter { (a, b, point) =>
        recallLimits.exists { (lo, hi) =>
          if point then a >= lo && (a < hi || projection.request.recallWindow.isEmpty && a == hi)
          else b > lo && a < hi
        }
      }
      div(
        cls("temporal-recall-lane"),
        role("img"),
        aria.label(labels.mkString("; ")),
        title(labels.mkString("; ")),
        if marks.isEmpty then span("Timing unavailable")
        else if visibleMarks.isEmpty then span("Outside recall window")
        else
          div(visibleMarks.map { (a, b, point) =>
            val (lo, hi) = recallLimits.get
            val left = if hi > lo then ((a - lo) / (hi - lo) * 100).max(0).min(100) else 50.0
            val width =
              if hi > lo then ((b.max(lo).min(hi) - a.max(lo).min(hi)) / (hi - lo) * 100).max(0)
              else 0.0
            span(
              cls(if point then "temporal-onset" else "temporal-interval"),
              styleAttr(s"left:$left%;width:$width%;")
            )
          })
      )
    val rowsPerPage = 24
    val columnsPerPage = 32
    val columnPage = Var(0)
    def currentRegions(index: Int) =
      projection.regions.zipWithIndex.slice(index * columnsPerPage, (index + 1) * columnsPerPage)
    val selectedRow = surface.state.recall.flatMap(id => projection.rows.find(_.unit == id))
    val visibleRows = page.signal.map { index =>
      val safe = index.min((projection.rows.size - 1).max(0) / rowsPerPage)
      projection.rows.slice(safe * rowsPerPage, (safe + 1) * rowsPerPage)
    }
    def query(
        row: TemporalInspectionCodecs.Row
    ): Either[TemporalInspectionCodecs.Error, TemporalQuery.InspectionReadout] =
      for
        union <- TemporalQuery.Region
          .onTimeAxis(
            axis,
            current.roi.flatMap(
              _.geometry.toVector.flatMap(_.intervals).map(i => i.start -> i.endExclusive)
            ),
            current.roi.flatMap(_.geometry.toVector.flatMap(_.points).map(_.at))
          )
          .left
          .map(TemporalInspectionCodecs.Error.Query(_))
        result <- projection.query(row.unit, union)
      yield result
    def ordinal(id: RecallUnitId): String =
      workspace.inventory.units.find(_.id == id).fold(id.value)(u => s"Recall ${u.ordinal + 1}")
    def readoutTable(row: TemporalInspectionCodecs.Row): HtmlElement = query(row) match
      case Left(reason)  => p(cls("temporal-missing"), s"${ordinal(row.unit)}: ${reason.toString}")
      case Right(result) =>
        val ledger = result.prepared.ledger
        val ranked = result.topK(current.topK).toOption.get
        val precision = TemporalConcentration.shortestInspectionWindow(
          result.prepared,
          TemporalConcentration.Target.ShareOfAllocatedSource(0.5)
        )
        div(
          h3(ordinal(row.unit)),
          blockQuote(workspace.recallText(row.unit).getOrElse("Recall text unavailable")),
          dl(
            cls("temporal-accounting"),
            dt("Allocated contribution inside region"),
            dd(
              if ledger.supportWithoutAllocation > 0 && ledger.allocatedSource == 0 then
                "Not allocated"
              else number(result.allocatedRegionMass)
            ),
            dt("Conditional support bounds"),
            dd(s"${number(result.resolvedBounds.lower)} to ${number(result.resolvedBounds.upper)}"),
            dt("Source quantity allocated"),
            dd(number(ledger.allocatedSource)),
            dt("Support without allocation"),
            dd(number(ledger.supportWithoutAllocation)),
            dt("Location unavailable"),
            dd(number(ledger.unavailableLocation)),
            dt("External alternatives"),
            dd(ledger.external.map((state, mass) => s"$state ${number(mass)}").mkString("; ")),
            dt("Omitted by top-k"),
            dd(
              s"${number(ranked.omittedWeight)} original quantity; ${number(ranked.omittedAllocatedRegionMass)} inside region"
            ),
            dt("Shortest interval for 50% of allocated source quantity"),
            dd(
              precision.fold(
                r => s"Unavailable: $r",
                w => s"${w.width} ticks · ${regionText(w.readout.region)}"
              )
            )
          ),
          table(
            thead(tr(th("Alternative"), th("Original quantity"), th("In region"))),
            tbody(ranked.retained.map { c =>
              val destination = c.component.alternative.destination
              tr(
                td(destination match
                  case Destination.Target(ref) =>
                    button(
                      typ("button"),
                      workspace.sourceText(ref).getOrElse(ref.key),
                      onClick --> (_ => dispatch(SurfaceWorkspaceAction.Source(ref)))
                    )
                  case Destination.External(state) => span(s"External: $state")),
                td(number(c.component.weight)),
                td(c.allocated.fold("Not allocated")(number))
              )
            })
          )
        )
    def exportRows: Vector[Vector[String]] =
      Vector(
        Vector(
          "unit",
          "measure",
          "bin",
          "allocated",
          "lower",
          "upper",
          "processing",
          "allocation_status",
          "allocated_source",
          "support_without_allocation",
          "location_unavailable",
          "external",
          "normalization_scope",
          "projection",
          "policy_bytes"
        )
      ) ++
        projection.rows.flatMap { row =>
          projection.regions.zipWithIndex.map { (region, index) =>
            val grid = row.grid.toOption
            val ledger = grid.map(_.prepared.ledger)
            val allocationStatus = ledger.fold("not-computed") { l =>
              if l.supportWithoutAllocation + l.unavailableLocation == 0 then "available"
              else if l.allocatedSource > 0 then "partial"
              else "unavailable"
            }
            Vector(
              row.unit.value,
              projection.measure.toString,
              regionText(region),
              grid
                .filter(_ => allocationStatus == "available" || allocationStatus == "partial")
                .flatMap(_.allocatedAt(index))
                .fold("")(_.toString),
              grid.flatMap(_.boundsAt(index)).fold("")(b => b._1.toString),
              grid.flatMap(_.boundsAt(index)).fold("")(b => b._2.toString),
              row.processing.toString,
              allocationStatus,
              ledger.fold("")(_.allocatedSource.toString),
              ledger.fold("")(_.supportWithoutAllocation.toString),
              ledger.fold("")(_.unavailableLocation.toString),
              ledger.fold("")(_.external.map(_._2).sum.toString),
              "original-row",
              projection.digest.hex,
              projection.policy.byteDigest.hex
            )
          }
        }
    def exportCurrent(kind: String): Unit = TemporalInspectionCodecs.saveView(projection) match
      case Left(_)        => notice.set("This packet does not permit export.")
      case Right(receipt) =>
        kind match
          case "csv" =>
            download(
              "temporal-quantities.csv",
              exportRows.map(_.map(csvCell).mkString(",")).mkString("\n") + "\n",
              "text/csv;charset=utf-8"
            )
          case "receipt" =>
            current.encode match
              case Right(bytes) =>
                download("temporal-view.json", bytes, "application/json;charset=utf-8")
              case Left(_) => notice.set("This packet does not permit export.")
          case _ =>
            val rows =
              projection.rows.slice(page.now() * rowsPerPage, (page.now() + 1) * rowsPerPage)
            val regions = currentRegions(columnPage.now())
            val cells = rows.zipWithIndex.flatMap { (row, y) =>
              regions.zipWithIndex.map { case ((region, index), x) =>
                val grid = row.grid.toOption
                val allocated =
                  grid.filter(_.prepared.ledger.allocatedSource > 0).flatMap(_.allocatedAt(index))
                val bounds = grid.flatMap(_.boundsAt(index))
                val label =
                  allocated.fold(bounds.fold("Unavailable")(b => s"[${b._1}, ${b._2}]"))(_.toString)
                val fill =
                  if allocated.nonEmpty then
                    s"rgba(23,63,54,${0.08 + 0.92 * allocated.get.max(0).min(1)})"
                  else "#e6e3d8"
                s"<g><title>${escape(s"${ordinal(row.unit)}; ${regionText(region)}; $label; ${row.processing}")}</title><rect x='${150 + x * 36}' y='${120 + y * 28}' width='34' height='26' fill='$fill'/></g>"
              }
            }.mkString
            val labels = rows.zipWithIndex
              .map((row, y) =>
                s"<text x='16' y='${138 + y * 28}'>${escape(ordinal(row.unit))}</text>"
              )
              .mkString
            val bins = regions.zipWithIndex.map { case ((_, index), x) =>
              s"<text x='${150 + x * 36}' y='108'>${index + 1}</text>"
            }.mkString
            val height = rows.size * 28 + 170
            val width = (regions.size * 36 + 180).max(900)
            val svg = s"""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" role="img"><title>Temporal quantities in the displayed rows and source bins</title><desc>${escape(
                receipt
              )}</desc><rect width="100%" height="100%" fill="#fbfaf6"/><g fill="#173f36" font-family="sans-serif" font-size="12"><text x="16" y="24">${escape(
                projection.measure.toString
              )} · original row denominator</text><text x="16" y="44">Green intensity: allocated quantity 0 to 1. Beige: support bounds or unavailable; exact values in cell titles.</text><text x="16" y="64">${escape(
                s"Projection ${projection.digest.hex}"
              )}</text><text x="16" y="84">Source bin numbers; equal display width. Export table and view receipt retain exact extents.</text>$bins$labels$cells</g></svg>"""
            download("temporal-quantities.svg", svg, "image/svg+xml;charset=utf-8")
        notice.set("Exported the supplied quantities with the current temporal view.")
    val allocations = projection.request.declarations.map(_.allocation).distinct
    val allocationKey = allocations match
      case Vector(TemporalQuery.Allocation.SupportOnly)                    => "support"
      case Vector(TemporalQuery.Allocation.UniformIntervals)               => "intervals"
      case Vector(TemporalQuery.Allocation.UniformPoints)                  => "points"
      case Vector(TemporalQuery.Allocation.Mixture(value)) if value == 0.5 => "mixed"
      case _                                                               => "supplied"
    val suppliedLabel =
      if allocations.size == 1 then s"Supplied: ${allocations.head}"
      else "Supplied per-target allocations"
    sectionTag(
      idAttr("temporal-workspace"),
      cls("temporal-workspace"),
      headerTag(
        h2("Where does the recall point?"),
        p(
          "Inspect source-time support and supplied recall timing. The two clocks remain independent."
        )
      ),
      div(
        cls("temporal-controls"),
        label(
          span("Source axis"),
          select(
            idAttr("temporal-axis"),
            selections.map((name, selection) =>
              option(
                value := selections.indexWhere(_._2 == selection).toString,
                selected := (selection == projection.request.selection),
                name
              )
            ),
            onChange.mapToValue --> { text =>
              text.toIntOption.flatMap(selections.lift).foreach { (_, selection) =>
                TemporalSupport.select(workspace.source, selection).toOption.foreach { coordinate =>
                  coordinate.axis.extent match
                    case ticks: AxisExtent.PlaybackTicks =>
                      action(
                        TemporalWorkspaceAction.Request(
                          projection.request.copy(
                            selection = selection,
                            bins = Vector(ticks.start -> ticks.endExclusive)
                          )
                        )
                      )
                    case _ => refused()
                }
              }
            }
          )
        ),
        label(
          span("Allocation assumption"),
          select(
            idAttr("temporal-allocation"),
            option(
              value := "supplied",
              selected := (allocationKey == "supplied"),
              disabled(true),
              suppliedLabel
            ),
            Vector(
              "support" -> "Support only",
              "intervals" -> "Uniform within intervals",
              "points" -> "Uniform over points",
              "mixed" -> "Half interval / half point"
            ).map((key, text) =>
              option(
                value := key,
                selected := (allocationKey == key),
                text
              )
            ),
            onChange.mapToValue --> { text =>
              action(TemporalWorkspaceAction.Allocation(text match
                case "intervals" => TemporalQuery.Allocation.UniformIntervals
                case "points"    => TemporalQuery.Allocation.UniformPoints
                case "mixed"     => TemporalQuery.Allocation.Mixture(0.5)
                case _           => TemporalQuery.Allocation.SupportOnly))
            }
          )
        ),
        field("Source start (ticks)", sourceStart, "temporal-source-start"),
        field("Source end (exclusive)", sourceEnd, "temporal-source-end"),
        field("Bins (1–128)", binCount, "temporal-bins"),
        button(typ("button"), "Apply source window", onClick --> (_ => requestBins())),
        field("Region: start:end, @point", roiInput, "temporal-region"),
        button(
          typ("button"),
          idAttr("temporal-apply-region"),
          "Inspect region",
          onClick --> { _ =>
            geometry(roiInput.now()).fold(refused())(region =>
              action(TemporalWorkspaceAction.Region(Vector(region)))
            )
          }
        ),
        field("Top alternatives", top, "temporal-topk"),
        button(
          typ("button"),
          idAttr("temporal-apply-topk"),
          "Apply top-k",
          onClick --> { _ =>
            top
              .now()
              .toIntOption
              .filter(_ >= 0)
              .fold(refused())(k => action(TemporalWorkspaceAction.TopK(k)))
          }
        )
      ),
      p(
        cls("temporal-contract"),
        s"${projection.measure} · source ticks on ${axis.id.value} · ${extent.timebase}. Support bounds assume the supplied support contains the referent. Allocation requires the selected additional assumption. No value is renormalized to the visible window."
      ),
      div(
        cls("temporal-controls"),
        h3("Recall clock window"),
        field("Recall start (seconds)", recallStart, "temporal-recall-start"),
        field("Recall end (exclusive)", recallEnd, "temporal-recall-end"),
        button(
          typ("button"),
          disabled := current.session.timing.isEmpty,
          idAttr("temporal-apply-recall"),
          "Apply recall window",
          onClick --> { _ =>
            (current.session.timing, seconds(recallStart.now()), seconds(recallEnd.now())) match
              case (Some(timing), Some(start), Some(end)) =>
                action(
                  TemporalWorkspaceAction.RecallWindow(
                    Some(TemporalProjection.RecallWindow(timing.clock.digest, start, end))
                  )
                )
              case _ => refused()
          }
        ),
        button(
          typ("button"),
          idAttr("temporal-apply-clear-recall"),
          "Clear recall window",
          onClick --> (_ => action(TemporalWorkspaceAction.RecallWindow(None)))
        )
      ),
      p(
        cls("temporal-contract"),
        current.session.timing.fold(
          "Original recall timing was not supplied. Units are shown in inventory order; this is not elapsed time."
        )(t =>
          s"Recall clock ${t.clock.key.value}; origin ${t.clock.origin}; recording ${t.clock.recording}. Timing state: ${projection.timingState}. Clock uncertainty is not modeled."
        )
      ),
      p(
        cls("temporal-contract"),
        recallLimits.fold("Recall-time rail: no supplied observations.")((a, b) =>
          s"Recall-time rail: $a to $b seconds; bars are supplied word intervals, ticks are onset-only observations. Gaps remain gaps."
        )
      ),
      div(
        cls("temporal-grid-scroll"),
        tabIndex(0),
        aria.label("Temporal mapping table; scroll for source bins"),
        child <-- visibleRows.combineWith(columnPage.signal).map { (rows, columnIndex) =>
          val regions = currentRegions(columnIndex)
          table(
            cls("temporal-grid"),
            caption(
              s"${projection.measure}: original quantity per source bin. A dash means no allocated estimate; bracketed values are support bounds. Source-bin columns have equal display widths; exact tick extents are printed."
            ),
            thead(tr(th("Recall / supplied exposure"), regions.map((r, _) => th(regionText(r))))),
            tbody(rows.map { row =>
              tr(
                th(
                  button(
                    typ("button"),
                    aria.pressed := surface.state.recall.contains(row.unit).toString,
                    idAttr(s"temporal-recall-${row.unit.value}"),
                    ordinal(row.unit),
                    onClick --> (_ => dispatch(SurfaceWorkspaceAction.Recall(row.unit)))
                  ),
                  timingLane(row.unit),
                  small(
                    row.exposure.fold(s"${row.processing}; exposure unavailable")(e =>
                      s"${e.duration._1}/${e.duration._2} s; ${e.onsetEvents.size} onset-only; ${e.missingWords} untimed words"
                    )
                  )
                ),
                regions.map { (region, index) =>
                  val bounds = row.grid.toOption.flatMap(_.boundsAt(index))
                  val quantity = row.grid.toOption.flatMap(_.allocatedAt(index))
                  val hasAllocation =
                    row.grid.toOption.exists(_.prepared.ledger.allocatedSource > 0)
                  val text =
                    if hasAllocation then quantity.fold("—")(number)
                    else bounds.fold("—")(b => s"[${number(b._1)}, ${number(b._2)}]")
                  td(
                    button(
                      typ("button"),
                      cls(
                        if bounds.exists(_._2 > 0) then "temporal-supported" else "temporal-empty"
                      ),
                      aria.label(
                        s"${ordinal(row.unit)}, ${regionText(region)}: $text; ${row.processing}"
                      ),
                      text,
                      onClick --> { _ =>
                        dispatch(SurfaceWorkspaceAction.Recall(row.unit))
                        action(TemporalWorkspaceAction.Region(Vector(region)))
                      }
                    )
                  )
                }
              )
            })
          )
        }
      ),
      div(
        cls("temporal-controls"),
        button(
          typ("button"),
          "Previous bins",
          disabled <-- columnPage.signal.map(_ == 0),
          onClick --> (_ => columnPage.update(n => (n - 1).max(0)))
        ),
        span(
          child.text <-- columnPage.signal.map(n =>
            s"Bins ${n * columnsPerPage + 1}–${((n + 1) * columnsPerPage).min(projection.regions.size)} of ${projection.regions.size}"
          )
        ),
        button(
          typ("button"),
          "Next bins",
          disabled <-- columnPage.signal.map(n =>
            (n + 1) * columnsPerPage >= projection.regions.size
          ),
          onClick --> (_ => columnPage.update(_ + 1))
        )
      ),
      div(
        cls("temporal-controls"),
        button(
          typ("button"),
          "Previous recalls",
          disabled <-- page.signal.map(_ == 0),
          onClick --> (_ => page.update(n => (n - 1).max(0)))
        ),
        span(
          child.text <-- page.signal.map(n =>
            s"Rows ${n * rowsPerPage + 1}–${((n + 1) * rowsPerPage).min(projection.rows.size)} of ${projection.rows.size}"
          )
        ),
        button(
          typ("button"),
          "Next recalls",
          disabled <-- page.signal.map(n => (n + 1) * rowsPerPage >= projection.rows.size),
          onClick --> (_ => page.update(_ + 1))
        )
      ),
      sectionTag(
        cls("temporal-inspector"),
        h3("Region and precision"),
        selectedRow.fold[HtmlElement](
          p(
            "Select a recall row to inspect original alternatives, omitted quantities and conditional precision."
          )
        )(readoutTable)
      ),
      surface.state.source.fold[HtmlElement](span()) { ref =>
        sectionTag(
          cls("temporal-inspector"),
          h3("Selected source support"),
          TemporalSupport
            .read(workspace.source, ref, projection.request.selection)
            .fold(
              reason => p(s"Support unavailable: $reason"),
              support =>
                div(
                  p(
                    s"${support.selected.state}; ${support.unavailableDescendants.size} descendants unavailable on this axis."
                  ),
                  pre(
                    support.nodes
                      .map(n =>
                        s"${n.ref.key}: ${n.state}; supplied ${n.suppliedIdentity.map(_.hex)}; included ${n.included}; excluded ${n.excluded}"
                      )
                      .mkString("\n")
                  )
                )
            )
        )
      },
      detailsTag(
        summaryTag("Clocks, exposure and provenance"),
        pre(
          s"Session ${current.session.digest.hex}\nPolicy bytes ${projection.policy.byteDigest.hex}\nProjection ${projection.digest.hex}\n${TemporalProjectionCodecs.encodeRequest(projection.request)}"
        ),
        projection.exposure.fold[HtmlElement](p("No recall exposure query is active."))(e =>
          p(
            s"Unassigned words: ${e.unassignedWords.size}; uncovered recall seconds: ${number(e.uncoveredSeconds)}; uncomputed duration: ${e.uncomputedDuration._1}/${e.uncomputedDuration._2}."
          )
        ),
        p(
          s"Joint path: ${projection.jointPath}. Candidate coverage and original inference assumptions:"
        ),
        pre(Canonical.print(projection.policy.declarations))
      ),
      div(
        cls("temporal-controls"),
        button(typ("button"), "Export table", onClick --> (_ => exportCurrent("csv"))),
        button(typ("button"), "Export figure", onClick --> (_ => exportCurrent("svg"))),
        button(typ("button"), "Export view receipt", onClick --> (_ => exportCurrent("receipt")))
      )
    )
