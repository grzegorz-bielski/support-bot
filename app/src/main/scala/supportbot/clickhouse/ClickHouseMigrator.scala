package supportbot
package clickhouse

import cats.*
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

/** A simple ClickHouse migration tool.
  *
  * One migration is a single SQL query, applied sequentially, one after another, with monotonically increasing version
  * numbers. There is no concept of `up` or `down` migrations.
  *
  * Inspired by https://github.com/VVVi/clickhouse-migrations
  */
final class ClickHouseMigrator[F[_]: {Concurrent, Logger, ClickHouseClient as clickHouseClient}](config: Config):
  private val migrationsTable = "schema_migrations"

  /** Migrate the database to the latest version.
    *
    * @param migrations
    *   list of migrations to be applied sequentially
    */
  def migrate(migrations: Vector[Migration]): F[Unit] =
    given QuerySettings = QuerySettings.default.copy(wait_end_of_query = 1.some)

    val versionedMigrations = migrations.mapWithIndex(_.asVersioned(_))

    for
      _ <- optionallyDropDatabase
      _ <- createDatabase
      _ <- createMigrationTable
      _ <- applyMigrations(versionedMigrations)
    yield ()

  private def applyMigrations(migrations: Vector[VersionedMigration])(using querySettings: QuerySettings): F[Unit] =
    for
      appliedMigrationsByVersion <- getAppliedMigrations
      migrationsWithChecksums     = migrations.map(m => m -> calculateChecksum(m.ddl))
      _                          <- validateMigrationsAgainstApplied(migrationsWithChecksums, appliedMigrationsByVersion)
      _                          <- migrationsWithChecksums.traverse: (migration, checksum) =>
                                      val ddlQuerySettings = querySettings.combine(migration.querySettings.combineAll)

                                      // assuming the migration is idempotent (IF NOT EXISTS, etc),
                                      // it's safe to apply it multiple times in case of migration table update failure
                                      info"Applying migration ${migration.name}" *>
                                        clickHouseClient.executeQuery(migration.ddl)(using ddlQuerySettings) *>
                                        commitMigration(migration, checksum) *>
                                        info"Migration ${migration.name} applied"
    yield ()

  private def commitMigration(migration: VersionedMigration, checksum: String)(using QuerySettings): F[Unit] =
    clickHouseClient.executeQuery:
      i"""
      INSERT INTO $migrationsTable (version, checksum, name) 
      VALUES (${migration.version}, '$checksum', '${migration.name}')
      """

  private def getAppliedMigrations(using QuerySettings): F[Map[Int, AppliedMigrationRow]] =
    clickHouseClient
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

  private def validateMigrationsAgainstApplied(
    migrationsWithChecksums: Vector[(VersionedMigration, String)],
    appliedMigrationsByVersion: Map[Int, AppliedMigrationRow],
  ): F[Unit] =
    if appliedMigrationsByVersion.isEmpty then info"No migrations are applied yet"
    else
      migrationsWithChecksums
        .traverse: (migration, checksum) =>
          for
            appliedMigration <-
              MonadThrow[F].fromOption(
                appliedMigrationsByVersion.get(migration.version), 
                Error.MigrationMissing(migration.name, migration.version))
            _                <-
              MonadThrow[F].raiseUnless(appliedMigration.checksum == checksum):
                Error.MigrationChecksumMismatch(migration.name, migration.version)
          yield ()
        .void *>
        info"Migrations are valid"

  private def calculateChecksum(value: String): String =
    MessageDigest
      .getInstance("MD5")
      .digest(value.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  private def optionallyDropDatabase(using QuerySettings): F[Unit] =
    Applicative[F].whenA(config.fresh):
      clickHouseClient.executeQuery:
        i"DROP DATABASE IF EXISTS ${config.databaseName}"
      *> warn"Database ${config.databaseName} was just dropped! Here's your fresh start."

  private def createDatabase(using QuerySettings): F[Unit] =
    clickHouseClient.executeQuery:
      i"CREATE DATABASE IF NOT EXISTS ${config.databaseName}"
    *> info"Database ${config.databaseName} is created"

  private def createMigrationTable(using QuerySettings): F[Unit] =
    clickHouseClient.executeQuery:
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
    *> info"Migration table $migrationsTable is created"

object ClickHouseMigrator:
  def of[F[_]: {Async, ClickHouseClient, LoggerFactory}](config: Config): ClickHouseMigrator[F] =
    given Logger[F] = LoggerFactory[F].getLogger
    ClickHouseMigrator(config)

  // app specific
  def migrate()(using AppConfig, ClickHouseClient[IO]): IO[Unit] =
    val chConfig       = AppConfig.get.clickhouse
    val migratorConfig = ClickHouseMigrator.Config(
      databaseName = chConfig.database,
      fresh = chConfig.resetOnStart,
    )

    of[IO](migratorConfig).migrate(AllMigrations)

  /** Configuration for the ClickHouse migrator.
    *
    * @param databaseName
    *   name of the database to be migrated
    * @param fresh
    *   A fresh start. Indicates whether to drop the database before applying migrations. Do _not_ use in production!
    */
  final case class Config(
    databaseName: String,
    fresh: Boolean = false,
  )

  private final case class AppliedMigrationRow(
    version: Int,
    checksum: String,
    name: String,
  ) derives ConfiguredJsonValueCodec

  sealed trait Error extends NoStackTrace
  object Error:
    final case class MigrationMissing(migrationName: String, version: Int) extends Error:
      override def getMessage: String =
        s"Migration `$migrationName v$version` is missing, but it was applied. Please check the migration scripts."

    final case class MigrationChecksumMismatch(migrationName: String, version: Int) extends Error:
      override def getMessage: String =
        s"Migration `$migrationName v$version` checksum mismatch. The migration script should not be changed after applying it."
