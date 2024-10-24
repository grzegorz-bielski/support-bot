package supportbot
package clickhouse

extension (underlying: Map[String, String])
  def toClickHouseMap: String =
    s"{${underlying.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"
