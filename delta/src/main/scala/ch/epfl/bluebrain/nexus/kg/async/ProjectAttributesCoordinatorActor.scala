package ch.epfl.bluebrain.nexus.kg.async

import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.async.ProjectAttributesCoordinatorActor.Msg._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.FileDigestAlreadyExists
import ch.epfl.bluebrain.nexus.kg.resources.{Event, Files, Rejection, Resource}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.FetchAttributes
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections._
import ch.epfl.bluebrain.nexus.kg.indexing._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import retry.CatsEffect._
import retry._
import retry.syntax.all._

/**
  * Coordinator backed by akka actor which runs the attributes stream inside the provided project
  */
//noinspection ActorMutableStateInspection
abstract private class ProjectAttributesCoordinatorActor(implicit val config: AppConfig)
    extends Actor
    with ActorLogging {

  private var child: Option[StreamSupervisor[Task, ProjectionProgress]] = None

  def receive: Receive = {
    case Start(_, project: ProjectResource) =>
      log.debug("Started attributes coordinator for project '{}'", project.value.show)
      context.become(initialized(project))
      child = Some(startCoordinator(project, restartOffset = false))
    case other                              =>
      log.debug("Received non Start message '{}', ignore", other)
  }

  def initialized(project: ProjectResource): Receive = {
    case Stop(_)  =>
      log.info("Attributes process for project '{}' received a stop message.", project.value.show)
      child.foreach(_.stop())
      child = None
      context.become(receive)

    case _: Start => //ignore, it has already been started

    case other => log.error("Unexpected message received '{}'", other)
  }

  def startCoordinator(project: ProjectResource, restartOffset: Boolean): StreamSupervisor[Task, ProjectionProgress]
}

object ProjectAttributesCoordinatorActor {

  val metricsPrefix = "digest_computation"

  def progressName(uuid: UUID): String = s"attributes-computation-$uuid"

  sealed private[async] trait Msg {

    /**
      * @return the project unique identifier
      */
    def uuid: UUID
  }
  object Msg {

    final case class Start(uuid: UUID, project: ProjectResource) extends Msg
    final case class Stop(uuid: UUID)                            extends Msg
  }

  private[async] def shardExtractor(shards: Int): ExtractShardId = {
    case msg: Msg                    => math.abs(msg.uuid.hashCode) % shards toString
    case ShardRegion.StartEntity(id) => (id.hashCode                % shards) toString
  }

  private[async] val entityExtractor: ExtractEntityId = {
    case msg: Msg => (msg.uuid.toString, msg)
  }

  /**
    * Starts the ProjectDigestCoordinator shard that coordinates the running digest' streams inside the provided project
    *
    * @param files            the files operations
    * @param shardingSettings the sharding settings
    * @param shards           the number of shards to use
    */
  final def start(
      files: Files[Task],
      shardingSettings: Option[ClusterShardingSettings],
      shards: Int
  )(implicit
      config: AppConfig,
      fetchDigest: FetchAttributes[Task],
      as: ActorSystem,
      projections: Projections[Task, String]
  ): ActorRef = {

    val props = Props(new ProjectAttributesCoordinatorActor {
      override def startCoordinator(
          project: ProjectResource,
          restartOffset: Boolean
      ): StreamSupervisor[Task, ProjectionProgress] = {

        implicit val indexing: IndexingConfig                                             = config.storage.indexing
        implicit val policy: RetryPolicy[Task]                                            = config.storage.fileAttrRetry.retryPolicy[Task]
        implicit val tm: Timeout                                                          = Timeout(config.storage.askTimeout)
        implicit val logErrors: (Either[Rejection, Resource], RetryDetails) => Task[Unit] =
          (err, d) =>
            Task.pure(
              log.warning(
                "Retrying on resource creation with retry details '{}' from project '{}' and error: '{}'",
                err,
                project.value.show,
                d
              )
            )
        val projectionId: String                                                          = progressName(project.uuid)

        val initFetchProgressF: Task[ProjectionProgress] =
          if (restartOffset) projections.recordProgress(projectionId, NoProgress) >> Task.delay(NoProgress)
          else projections.progress(projectionId)

        val sourceF: Task[Source[ProjectionProgress, _]] = initFetchProgressF.map {
          initial =>
            val flow = ProgressFlowElem[Task, Any]
              .collectCast[Event]
              .collect {
                case ev: Event.FileCreated => ev: Event
                case ev: Event.FileUpdated => ev: Event
              }
              .mapAsync { event =>
                implicit val subject: Subject = event.subject
                files
                  .updateFileAttrEmpty(event.id)
                  .value
                  .retryingM {
                    case Right(_) | Left(_: FileDigestAlreadyExists) => true
                    case Left(_)                                     => false
                  }
                  .map(_.toOption)

              }
              .collectSome[Resource]
              .toPersistedProgress(projectionId, initial)

            val source: Source[PairMsg[Any], NotUsed] = PersistenceQuery(as)
              .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
              .eventsByTag(s"project=${project.uuid}", initial.minProgress.offset)
              .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

            source
              .via(flow)
              .via(kamonProjectMetricsFlow(metricsPrefix, project.value.projectLabel))
        }
        StreamSupervisor.start(sourceF, projectionId, context.actorOf)
      }
    })
    start(props, shardingSettings, shards)
  }

  final private[async] def start(props: Props, shardingSettings: Option[ClusterShardingSettings], shards: Int)(implicit
      as: ActorSystem
  ): ActorRef = {
    val settings = shardingSettings.getOrElse(ClusterShardingSettings(as)).withRememberEntities(true)
    ClusterSharding(as).start(
      "project-attributes-coordinator",
      props,
      settings,
      entityExtractor,
      shardExtractor(shards)
    )
  }
}
