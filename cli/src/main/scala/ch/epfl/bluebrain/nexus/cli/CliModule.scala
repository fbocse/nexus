package ch.epfl.bluebrain.nexus.cli

import cats.effect.concurrent.Ref
import cats.effect.{ConcurrentEffect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.clients.{EventStreamClient, ProjectClient, SparqlClient}
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

final class CliModule[F[_]: ConcurrentEffect: Timer: TagK] extends ModuleDef {

  make[Console[F]].tagged(Repo.Prod).from(Console[F])

  make[Client[F]].tagged(Repo.Prod).fromResource {
    BlazeClientBuilder[F](ExecutionContext.global).resource
  }

  make[ProjectClient[F]].tagged(Repo.Prod).fromEffect { (cfg: AppConfig, client: Client[F]) =>
    Ref.of[F, Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]](Map.empty).map { cache =>
      ProjectClient(client, cfg.env, cache)
    }
  }

  make[SparqlClient[F]].tagged(Repo.Prod).from { (cfg: AppConfig, client: Client[F]) => SparqlClient(client, cfg.env) }

  make[EventStreamClient[F]].tagged(Repo.Prod).from { (cfg: AppConfig, client: Client[F], pc: ProjectClient[F]) =>
    EventStreamClient(client, pc, cfg.env)
  }
}

object CliModule {

  final def apply[F[_]: ConcurrentEffect: Timer: TagK]: CliModule[F] =
    new CliModule[F]

}