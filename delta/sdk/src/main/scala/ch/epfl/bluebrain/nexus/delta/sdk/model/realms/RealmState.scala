package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name, ResourceF, ResourceRef}
import io.circe.Json
import org.apache.jena.iri.IRI

/**
  * Enumeration of Permissions states.
  */
sealed trait RealmState extends Product with Serializable {

  /**
    * @return the current deprecation status
    */
  def deprecated: Boolean

  /**
    * @return the schema reference that realm conforms to
    */
  final def schema: ResourceRef = Latest(schemas.realms)

  /**
    * @return the collection of known types of realm resources
    */
  final def types: Set[IRI] = Set(nxv.Realm)

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[RealmResource]

}

object RealmState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial state for realms.
    */
  final case object Initial extends RealmState {

    /**
      * @return the current deprecation status
      */
    override val deprecated: Boolean = false

    /**
      * Converts the state into a resource representation.
      */
    override val toResource: Option[RealmResource] = None
  }

  /**
    * A realm active state; a realm in an active state can be used to authorize a subject through a token.
    *
    * @param label                 the realm label
    * @param rev                   the current state revision
    * @param deprecated            the current state deprecation status
    * @param openIdConfig          the openid configuration address
    * @param issuer                the issuer identifier
    * @param keys                  the collection of JWK keys in json format
    * @param grantTypes            the supported oauth2 grant types
    * @param logo                  an optional logo address
    * @param authorizationEndpoint the authorization endpoint
    * @param tokenEndpoint         the token endpoint
    * @param userInfoEndpoint      the user info endpoint
    * @param revocationEndpoint    an optional revocation endpoint
    * @param endSessionEndpoint    an optional end session endpoint
    * @param createdAt             the instant when the resource was created
    * @param createdBy             the subject that created the resource
    * @param updatedAt             the instant when the resource was last updated
    * @param updatedBy             the subject that last updated the resource
    */
  final case class Current(
      label: Label,
      rev: Long,
      deprecated: Boolean,
      name: Name,
      openIdConfig: Uri,
      issuer: String,
      keys: Set[Json],
      grantTypes: Set[GrantType],
      logo: Option[Uri],
      authorizationEndpoint: Uri,
      tokenEndpoint: Uri,
      userInfoEndpoint: Uri,
      revocationEndpoint: Option[Uri],
      endSessionEndpoint: Option[Uri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends RealmState {

    /**
      * @return the realm information
      */
    def realm: Realm =
      Realm(
        label = label,
        name = name,
        openIdConfig = openIdConfig,
        issuer = issuer,
        grantTypes = grantTypes,
        logo = logo,
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = tokenEndpoint,
        userInfoEndpoint = userInfoEndpoint,
        revocationEndpoint = revocationEndpoint,
        endSessionEndpoint = endSessionEndpoint,
        keys = keys
      )

    /**
      * @return a resource representation for the realm
      */
    override def toResource: Option[RealmResource] =
      Some(
        ResourceF(
          id = label,
          rev = rev,
          types = types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = realm
        )
      )
  }

}
