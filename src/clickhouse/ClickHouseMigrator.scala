package supportbot

import cats.effect.*
import cats.syntax.all.*
import unindent.*
import supportbot.clickhouse.ClickHouseClient
import supportbot.clickhouse.ClickHouseClient.QuerySettings
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.util.control.NoStackTrace
import java.security.MessageDigest

import ClickHouseMigrator.*

// inspired by https://github.com/VVVi/clickhouse-migrations
final class ClickHouseMigrator(config: Config)(using client: ClickHouseClient[IO]):
  private val migrationsTable = "schema_migrations"

  def migrate(migrations: Vector[Migration]): IO[Unit] =
    given QuerySettings = QuerySettings.default.copy(wait_end_of_query = 1.some)

    for
      _ <- createDatabase(config.database)
      _ <- createMigrationTable
      _ <- applyMigrations(migrations)
    yield ()

  private def applyMigrations(migrations: Vector[Migration])(using QuerySettings): IO[Unit] =
    for

      // get applied migrations
      appliedMigrations         <- client
                                     .streamQueryJson[AppliedMigrationRow]:
                                       i"""
                                        SELECT version, checksum, name 
                                        FROM $migrationsTable 
                                        ORDER BY version
                                        """
                                     .compile
                                     .toVector
      appliedMigrationsByVersion = appliedMigrations.map(m => m.version -> m).toMap

      // calculate checksums for passed migrations
      migrationsWithChecksums = migrations.map(migration => migration -> calculateChecksum(migration.ddl))

      // validate passed migrations
      _ <- migrationsWithChecksums.traverse: (migration, checksum) =>
             for
               appliedMigration <-
                 IO.fromOption(appliedMigrationsByVersion.get(migration.version)):
                   Error.MigrationMissing(migration.name)
               _                <-
                 IO.raiseUnless(appliedMigration.checksum == checksum):
                   Error.MigrationChecksumMismatch(migration.name)
             yield ()

      // actually apply migrations
      _ <- migrationsWithChecksums.traverse: (migration, checksum) =>
             // TODO: handle extra query settings
             // TODO: log what was applied

             // assuming the migration is idempotent,
             // so it's safe to apply it multiple times in case of migration table update failure
             client.executeQuery(migration.ddl) *>
               client.executeQuery:
                 i"""
                                      INSERT INTO $migrationsTable (version, checksum, name) 
                                      VALUES (${migration.version}, '$checksum', '${migration.name}')
                                      """
    yield ()

  private def calculateChecksum(value: String): String =
    MessageDigest
      .getInstance("MD5")
      .digest(value.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  private def createDatabase(databaseName: String)(using QuerySettings): IO[Unit] = client.executeQuery:
    i"CREATE DATABASE IF NOT EXISTS $databaseName"

  private def createMigrationTable(using QuerySettings): IO[Unit] = client.executeQuery:
    i"""
    CREATE TABLE IF NOT EXISTS $migrationsTable (
        uid UUID DEFAULT generateUUIDv4(),
        version UInt32,
        checksum String,
        name String,
        applied_at DateTime DEFAULT now()
    )
    ENGINE = MergeTree()
    ORDER BY tuple(applied_at)
    """

object ClickHouseMigrator:
  final case class Config(
    database: String,
  )

  final case class Migration(
    version: Int,
    name: String,
    ddl: String,
    querySettings: Option[QuerySettings] = None,
  )

  private final case class AppliedMigrationRow(
    version: Int,
    checksum: String,
    name: String,
  ) derives ConfiguredJsonValueCodec

  sealed trait Error extends NoStackTrace
  object Error:
    final case class MigrationMissing(migrationName: String) extends Error:
      override def getMessage: String =
        s"Migration $migrationName is missing, but it was applied. Please check the migration scripts."

    final case class MigrationChecksumMismatch(migrationName: String) extends Error:
      override def getMessage: String =
        s"Migration $migrationName checksum mismatch. The migration script should not be changed after applying it."
