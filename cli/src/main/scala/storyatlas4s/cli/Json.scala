package storyatlas4s.cli

/** Minimal deterministic JSON writer for the edition receipt (no dependency on a JSON library). */
private[cli] enum Json:
  case Str(value: String)
  case Num(value: Long)
  case Null
  case Arr(items: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

  def render: String =
    val out = new StringBuilder
    Json.write(this, 0, out)
    out.append('\n')
    out.result()

private[cli] object Json:
  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)
  def arr(items: Iterable[Json]): Json = Arr(items.toVector)
  def strings(items: Iterable[String]): Json = arr(items.map(Str.apply))

  private def indent(out: StringBuilder, depth: Int): Unit =
    var i = 0
    while i < depth do
      out.append("  ")
      i += 1

  private def write(json: Json, depth: Int, out: StringBuilder): Unit = json match
    case Str(value) => quote(value, out)
    case Num(value) => out.append(value)
    case Null       => out.append("null")
    case Arr(items) =>
      if items.isEmpty then out.append("[]")
      else
        out.append("[\n")
        items.zipWithIndex.foreach { (item, index) =>
          indent(out, depth + 1)
          write(item, depth + 1, out)
          if index < items.length - 1 then out.append(',')
          out.append('\n')
        }
        indent(out, depth)
        out.append(']')
    case Obj(fields) =>
      if fields.isEmpty then out.append("{}")
      else
        out.append("{\n")
        fields.zipWithIndex.foreach { case ((key, value), index) =>
          indent(out, depth + 1)
          quote(key, out)
          out.append(": ")
          write(value, depth + 1, out)
          if index < fields.length - 1 then out.append(',')
          out.append('\n')
        }
        indent(out, depth)
        out.append('}')

  private def quote(value: String, out: StringBuilder): Unit =
    out.append('"')
    value.foreach {
      case '"'           => out.append("\\\"")
      case '\\'          => out.append("\\\\")
      case '\n'          => out.append("\\n")
      case '\r'          => out.append("\\r")
      case '\t'          => out.append("\\t")
      case c if c < 0x20 => out.append(f"\\u${c.toInt}%04x")
      case c             => out.append(c)
    }
    out.append('"')
