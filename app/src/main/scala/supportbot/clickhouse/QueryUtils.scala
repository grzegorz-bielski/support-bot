package supportbot
package clickhouse

extension (underlying: Map[String, String])
  inline def toClickHouseMap: String =
    s"{${underlying.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"

extension (underlying: String)
  inline def toClickHouseString: String =
    StringEscapeUtils.toClickHouseString(underlying)

private[clickhouse] object StringEscapeUtils:
  // this _does not_ handle the hex escape
  def toClickHouseString(str: String): String =
    val sb = StringBuilder()

    sb.append('\'')
    str.foreach: c =>
      staticEscapeMappings.get(c) match
        case Some(escaped) => sb.append(escaped)
        case None          => sb.append(c)
    sb.append('\'')

    sb.result()

  // see CH escaping rules:
  // https://clickhouse.com/docs/en/sql-reference/syntax#string
  lazy val staticEscapeMappings = Map(
    '\\'     -> "\\\\",
    '\''     -> "\\'",
    '\n'     -> "\\n",
    '\r'     -> "\\r",
    '\t'     -> "\\t",
    '\b'     -> "\\b",
    '\f'     -> "\\f",
    '\u0000' -> "\\0",
    '\u0007' -> "\\a",
    '\u000B' -> "\\v",
  )
