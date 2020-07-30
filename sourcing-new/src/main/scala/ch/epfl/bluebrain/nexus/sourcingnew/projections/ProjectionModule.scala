package ch.epfl.bluebrain.nexus.sourcingnew.projections


import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra.{CassandraProjection, CassandraSchemaManager, ProjectionConfig, CassandraConfig}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc.{JdbcConfig, JdbcProjection, JdbcSchemaManager}
import distage.{Axis, ModuleDef, Tag, TagK}
import doobie.util.transactor.Transactor
import io.circe.{Decoder, Encoder}

object Persistence extends Axis {
  case object Cassandra extends AxisValueDef
  case object Postgres extends AxisValueDef
}

final class ProjectionModule[F[_]: ContextShift: Async: TagK, A: Encoder: Decoder: Tag] extends ModuleDef {

  // Cassandra
  make[SchemaManager[F]].tagged(Persistence.Cassandra).from {
    (cassandraSession: CassandraSession, schemaConfig: CassandraConfig, actorSystem: ActorSystem[Nothing]) =>
      new CassandraSchemaManager[F](cassandraSession, schemaConfig, actorSystem)

  }
  make[Projection[F, A]].tagged(Persistence.Cassandra).from {
    (cassandraSession: CassandraSession, projectionConfig: ProjectionConfig, actorSystem: ActorSystem[Nothing]) =>
      new CassandraProjection[F, A](cassandraSession, projectionConfig, actorSystem)

  }

  // Postgresql
  make[Transactor[F]].tagged(Persistence.Postgres).from {
    (jdbcConfig: JdbcConfig) =>
      Transactor.fromDriverManager[F](
        jdbcConfig.driver,
        jdbcConfig.url,
        jdbcConfig.username,
        jdbcConfig.password,
      )
  }
  make[SchemaManager[F]].tagged(Persistence.Postgres).from {
    (jdbcConfig: JdbcConfig) =>
      new JdbcSchemaManager[F](jdbcConfig)
  }
  make[Projection[F,A]].tagged(Persistence.Postgres).from {
    (xa: Transactor[F]) =>
      new JdbcProjection[F, A](xa)
  }

}

object ProjectionModule {
  final def apply[F[_]: ContextShift: Async: TagK, A: Encoder: Decoder: Tag]: ProjectionModule[F, A] =
    new ProjectionModule[F, A]
}