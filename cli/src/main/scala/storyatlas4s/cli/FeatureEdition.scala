package storyatlas4s.cli

import _root_.intaglio.svg.{SvgOptions, SvgRenderer}
import cats.syntax.all.*
import storyatlas4s.edition.EditionSpec
import storyatlas4s.intaglio.{AtlasLowering, GraphicsNames, PlateBox}
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.view.*

/** A measured reading surface and Atlas for every supplied Token/Sentence/Situation track.
  * Scientific values and supports come exclusively from the compiled feature marks.
  */
object FeatureEdition:
  private def scale(track: FeatureTrack[FeatureTarget, Double]): Option[FeatureScale] =
    val families = track.targets.map(TargetFamily.of).distinct
    val family = families match
      case Vector(one) => Some(one)
      case Vector() => track.derivation.flatMap(_.targetFamily)
      case _ => None
    family.flatMap {
      case TargetFamily.Token => Some(FeatureScale.SurfaceUnit(SurfaceUnitKind.Token))
      case TargetFamily.Sentence => Some(FeatureScale.SurfaceUnit(SurfaceUnitKind.Sentence))
      case TargetFamily.Situation => Some(FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations))
      case _ => None
    }

  def files(read: ReadModel): Either[String, Vector[EditionFile]] = read.features match
    case FeatureRecord.NotSupplied => Right(Vector.empty)
    case FeatureRecord.Supplied(_, tracks) =>
      val choices = tracks.flatMap(t => scale(t).map(s => (t, s, s"feature-${Checksum.ofText(t.space.id.value).hex}")))
      val links = choices.map((t, s, stem) => (s"$stem.html", s"${t.space.description} · ${s.label}"))
      choices.flatTraverse { (track, grain, stem) =>
        val spec = AtlasSpec(EditionSpec.workspaceZoom, ThreadPolicy.Selected, grain)
        for
          state <- CommonViewState.of(feature = Some(FeatureRendering.selection(track)),
            relationLayers = EditionSpec.relationLayers).left.map(_.message)
          config = AtlasCompiler.configurationChecksum(state, spec)
          scene <- read.validated match
            case Some(model) =>
              ViewProvenance.of(model.source.canonicalChecksum, model.receipt.map(_.contentChecksum),
                ViewBasis.ValidatedBuild, EditionSpec.compilerVersion, config).left.map(_.message).flatMap(p =>
                  AtlasCompiler(p).compileFeatures(model, state, spec, track).left.map(_.message))
            case None =>
              ViewProvenance.draftBuild(read.draftModel, EditionSpec.compilerVersion, config).left.map(_.message).flatMap(p =>
                AtlasCompiler(p).compileDraftFeatures(read.draftModel, state, spec, track).left.map(_.message))
          box <- PlateBox.of(1200, 1800).left.map(_.message)
          lowered <- AtlasLowering.lower(scene, read.draft.source.canonicalText.length, box).left.map(_.message)
          options <- SvgOptions(1200, 1800, Some(track.space.description)).left.map(_.message)
          svg <- SvgRenderer.render(lowered, options).left.map(_.message)
          html <- CodexHtml.unencodable(read.draft.source.canonicalText)
            .map(_.toString).toLeft(document(read.draft.source.canonicalText, scene, svg.value, links, s"$stem.html"))
        yield Vector(
          EditionFile(s"$stem.svg", "feature-atlas", grain.label, config, GraphicsNames.collect(lowered).size, svg.value, None),
          EditionFile(s"$stem.txt", "feature-twin", grain.label, config, scene.marks.size, scene.textualTwin, None),
          EditionFile(s"$stem.html", "feature-reading", grain.label, config, scene.marks.size, html, None)
        )
      }.map { pages =>
        val navigation = links.map((href, label) => s"<li><a href=\"${esc(href)}\">${esc(label)}</a></li>").mkString
        val state = if tracks.isEmpty then "The pipeline supplied a record with no measured tracks."
          else s"${choices.size} measured tracks available at Token, Sentence or Situation grain."
        pages :+ EditionFile("features.html", "feature-index", "measured tracks", Checksum.ofText(state), 0,
          s"<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Measured features</title><body><h1>Measured features</h1><p>${esc(state)}</p><ul>$navigation</ul><a href=\"workspace.html\">Story workspace</a></body></html>", None)
      }

  private def esc(s: String): String = CodexHtml.escapeAttr(s)

  private def document(text: String, scene: NarrativeScene, svg: String, links: Vector[(String, String)], current: String): String =
    val marks = scene.marks.collect { case f: VisualPrimitive.Feature => f }
    val title = marks.headOption.fold("Measured feature")(f => f.value.space.description)
    val navigation = links.map { (href, label) =>
      s"<a href=\"${esc(href)}\" ${if href == current then "aria-current=\"page\"" else ""}>${esc(label)}</a>"
    }.mkString
    def ink(f: VisualPrimitive.Feature): String =
      val v = f.value
      val shade = v.estimate.toOption.flatMap(n => v.domain.map(_.fraction(n))).fold(255)(x => (245 - 70 * x).round.toInt)
      val cls = v.estimate match
        case Estimate.Missing(MissingReason.Excluded) => "excluded"
        case Estimate.Missing(_) => "missing"
        case _ => "observed"
      s"class=\"$cls\" style=\"background-color:rgb($shade,$shade,$shade)\""
    def piece(f: VisualPrimitive.Feature, span: TextSpan): String =
      s"<a ${ink(f)} href=\"#${esc(f.identity.mark.value)}\" title=\"${esc(f.value.description)}\">${esc(text.substring(span.start, span.endExclusive))}</a>"
    val reading = scene.featureLayer.scale match
      case FeatureScale.NarrativeUnit(_) => marks.map { f =>
        val pieces = f.value.support.spans.toVector.map(s => piece(f, s)).mkString("<span class=\"gap\"> … </span>")
        s"<p class=\"situation\">$pieces</p>"
      }.mkString
      case _ =>
        val spans = marks.flatMap(f => f.value.support.spans.toVector.map(s => (f, s))).sortBy(_._2.start)
        val out = new StringBuilder
        var cursor = 0
        spans.foreach { (f, s) =>
          out.append(esc(text.substring(cursor, s.start)))
          out.append(piece(f, s))
          cursor = s.endExclusive
        }
        out.append(esc(text.substring(cursor))).toString
    val inspector = marks.map { f =>
      s"<details id=\"${esc(f.identity.mark.value)}\"><summary>${esc(f.value.estimate.toString)} · ${esc(f.value.target.toString)}</summary><p>${esc(f.value.description)}</p><code>${esc(f.address.render)}</code></details>"
    }.mkString
    val basis = scene.provenance.draft.fold(scene.provenance.basis.label)(p => s"Draft — ${p.label}")
    s"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>${esc(title)} — StoryAtlas</title><style>
      body{margin:0;background:#f6f3eb;color:#263a36;font:16px/1.65 Georgia,serif}header{padding:30px 5vw;border-bottom:1px solid #bfc6bd}
      h1{font-size:34px;line-height:1.2;margin:12px 0}nav{display:flex;flex-wrap:wrap;gap:6px 20px;font:13px/1.6 system-ui}a{color:inherit}nav [aria-current]{font-weight:bold;text-decoration-thickness:3px}
      main{display:grid;grid-template-columns:minmax(320px,1fr) minmax(480px,1.25fr);gap:36px;padding:30px 4vw}article{white-space:pre-wrap;font-size:19px;line-height:2.1}
      article a{text-decoration:none;border-bottom:2px solid #6c857b}article a:focus,article a:hover{outline:2px solid #005a4c}
      .missing{background-image:repeating-linear-gradient(135deg,transparent 0 4px,#b8bdb6 4px 5px);border-bottom:2px dashed #374b45!important}
      .excluded{border-bottom:2px dotted #374b45!important}.situation{padding:9px 0;border-bottom:1px solid #bfc6bd}.gap{color:#666;font-size:13px}
      .plate svg{width:100%;height:auto}.inspector{padding:30px 5vw}details{border-top:1px solid #bfc6bd;padding:10px 0}details:target{outline:2px solid #005a4c}details p{overflow-wrap:anywhere}code{font-size:12px;overflow-wrap:anywhere}
      @media(max-width:850px){main{display:block}.plate{margin-top:30px}}@media print{nav{display:none}main{display:block}}
      </style></head><body><header><a href="features.html">Measured features</a> · <a href="workspace.html">Story workspace</a><h1>${esc(title)}</h1>
      <p>${esc(scene.featureLayer.scale.label)} · ${esc(basis)}</p><nav aria-label="Measure and grain">$navigation</nav>
      <p>Value is shade; hatching is missing; a dotted underline is excluded. The plate's separate lower bars show coverage. Select words to inspect their recorded value and support. Aggregate circularity is not assessed.</p></header>
      <main><article aria-label="Measured source">$reading</article><section class="plate" aria-label="Measured atlas">$svg</section></main>
      <section class="inspector"><h2>Measurements and evidence</h2>$inspector</section></body></html>"""
