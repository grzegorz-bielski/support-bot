package supportbot
package clickhouse

extension (underlying: Map[String, String])
  def toClickHouseMap: String =
    s"{${underlying.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"


extension (underlying: String)
  def toClickHouseString: String =
    // see CH escaping rules: 
    // https://clickhouse.com/docs/en/sql-reference/syntax#string

    // TODO: test it
    val escaped = underlying
      .replace("\\", "\\\\") // backslash
      .replace("'", "\\'") // single quote
      .replace("\n", "\\n") // newline
      .replace("\r", "\\r") // carriage return
      .replace("\t", "\\t") // tab
      .replace("\b", "\\b") // backspace
      .replace("\f", "\\f") // form feed
      .replace("\u0000", "\\0") // null byte
      .replace(raw"\a", "\\a") // bell
      .replace(raw"\v", "\\v") // vertical tab
      .replace(raw"\xHH", "\\xHH") // hex escape

    s"'$escaped'"
