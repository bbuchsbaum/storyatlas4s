package storyatlas4s.edition

/** The standalone Recall Voyage page: one HTML file that opens from the filesystem.
  *
  * It carries the voyage document as an inline JSON script, the static plate the CLI lowered and
  * rendered as a no-script fallback, and loads `app.js` beside it, which mounts the interactive
  * pane over the same document. No external resource: no font, no stylesheet, no CDN, no fetch
  * (storyatlas4s design contract). The stylesheet is the one the owner's reference page used, with
  * its web fonts replaced by system stacks.
  */
object VoyagePage:
  val DocumentElementId: String = "voyage-document"
  val MountElementId: String = "app"

  /** Escape a JSON document for an inline `<script type="application/json">` body. */
  def inlineJson(json: String): String =
    json.replace("</", "<\\/").replace("<!--", "<\\!--")

  def render(documentJson: String, staticSvg: String, title: String): String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>${escape(title)}</title>
       |<style>
       |$css
       |</style>
       |</head>
       |<body>
       |<script type="application/json" id="$DocumentElementId">${inlineJson(documentJson)}</script>
       |<div id="$MountElementId" class="page">
       |<header class="top"><div class="masthead"><div class="eyebrow">storyatlas4s · recall voyage · static plate</div>
       |<h1>${escape(
        title
      )}</h1><p>Open this page with <code>app.js</code> beside it for the interactive pane; this is the plate the edition lowered.</p></div></header>
       |<section class="panel"><div class="scroller">$staticSvg</div></section>
       |</div>
       |<script src="app.js"></script>
       |</body>
       |</html>
       |""".stripMargin

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  val css: String =
    """:root {
      |  --ground: #F4F5F3; --surface: #FFFFFF; --surface-2: #EDEFEC; --ink: #1B2126; --ink-2: #4B565F; --muted: #7A858F;
      |  --hair: #D5DAD8; --hair-2: #E6E9E6;
      |  --model: #1B7FA3; --model-soft: rgba(27,127,163,0.16); --gold: #C9922E; --gold-band: rgba(201,146,46,0.17);
      |  --adj: #6B4FBB; --external: #8A939D; --raw: #9AA3AB; --focus: #1B7FA3;
      |  --sans: "IBM Plex Sans", "Helvetica Neue", Arial, sans-serif;
      |  --mono: "IBM Plex Mono", "SFMono-Regular", Menlo, Consolas, monospace;
      |  --serif: "Source Serif 4", Georgia, "Times New Roman", serif;
      |}
      |* { box-sizing: border-box; }
      |body { margin: 0; background: var(--ground); color: var(--ink); font: 14px/1.45 var(--sans); }
      |h1, h2, h3 { margin: 0; }
      |h1 { font-size: 22px; font-weight: 600; letter-spacing: -0.01em; }
      |h2 { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; color: var(--ink-2); }
      |.num { font-family: var(--mono); font-variant-numeric: tabular-nums; }
      |button, select, input { font: inherit; color: inherit; }
      |:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
      |.page { max-width: 1480px; margin: 0 auto; padding: 20px 24px 40px; display: grid; gap: 16px; }
      |header.top { display: grid; grid-template-columns: 1fr auto; gap: 16px 32px; align-items: end; }
      |.masthead p { margin: 4px 0 0; color: var(--ink-2); max-width: 64ch; }
      |.masthead .eyebrow { font-size: 11px; text-transform: uppercase; letter-spacing: 0.1em; color: var(--muted); margin-bottom: 6px; }
      |.controls { display: flex; flex-wrap: wrap; gap: 10px 18px; align-items: center; justify-content: flex-end; }
      |.controls label { display: inline-flex; align-items: center; gap: 6px; color: var(--ink-2); }
      |.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
      |.stat { background: var(--surface); border: 1px solid var(--hair); border-radius: 8px; padding: 10px 12px; display: grid; gap: 2px; }
      |.stat .k { font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); }
      |.stat .v { font-family: var(--mono); font-size: 20px; font-variant-numeric: tabular-nums; }
      |.stat .s { font-size: 12px; color: var(--ink-2); }
      |.panes { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
      |@media (max-width: 1100px) { .panes { grid-template-columns: 1fr; } }
      |.panel { background: var(--surface); border: 1px solid var(--hair); border-radius: 10px; }
      |.panel > .head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; padding: 12px 16px 8px; }
      |.panel > .head .hint { color: var(--muted); font-size: 12px; }
      |.scroller { overflow-x: auto; overflow-y: hidden; position: relative; padding: 0 0 8px; }
      |.scroller svg { display: block; }
      |.scroller svg [data-name] { cursor: pointer; }
      |.scroller svg .selected { stroke: var(--ink); stroke-width: 2px; }
      |.scroller svg .focused { stroke: var(--ink); stroke-width: 2.5px; }
      |.tip { position: absolute; pointer-events: none; background: var(--ink); color: var(--ground); padding: 6px 9px; border-radius: 6px; font-size: 12px; max-width: 320px; line-height: 1.35; z-index: 2; }
      |.tip .q { font-family: var(--serif); font-size: 13px; }
      |.tip .m { font-family: var(--mono); font-size: 11px; opacity: 0.85; }
      |.inspector { display: grid; gap: 14px; padding: 12px 16px 16px; }
      |.inspector .where { color: var(--muted); font-size: 12px; }
      |.quote { font-family: var(--serif); font-size: 17px; line-height: 1.45; margin: 0; }
      |.kv { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; font-size: 13px; }
      |.kv .k { color: var(--muted); }
      |.segtext { font-size: 13px; color: var(--ink-2); border-left: 3px solid var(--model-soft); padding: 2px 0 2px 10px; margin: 0; }
      |.segtext.ru { border-left-color: var(--hair); }
      |.segtext .lab { display: block; font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); margin-bottom: 2px; }
      |.post { display: grid; gap: 6px; }
      |.post .bar { display: flex; height: 14px; border-radius: 4px; overflow: hidden; gap: 2px; background: var(--surface-2); }
      |.post .bar span { display: block; height: 100%; }
      |.post .legend { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--ink-2); }
      |.post .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; vertical-align: -1px; margin-right: 5px; }
      |table.alts { border-collapse: collapse; font-size: 12px; width: 100%; }
      |table.alts th { text-align: left; font-weight: 500; color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; padding: 2px 8px 4px 0; }
      |table.alts td { padding: 2px 8px 2px 0; border-top: 1px solid var(--hair-2); vertical-align: top; }
      |.note { font-size: 12px; color: var(--ink-2); margin: 0; }
      |.empty { color: var(--muted); font-size: 13px; }
      |.legend-row { display: flex; flex-wrap: wrap; gap: 8px 22px; font-size: 12px; color: var(--ink-2); padding: 8px 16px 12px; border-top: 1px solid var(--hair-2); }
      |.legend-row svg { width: 26px; height: 16px; vertical-align: middle; margin-right: 6px; }
      |footer.prov { font-size: 12px; color: var(--muted); max-width: 90ch; line-height: 1.5; }
      |footer.prov p { margin: 4px 0; }
      |.kbd { font-family: var(--mono); font-size: 11px; border: 1px solid var(--hair); border-radius: 4px; padding: 0 5px; background: var(--surface-2); }
      |.error { border: 2px solid var(--ink); padding: 0.75em; background: var(--surface); }
      |""".stripMargin
