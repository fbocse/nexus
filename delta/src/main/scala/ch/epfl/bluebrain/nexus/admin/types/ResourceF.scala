package ch.epfl.bluebrain.nexus.admin.types

import java.time.Instant
import java.util.UUID

import akka.cluster.ddata.LWWRegister.Clock
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.Contexts._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}

/**
  * The metadata information for any resource in the service
  *
  * @param id         the id of the resource
  * @param uuid       the permanent internal identifier of the resource
  * @param rev        the revision
  * @param deprecated the deprecation status
  * @param types      the types of the resource
  * @param createdAt  the creation date of the resource
  * @param createdBy  the identity that created the resource
  * @param updatedAt  the last update date of the resource
  * @param updatedBy  the identity that performed the last update to the resource
  * @param value      the resource value
  */
final case class ResourceF[A](
    id: AbsoluteIri,
    uuid: UUID,
    rev: Long,
    deprecated: Boolean,
    types: Set[AbsoluteIri],
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject,
    value: A
) {

  /**
    * Creates a new [[ResourceF]] changing the value using the provided ''v'' argument.
    *
    * @param v the new value.
    * @tparam B the generic type of the resulting value field
    */
  def withValue[B](v: B): ResourceF[B] = copy(value = v)

  /**
    * Converts the current [[ResourceF]] to a [[ResourceF]] where the value is of type Unit.
    */
  def discard: ResourceF[Unit] = copy(value = ())
}

object ResourceF {

  /**
    * Constructs a [[ResourceF]] where the value is of type Unit
    *
    * @param id         the identifier of the resource
    * @param uuid       the permanent internal identifier of the resource
    * @param rev        the revision of the resource
    * @param deprecated the deprecation status
    * @param types      the types of the resource
    * @param createdAt  the instant when the resource was created
    * @param createdBy  the identity that created the resource
    * @param updatedAt  the instant when the resource was updated
    * @param updatedBy  the identity that updated the resource
    */
  def unit(
      id: AbsoluteIri,
      uuid: UUID,
      rev: Long,
      deprecated: Boolean,
      types: Set[AbsoluteIri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ): ResourceF[Unit] =
    ResourceF(id, uuid, rev, deprecated, types, createdAt, createdBy, updatedAt, updatedBy, ())

  implicit def resourceMetaEncoder(implicit http: HttpConfig): Encoder[ResourceMetadata] =
    Encoder.encodeJson.contramap {
      case ResourceF(id, uuid, rev, deprecated, types, createdAt, createdBy, updatedAt, updatedBy, _: Unit) =>
        val jsonTypes = types.toList match {
          case Nil      => Json.Null
          case t :: Nil => Json.fromString(t.lastSegment.getOrElse(t.asString))
          case _        => Json.arr(types.map(t => Json.fromString(t.lastSegment.getOrElse(t.asString))).toSeq: _*)
        }
        Json.obj(
          "@context" -> Json.arr(adminCtxUri.asJson, resourceCtxUri.asJson),
          "@id"                 -> id.asJson,
          "@type"               -> jsonTypes,
          nxv.uuid.prefix       -> Json.fromString(uuid.toString),
          nxv.rev.prefix        -> Json.fromLong(rev),
          nxv.deprecated.prefix -> Json.fromBoolean(deprecated),
          nxv.createdBy.prefix  -> createdBy.id.asJson,
          nxv.updatedBy.prefix  -> updatedBy.id.asJson,
          nxv.createdAt.prefix  -> Json.fromString(createdAt.toString),
          nxv.updatedAt.prefix  -> Json.fromString(updatedAt.toString)
        )
    }

  // TODO: That should be done using a Graph decoder instead. We will evaluate how to do it with Jena in 1.5 release
  private def decodeTypes(hc: HCursor): Decoder.Result[Set[AbsoluteIri]] =
    hc.getOrElse("@type")(Set.empty[AbsoluteIri])
      .orElse(hc.get[AbsoluteIri]("@type").map(Set(_)))
      .orElse(hc.getOrElse("@type")(Set.empty[String]).map(_.map(nxv.withSuffix(_).value)))
      .orElse(hc.get[String]("@type").map(tpe => Set(nxv.withSuffix(tpe).value)))

  implicit val resourceMetaDecoder: Decoder[ResourceMetadata] =
    Decoder.instance { hc =>
      for {
        id           <- hc.get[AbsoluteIri]("@id")
        types        <- decodeTypes(hc)
        uuid         <- hc.get[UUID](nxv.uuid.prefix)
        rev          <- hc.get[Long](nxv.rev.prefix)
        deprecated   <- hc.get[Boolean](nxv.deprecated.prefix)
        createdByIri <- hc.get[AbsoluteIri](nxv.createdBy.prefix)
        createdBy    <- Subject(createdByIri).toRight(DecodingFailure(s"Wrong createdBy '$createdByIri'", hc.history))
        updatedByIri <- hc.get[AbsoluteIri](nxv.updatedBy.prefix)
        updatedBy    <- Subject(createdByIri).toRight(DecodingFailure(s"Wrong updatedBy '$updatedByIri'", hc.history))
        createdAt    <- hc.get[Instant](nxv.createdAt.prefix)
        updatedAt    <- hc.get[Instant](nxv.updatedAt.prefix)
      } yield ResourceF.unit(id, uuid, rev, deprecated, types, createdAt, createdBy, updatedAt, updatedBy)
    }

  implicit def resourceDecoder[A](implicit aDecoder: Decoder[A]): Decoder[ResourceF[A]] =
    Decoder.instance { hc =>
      for {
        resourceUnit <- resourceMetaDecoder(hc)
        value        <- aDecoder(hc)
      } yield resourceUnit.copy(value = value)
    }

  implicit def resourceEncoder[A: Encoder](implicit http: HttpConfig): Encoder[ResourceF[A]] =
    Encoder.encodeJson.contramap { resource => resource.discard.asJson.deepMerge(resource.value.asJson) }

  implicit def uqrsEncoder[A: Encoder](implicit http: HttpConfig): Encoder[UnscoredQueryResults[ResourceF[A]]] = {
    Encoder.encodeJson.contramap {
      case UnscoredQueryResults(total, results, _) =>
        Json.obj(
          "@context"         -> Json.arr(adminCtxUri.asJson, resourceCtxUri.asJson, searchCtxUri.asJson),
          nxv.total.prefix   -> Json.fromLong(total),
          nxv.results.prefix -> Json.arr(results.map(_.source.asJson.removeKeys("@context")): _*)
        )
    }
  }

  implicit def clock[A]: Clock[ResourceF[A]] = { (_: Long, value: ResourceF[A]) => value.rev }

  implicit private class AbsoluteIriSyntax(private val iri: AbsoluteIri) extends AnyVal {
    def lastSegment: Option[String] =
      iri.path.head match {
        case segment: String => Some(segment)
        case _               => None
      }
  }
}
