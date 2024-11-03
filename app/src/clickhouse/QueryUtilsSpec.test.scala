package supportbot
package clickhouse

class QueryUtilsSpec extends munit.FunSuite:
  test("`toClickHouseMap` should convert a map to a ClickHouse map"):
    assertEquals(
      Map("key1" -> "value1", "key2" -> "value2").toClickHouseMap,
      "{'key1':'value1', 'key2':'value2'}",
    )

  test("`toClickHouseString` should escape a string with all special characters"):
    StringEscapeUtils.staticEscapeMappings.toVector.foreach: (char, expected) =>
      assertEquals(s"$char".toClickHouseString, s"'$expected'")
