package supportbot
package clickhouse

import cats.effect.*
import cats.syntax.all.*
import unindent.*
import munit.*
import com.dimafeng.testcontainers.munit.fixtures.TestContainersFixtures
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.*
import org.testcontainers.containers.wait.strategy.Wait
import com.dimafeng.testcontainers.*
import com.dimafeng.testcontainers.implicits.DockerImageNameConverters.given

import ClickHouseMigratorSpec.*
import ClickHouseMigrator.*

class ClickHouseMigratorSpec extends CatsEffectSuite, TestContainersFixtures:
  val clickHouseFixture = ContainerFunFixture(clickHouseContainer)

  // TODO: 
  // - test the `unclean` database scenario
  // - test validations
  
  clickHouseFixture.test("should apply migrations in clean database correctly"): container =>
    clickHouseClientResource(container).use: client =>
      given SttpClickHouseClient = client
      given Logger[IO]           = NoOpLogger[IO]

      val migrator = ClickHouseMigrator(
        config = ClickHouseMigrator.Config(
          databaseName = clickHouseDb,
          fresh = true,
        ),
      )

      for
        _      <- migrator.migrate(
                    Vector(
                      Migration(
                        name = "test_migrations",
                        ddl = 
                          i"""
                          CREATE TABLE test_table (
                            id Int32
                          )
                          ENGINE = MergeTree()
                          ORDER BY id
                          """,
                      ),
                      Migration(
                        name = "test_migrations",
                        ddl = i"ALTER TABLE test_table ADD COLUMN name String",
                      ),
                      Migration(
                        name = "test_migrations",
                        ddl = i"INSERT INTO test_table (*) VALUES (1, 'test')",
                      )
                    ),
                  )
        result <- client
                    .streamQueryTextLines:
                      i"SELECT name FROM test_table WHERE id = 1"
                    .compile
                    .string
                    .map(_.trim)
      yield assertEquals(result, "test")

object ClickHouseMigratorSpec:
  lazy val clickHouseUser = "default"
  lazy val clickHousePassword = "default"
  lazy val clickHouseDb   = "default"

  lazy val exposedPort         = 8123
  lazy val clickHouseContainer = GenericContainer(
    dockerImage = "clickhouse/clickhouse-server:24.11.1-alpine",
    exposedPorts = Seq(exposedPort),
    waitStrategy = Wait.forHttp("/"),
    env = Map(
      "CLICKHOUSE_USER"     -> clickHouseUser,
      "CLICKHOUSE_PASSWORD" -> clickHousePassword,
      "CLICKHOUSE_DATABASE" -> clickHouseDb,
    )
  )

  def clickHouseClientResource(container: GenericContainer): Resource[IO, SttpClickHouseClient] =
    val config = ClickHouseClient.Config(
      url = s"http://${container.host}:${container.mappedPort(exposedPort)}",
      username = clickHouseUser,
      password = clickHousePassword,
    )

    SttpBackend.resource.map(SttpClickHouseClient(config)(using _))
