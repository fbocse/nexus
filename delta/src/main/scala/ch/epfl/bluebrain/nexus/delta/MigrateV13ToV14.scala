package ch.epfl.bluebrain.nexus.delta

import java.nio.ByteBuffer
import java.util.{UUID, Set => JSet}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import ch.epfl.bluebrain.nexus.delta.MigrateV13ToV14._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections.cassandraDefaultConfigPath
import com.datastax.oss.driver.api.core.cql.{BoundStatement, PreparedStatement}
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.matching.Regex

// $COVERAGE-OFF$
class MigrateV13ToV14(implicit config: AppConfig, session: CassandraSession, as: ActorSystem) {

  private def truncateMessagesTable: String =
    s"TRUNCATE ${config.description.name}.messages"

  private def truncateSnapshotTable: String =
    s"TRUNCATE ${config.description.name}_snapshot.snapshots"

  private def truncateAllPersIdTable: String =
    s"TRUNCATE ${config.description.name}.all_persistence_ids"

  private def insertMessagesStmt: String =
    s"""INSERT INTO ${config.description.name}.messages (persistence_id, partition_nr, sequence_nr, timestamp, timebucket, writer_uuid, ser_id, ser_manifest, event_manifest, event, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

  private def insertAllPersIdsStmt: String =
    if (config.migration.parallelism == 1)
      s"""INSERT INTO ${config.description.name}.all_persistence_ids (persistence_id) VALUES (?) IF NOT EXISTS"""
    else
      s"""INSERT INTO ${config.description.name}.all_persistence_ids (persistence_id) VALUES (?)"""

  private def skipNonExisting(projects: Set[String]): Flow[Message, Message, NotUsed] =
    Flow[Message].filter { m =>
      m.persistence_id match {
        case resourceRegex(projectUuid, id) =>
          if (projects.contains(s"projects-$projectUuid")) {
            true
          } else {
            log.warn(s"project '$projectUuid' does not exist. Resource '$id' is going to be omitted")
            false
          }
        case _                              => true
      }
    }

  private def read(keyspace: String): Source[Message, NotUsed] = {
    CassandraSource(
      s"SELECT persistence_id, partition_nr, sequence_nr, timestamp, timebucket, writer_uuid, ser_id, ser_manifest, event_manifest, event, tags FROM $keyspace.messages;"
    ).map(r =>
      Message(
        r.getString("persistence_id"),
        r.getLong("partition_nr"),
        r.getLong("sequence_nr"),
        r.getUuid("timestamp"),
        r.getString("timebucket"),
        r.getString("writer_uuid"),
        r.getInt("ser_id"),
        r.getString("ser_manifest"),
        r.getString("event_manifest"),
        r.getByteBuffer("event"),
        r.getSet("tags", classOf[String])
      )
    )
  }

  // adapted from akka.stream.alpakka.cassandra.scaladsl.CassandraFlow.create
  // logs and skips errors in statement execution
  // uses a custom parallelism setting
  private def createCassandraFlow[T](
      cqlStatement: String,
      statementBinder: (T, PreparedStatement) => BoundStatement
  )(implicit session: CassandraSession): Flow[T, T, NotUsed] = {
    Flow
      .lazyFutureFlow { () =>
        val prepare = session.prepare(cqlStatement)
        prepare.map { preparedStatement =>
          Flow[T].mapAsync(config.migration.parallelism) { element =>
            session
              .executeWrite(statementBinder(element, preparedStatement))
              .recover {
                case NonFatal(th) => log.error(s"Failed to execute insert statement for message '$element'", th)
              }(ExecutionContext.parasitic)
              .map(_ => element)(ExecutionContext.parasitic)
          }
        }(as.dispatcher)
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  private def write: Sink[Message, Future[Long]] = {
    val flowInsertMessages =
      createCassandraFlow(
        insertMessagesStmt,
        (m: Message, stmt) =>
          stmt
            .bind()
            .setString("persistence_id", m.persistence_id)
            .setLong("partition_nr", m.partition_nr)
            .setLong("sequence_nr", m.sequence_nr)
            .setUuid("timestamp", m.timestamp)
            .setString("timebucket", m.timebucket)
            .setString("writer_uuid", m.writer_uuid)
            .setInt("ser_id", m.ser_id)
            .setString("ser_manifest", m.ser_manifest)
            .setString("event_manifest", m.event_manifest)
            .setByteBuffer("event", m.event)
            .setSet("tags", m.tags, classOf[String])
      ).log("error inserting into messages table")

    val flowInsertPersistenceIds =
      createCassandraFlow(
        insertAllPersIdsStmt,
        (m: Message, stmt) => stmt.bind().setString("persistence_id", m.persistence_id)
      ).log("error inserting persistence id")
    flowInsertMessages
      .via(flowInsertPersistenceIds)
      .toMat {
        Sink.fold(0L) {
          case (acc, _) =>
            if (acc % config.migration.logInterval.toLong == 0L) log.info(s"Processed '$acc' events.")
            acc + 1L
        }
      }(Keep.right)
  }

  def migrate(): Task[Unit] = {
    implicit val session: CassandraSession =
      CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath))
    val iamS                               = read(config.migration.iamKeyspace)
    val adminS                             = read(config.migration.adminKeyspace)
    val kgS                                = read(config.migration.kgKeyspace)
    for {
      _        <- Task.delay(log.info("Migrating messages from multiple keyspaces into a single keyspace."))
      _        <- Task.deferFuture(session.executeDDL(truncateMessagesTable))
      _        <- Task.deferFuture(session.executeDDL(truncateAllPersIdTable))
      _        <- Task.deferFuture(session.executeDDL(truncateSnapshotTable))
      _        <- Task.sleep(1.seconds)
      projects <-
        Task.deferFuture(read(config.migration.adminKeyspace).map(_.persistence_id).runFold(Set.empty[String])(_ + _))
      records  <- Task.deferFuture(iamS.concat(adminS).concat(kgS.via(skipNonExisting(projects))).runWith(write))
      _        <- Task.sleep(1.seconds)
      _        <- Task.delay(log.info(s"Migrated a total of '$records' events."))
    } yield ()
  }
}

object MigrateV13ToV14 {

  val resourceRegex: Regex =
    "^resources\\-([0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{12})\\-(.+)$".r
  val log: Logger          = Logger[MigrateV13ToV14.type]

  final def migrate(implicit config: AppConfig, as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit =
    (for {
      session <-
        Task.delay(CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath)))
      result  <- new MigrateV13ToV14()(config, session, as).migrate()
    } yield result).runSyncUnsafe()

  final case class Message(
      persistence_id: String,
      partition_nr: Long,
      sequence_nr: Long,
      timestamp: UUID,
      timebucket: String,
      writer_uuid: String,
      ser_id: Int,
      ser_manifest: String,
      event_manifest: String,
      event: ByteBuffer,
      tags: JSet[String]
  )
}
// $COVERAGE-ON$
