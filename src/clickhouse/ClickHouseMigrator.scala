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
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.*

import ClickHouseMigrator.*

// inspired by https://github.com/VVVi/clickhouse-migrations
final class ClickHouseMigrator(config: Config)(using client: ClickHouseClient[IO], logger: Logger[IO]):
  private val migrationsTable = "schema_migrations"

  def migrate(migrations: Vector[Migration]): IO[Unit] =
    given QuerySettings = QuerySettings.default.copy(wait_end_of_query = 1.some)

    for
      _ <- createDatabase(config.database)
      _ <- createMigrationTable
      _ <- applyMigrations(migrations)
    yield ()

  private def applyMigrations(migrations: Vector[Migration])(using querySettings: QuerySettings): IO[Unit] =
    for
      appliedMigrationsByVersion <- getAppliedMigrations
      migrationsWithChecksums     = migrations.map(m => m -> calculateChecksum(m.ddl))
      _                          <- validateMigrations(migrationsWithChecksums, appliedMigrationsByVersion)
      _                          <- migrationsWithChecksums.traverse: (migration, checksum) =>
                                      val ddlQuerySettings = querySettings.combine(migration.querySettings.combineAll)

                                      // assuming the migration is idempotent (IF NOT EXISTS, etc),
                                      // it's safe to apply it multiple times in case of migration table update failure
                                      info"Applying migration ${migration.name}" *>
                                        client.executeQuery(migration.ddl)(using ddlQuerySettings) *>
                                        commitMigration(migration, checksum) *>
                                        info"Migration ${migration.name} applied"
    yield ()

  private def commitMigration(migration: Migration, checksum: String)(using QuerySettings): IO[Unit] =
    client.executeQuery:
      i"""
      INSERT INTO $migrationsTable (version, checksum, name) 
      VALUES (${migration.version}, '$checksum', '${migration.name}')
      """

  private def getAppliedMigrations(using QuerySettings): IO[Map[Int, AppliedMigrationRow]] =
    client
      .streamQueryJson[AppliedMigrationRow]:
        i"""
        SELECT version, checksum, name 
        FROM $migrationsTable 
        ORDER BY version
        FORMAT JSONEachRow
        """
      .compile
      .toVector
      .map(_.map(m => m.version -> m).toMap)

  private def validateMigrations(
    migrationsWithChecksums: Vector[(Migration, String)],
    appliedMigrationsByVersion: Map[Int, AppliedMigrationRow],
  ): IO[Unit] =
    migrationsWithChecksums
      .traverse: (migration, checksum) =>
        for
          appliedMigration <-
            IO.fromOption(appliedMigrationsByVersion.get(migration.version)):
              Error.MigrationMissing(migration.name)
          _                <-
            IO.raiseUnless(appliedMigration.checksum == checksum):
              Error.MigrationChecksumMismatch(migration.name)
        yield ()
      .void *>
      info"Migrations are valid"

  private def calculateChecksum(value: String): String =
    MessageDigest
      .getInstance("MD5")
      .digest(value.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  private def createDatabase(databaseName: String)(using QuerySettings): IO[Unit] =
    client.executeQuery:
      i"CREATE DATABASE IF NOT EXISTS $databaseName"
    *> info"Created database $databaseName"

  private def createMigrationTable(using QuerySettings): IO[Unit] =
    client.executeQuery:
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
    *> info"Created migration table $migrationsTable"

object ClickHouseMigrator:
  def of(config: Config)(using ClickHouseClient[IO]): IO[ClickHouseMigrator] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseMigrator(config)

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
