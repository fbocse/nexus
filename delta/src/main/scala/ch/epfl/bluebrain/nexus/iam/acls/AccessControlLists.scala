package ch.epfl.bluebrain.nexus.iam.acls

import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.iam.types.{Identity, Permission}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectLabel
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.Contexts._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.collection.immutable.ListMap

/**
  * Type definition representing a mapping of Paths to AccessControlList for a specific resource.
  *
  * @param value a map of path and AccessControlList
  */
final case class AccessControlLists(value: Map[Path, Resource]) {

  /**
    * Adds the provided ''acls'' to the current ''value'' and returns a new [[AccessControlLists]] with the added ACLs.
    *
    * @param acls the acls to be added
    */
  def ++(acls: AccessControlLists): AccessControlLists = {
    val toAddKeys   = acls.value.keySet -- value.keySet
    val toMergeKeys = acls.value.keySet -- toAddKeys
    val added       = value ++ acls.value.view.filterKeys(toAddKeys.contains)
    val merged      = value.view.filterKeys(toMergeKeys.contains).map {
      case (p, currResourceAcl) => p -> currResourceAcl.map(_ ++ acls.value(p).value)
    }
    AccessControlLists(added ++ merged)
  }

  /**
    * Adds a key pair of Path and [[Resource]] to the current ''value'' and returns a new [[AccessControlLists]] with the added acl.
    *
    * @param entry the key pair of Path and ACL to be added
    */
  def +(entry: (Path, Resource)): AccessControlLists = {
    val (path, aclResource) = entry
    val toAdd               = aclResource.map(acl => value.get(path).map(_.value ++ acl).getOrElse(acl))
    AccessControlLists(value + (path -> toAdd))
  }

  /**
    * @return new [[AccessControlLists]] with the same elements as the current one but sorted by [[Path]] (alphabetically)
    */
  def sorted: AccessControlLists                            =
    AccessControlLists(ListMap(value.toSeq.sortBy { case (path, _) => path.asString }: _*))

  /**
    * Generates a new [[AccessControlLists]] only containing the provided ''identities''.
    *
    * @param identities the identities to be filtered
    */
  def filter(identities: Set[Identity]): AccessControlLists =
    value.foldLeft(AccessControlLists.empty) {
      case (acc, (p, aclResource)) => acc + (p -> aclResource.map(_.filter(identities)))
    }

  /**
    * @return a new [[AccessControlLists]] containing the ACLs with non empty [[AccessControlList]]
    */
  def removeEmpty: AccessControlLists =
    AccessControlLists(value.foldLeft(Map.empty[Path, Resource]) {
      case (acc, (_, acl)) if acl.value.value.isEmpty => acc
      case (acc, (p, acl))                            =>
        val filteredAcl = acl.value.value.filterNot { case (_, v) => v.isEmpty }
        if (filteredAcl.isEmpty) acc
        else acc + (p -> acl.map(_ => AccessControlList(filteredAcl)))

    })

  /**
    * Checks if on the list of ACLs there are some which contains any of the provided ''identities'', ''perm'' in
    * the root path, the organization path or the project path.
    *
   * @param identities the list of identities to filter from the ''acls''
    * @param label      the organization and project label information to be used to generate the paths to filter
    * @param perm       the permission to filter
    * @return true if the conditions are met, false otherwise
    */
  def exists(identities: Set[Identity], label: ProjectLabel, perm: Permission): Boolean =
    filter(identities).value.exists {
      case (path, v) =>
        (path == / || path == Segment(label.organization, /) || path == label.organization / label.value) &&
          v.value.permissions.contains(perm)
    }

  /**
    * Checks if on the list of ACLs there are some which contains any of the provided ''identities'', ''perm'' in
    * the root path or the organization path.
    *
   * @param identities the list of identities to filter from the ''acls''
    * @param label      the organization label information to be used to generate the paths to filter
    * @param perm       the permission to filter
    * @return true if the conditions are met, false otherwise
    */
  def exists(identities: Set[Identity], label: String, perm: Permission): Boolean =
    filter(identities).value.exists {
      case (path, v) => (path == / || path == Segment(label, /)) && v.value.permissions.contains(perm)
    }

  /**
    * Checks if on the list of ACLs there are some which contain any of the provided ''identities'', ''perm'' in
    * the root path.
    *
   * @param identities the list of identities to filter from the ''acls''
    * @param perm       the permission to filter
    * @return true if the conditions are met, false otherwise
    */
  def existsOnRoot(identities: Set[Identity], perm: Permission): Boolean =
    filter(identities).value.exists {
      case (path, v) =>
        path == / && v.value.permissions.contains(perm)
    }
}

object AccessControlLists {

  /**
    * An empty [[AccessControlLists]].
    */
  val empty: AccessControlLists = AccessControlLists(Map.empty[Path, Resource])

  /**
    * Convenience factory method to build an ACLs from var args of ''Path'' to ''AccessControlList'' tuples.
    */
  final def apply(tuple: (Path, Resource)*): AccessControlLists = AccessControlLists(tuple.toMap)

  implicit def aclsEncoder(implicit http: HttpConfig): Encoder[AccessControlLists] =
    Encoder.encodeJson.contramap {
      case AccessControlLists(value) =>
        val arr = value.map {
          case (path, acl) =>
            Json.obj("_path" -> Json.fromString(path.asString)) deepMerge acl.asJson.removeKeys("@context")
        }
        Json
          .obj(nxv.total.prefix -> Json.fromInt(arr.size), nxv.results.prefix -> Json.arr(arr.toSeq: _*))
          .addContext(resourceCtxUri)
          .addContext(iamCtxUri)
          .addContext(searchCtxUri)
    }
}
