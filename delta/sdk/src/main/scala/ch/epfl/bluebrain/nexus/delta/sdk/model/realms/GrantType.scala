package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

/**
  * OAuth2 grant type enumeration.
  */
sealed trait GrantType extends Product with Serializable

object GrantType {

  /**
    * The Authorization Code grant type is used by confidential and public clients to exchange an authorization code for
    * an access token.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.1
    */
  final case object AuthorizationCode extends GrantType

  /**
    * The Implicit grant type is a simplified flow that can be used by public clients, where the access token is
    * returned immediately without an extra authorization code exchange step.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.2
    */
  final case object Implicit extends GrantType

  /**
    * The Password grant type is used by first-party clients to exchange a user's credentials for an access token.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.3
    */
  final case object Password extends GrantType

  /**
    * The Client Credentials grant type is used by clients to obtain an access token outside of the context of a user.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.4
    */
  final case object ClientCredentials extends GrantType

  /**
    * The Device Code grant type is used by browserless or input-constrained devices in the device flow to exchange a
    * previously obtained device code for an access token.
    *
    * @see https://tools.ietf.org/html/draft-ietf-oauth-device-flow-07#section-3.4
    */
  final case object DeviceCode extends GrantType

  /**
    * The Refresh Token grant type is used by clients to exchange a refresh token for an access token when the access
    * token has expired.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.5
    */
  final case object RefreshToken extends GrantType
}
