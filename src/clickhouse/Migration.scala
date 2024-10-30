package supportbot
package clickhouse

import ClickHouseClient.QuerySettings

/** Represents a migration to be applied to the ClickHouse database.
  *
  * @param ddl
  *   The DDL statement to be executed, but it can be any valid ClickHouse query.
  * @param name
  *   The name of the migration. Used mainly for logging purposes.
  * @param querySettings
  *   Additional query settings to be used for this migration.
  */
final case class Migration(
  ddl: String,
  name: String,
  querySettings: Option[QuerySettings] = None,
):
  /** Converts this migration to a versioned migration.
    *
    * @param version
    *   The version of the migration, which should be monotonically increasing and unique. Usually index in the
    *   migration collection
    */
  def asVersioned(version: Int): VersionedMigration =
    VersionedMigration(version, name, ddl, querySettings)

private[clickhouse] final case class VersionedMigration(
  version: Int,
  name: String,
  ddl: String,
  querySettings: Option[QuerySettings] = None,
)
