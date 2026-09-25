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

  /** Escape a JSON document for an inline `<script type="application/json">` body: every `<`
    * becomes the six-character JSON unicode escape for the less-than sign, which covers `</script`,
    * `<!--` and `<script` at once and decodes back to the same text. `<` never occurs outside a
    * JSON string, so the rewrite is total and reversible.
    */
  def inlineJson(json: String): String =
    json.replace("<", "\\u003c")

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

  /** Shared by the standalone edition and the joined workspace, scoped to the inspector so it
    * cannot restyle the surrounding source reader or matrix.
    */
  val inspectorCss: String =
    """.inspector-content { --model: #1B7FA3; --model-soft: rgba(27,127,163,0.16); --raw: #9AA3AB; --external: #8A939D; --hair: #D5DAD8; --surface-2: #EDEFEC; display: grid; gap: 16px; min-width: 0; color: #1B2126; }
      |.inspector-content h2 { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; color: #4B565F; margin: 0; }
      |.inspector-content .num { font-family: "SFMono-Regular", Menlo, Consolas, monospace; font-variant-numeric: tabular-nums; }
      |.inspector-content .where { color: #4B565F; font-size: 12px; }
      |.inspector-content .quote { font-family: Georgia, "Times New Roman", serif; font-size: 17px; line-height: 1.45; margin: 0; }
      |.inspector-content .kv { display: grid; grid-template-columns: max-content minmax(0,1fr); gap: 4px 12px; font-size: 13px; }
      |.inspector-content .kv .k { color: #4B565F; }
      |.inspector-content .segtext { font-size: 13px; color: #4B565F; padding: 2px 0; margin: 0; }
      |.inspector-content .segtext .lab { display: block; font-size: 12px; margin-bottom: 2px; }
      |.inspector-content .post, .inspector-content .strip { display: grid; gap: 6px; }
      |.inspector-content .drawn-choice { padding: 12px; border: 1px solid var(--hair); border-radius: 6px; display: grid; gap: 6px; }
      |.inspector-content .choice-target { margin: 0; overflow-wrap: anywhere; }
      |.inspector-content .group-extents, .inspector-content .mass-extents { display: flex; justify-content: space-between; font-size: 12px; color: #4B565F; }
      |.inspector-content .strip svg { width: 100%; height: 44px; }
      |.inspector-content .post .bar { display: flex; height: 14px; border-radius: 4px; overflow: hidden; background: var(--surface-2); }
      |.inspector-content .post .bar span { display: block; height: 100%; flex-shrink: 0; }
      |.inspector-content .post .legend { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: #4B565F; margin: 0; }
      |.inspector-content .post .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; vertical-align: -1px; margin-right: 5px; }
      |.inspector-content .posterior-candidates > summary { cursor: pointer; font-size: 13px; font-weight: 600; padding: 8px 0; }
      |.inspector-content .posterior-candidates .note { margin: 4px 0 8px; }
      |.inspector-content table.alts { border-collapse: collapse; font-size: 12px; width: 100%; table-layout: fixed; overflow-wrap: anywhere; }
      |.inspector-content table.alts th { text-align: left; font-weight: 500; color: #4B565F; font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; padding: 2px 8px 4px 0; }
      |.inspector-content table.alts th:first-child { width: 3.5em; }
      |.inspector-content table.alts th:nth-child(2) { width: 5.5em; }
      |.inspector-content table.alts td { padding: 2px 8px 2px 0; border-top: 1px solid var(--hair); vertical-align: top; }
      |.inspector-content .note { font-size: 12px; color: #4B565F; margin: 0; }
      |""".stripMargin

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
      |.panes { display: grid; min-width: 0; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
      |@media (max-width: 1100px) { .panes { grid-template-columns: minmax(0, 1fr); } }
      |.panel { min-width: 0; background: var(--surface); border: 1px solid var(--hair); border-radius: 10px; }
      |.panel > .head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; padding: 12px 16px 8px; }
      |.panel > .head .hint { color: var(--muted); font-size: 12px; }
      |.scroller { overflow-x: auto; overflow-y: hidden; position: relative; padding: 0 0 8px; }
      |.scroller svg { display: block; }
      |.scroller svg [data-name] { cursor: pointer; }
      |.plate svg { display: block; max-width: none; }
      |.controls input { accent-color: var(--model); }
      |.legend-row span { display: inline-flex; align-items: center; gap: 7px; }
      |.legend-row .glyph { flex: none; }
      |.strip { display: grid; gap: 4px; }
      |.scroller svg .selected { stroke: var(--ink); stroke-width: 2px; }
      |.scroller svg .focused { stroke: var(--ink); stroke-width: 2.5px; }
      |.scroller svg .selected .voyage-container > * { stroke: var(--ink); stroke-width: 2px; }
      |.scroller svg .focused .voyage-container > * { stroke: var(--ink); stroke-width: 2.5px; stroke-dasharray: 3 2; }
      |.tip { position: absolute; pointer-events: none; background: var(--ink); color: var(--ground); padding: 6px 9px; border-radius: 6px; font-size: 12px; max-width: 320px; line-height: 1.35; z-index: 2; }
      |.tip .q { font-family: var(--serif); font-size: 13px; }
      |.tip .m { font-family: var(--mono); font-size: 11px; opacity: 0.85; }
      |.inspector { display: grid; gap: 14px; padding: 12px 16px 16px; }
      |.note { font-size: 12px; color: var(--ink-2); margin: 0; }
      |.empty { color: var(--muted); font-size: 13px; }
      |.legend-row { display: flex; flex-wrap: wrap; gap: 8px 22px; font-size: 12px; color: var(--ink-2); padding: 8px 16px 12px; border-top: 1px solid var(--hair-2); }
      |.legend-row svg { width: 26px; height: 16px; vertical-align: middle; margin-right: 6px; }
      |footer.prov { font-size: 12px; color: var(--muted); max-width: 90ch; line-height: 1.5; }
      |footer.prov p { margin: 4px 0; }
      |.kbd { font-family: var(--mono); font-size: 11px; border: 1px solid var(--hair); border-radius: 4px; padding: 0 5px; background: var(--surface-2); }
      |.error { border: 2px solid var(--ink); padding: 0.75em; background: var(--surface); }
      |.voyage-navigation { padding: 12px 16px 0; }
      |.range-toolbar, .range-form, .range-actions { display: flex; flex-wrap: wrap; align-items: end; gap: 8px; }
      |.range-toolbar { justify-content: space-between; }
      |.range-form label { display: grid; gap: 3px; font-size: 12px; }
      |.range-form input { width: 7.5em; padding: 7px 8px; border: 1px solid var(--hair); border-radius: 4px; font-family: var(--mono); font-variant-numeric: tabular-nums; }
      |.voyage-navigation button { min-height: 36px; padding: 7px 10px; border: 1px solid var(--hair); border-radius: 4px; background: var(--surface); cursor: pointer; }
      |.voyage-navigation button:hover:not(:disabled) { background: var(--surface-2); }
      |.voyage-navigation button:disabled { opacity: 0.5; cursor: default; }
      |.overview-caption, .overview-extents { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; color: var(--ink-2); }
      |.overview-caption { margin-top: 16px; margin-bottom: 5px; flex-wrap: wrap; }
      |.overview-extents { font-family: var(--mono); margin-top: 3px; }
      |.recall-overview { height: 46px; position: relative; border: 1px solid var(--hair); background: var(--surface-2); cursor: crosshair; touch-action: none; user-select: none; }
      |.recall-overview svg { display: block; pointer-events: none; }
      |.onset-tick { stroke: var(--ink-2); stroke-width: 1; }
      |.brush-window { position: absolute; top: 0; bottom: 0; min-width: 1px; border: 2px solid var(--ink); background: rgba(27,127,163,0.12); pointer-events: none; }
      |.range-status { margin-top: 8px; font-weight: 600; font-variant-numeric: tabular-nums; }
      |.range-error { margin: 8px 0; color: #942f29; }
      |.recall-picker { display: flex; align-items: end; gap: 10px; margin-top: 12px; }
      |.recall-picker label { display: grid; gap: 4px; min-width: 0; flex: 1; font-size: 12px; }
      |.recall-picker select { width: 100%; min-width: 0; padding: 7px; background: var(--surface); border: 1px solid var(--hair); border-radius: 4px; }
      |.selection-location { min-height: 1.5em; margin: 6px 0 10px; font-size: 12px; color: var(--ink-2); }
      |.voyage-key { border-top: 1px solid var(--hair); }
      |.voyage-key > summary { cursor: pointer; padding: 12px 16px; font-weight: 600; }
      |.voyage-key .legend-row { border-top: 0; }
      |@media (max-width: 640px) {
      |  .page { padding: 12px 12px 24px; gap: 12px; }
      |  header.top { grid-template-columns: minmax(0, 1fr); gap: 10px; }
      |  .masthead p { font-size: 14px; }
      |  .controls { justify-content: flex-start; gap: 8px; }
      |  .controls label { min-height: 36px; }
      |  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
      |  .stat { padding: 8px; }
      |  .panel > .head { display: grid; gap: 4px; padding: 12px 10px 4px; }
      |  .voyage-navigation { padding: 10px 10px 0; }
      |  .range-toolbar { display: grid; gap: 10px; }
      |  .range-form { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1fr) auto; }
      |  .range-form input { width: 100%; min-width: 0; font-size: 16px; }
      |  .voyage-navigation button, .recall-picker select { min-height: 44px; }
      |  .range-actions { display: grid; grid-template-columns: repeat(3, 1fr); }
      |  .recall-picker { display: grid; grid-template-columns: minmax(0, 1fr); gap: 6px; }
      |  .recall-picker select { font-size: 16px; }
      |  .recall-picker button { justify-self: start; }
      |  .inspector { padding: 12px 10px; }
      |  .legend-row { display: grid; font-size: 13px; }
      |}
      |""".stripMargin + inspectorCss
