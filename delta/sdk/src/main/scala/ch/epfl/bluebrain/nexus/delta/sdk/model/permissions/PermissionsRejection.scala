package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

/**
  * Enumeration of Permissions rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class PermissionsRejection(val reason: String) extends Product with Serializable

object PermissionsRejection {

  /**
    * Rejection returned when a subject intends to subtract an empty collection of permissions.
    */
  final case object CannotSubtractEmptyCollection
      extends PermissionsRejection("Cannot subtract an empty collection of permissions.")

  /**
    * Rejection returned when a subject intends to subtract from the minimum collection of permissions.
    */
  final case class CannotSubtractFromMinimumCollection(permissions: Set[Permission])
      extends PermissionsRejection(
        s"Cannot subtract permissions from the minimum collection of permissions: '${permissions.mkString("\"", ", ", "\"")}'"
      )

  /**
    * Rejection returned when a subject intends to subtract permissions when the current collection is empty.
    */
  final case object CannotSubtractFromEmptyCollection
      extends PermissionsRejection("Cannot subtract from an empty collection of permissions.")

  /**
    * Rejection returned when a subject intends to subtract permissions that are not in the current collection.
    */
  final case class CannotSubtractUndefinedPermissions(permissions: Set[Permission])
      extends PermissionsRejection(
        s"Cannot subtract permissions not present in the collection: '${permissions.mkString("\"", ", ", "\"")}'."
      )

  /**
    * Rejection returned when a subject intends to append an empty collection of permissions.
    */
  final case object CannotAppendEmptyCollection
      extends PermissionsRejection("Cannot append an empty collection of permissions.")

  /**
    * Rejection returned when a subject intends to replace the current collection of permission with an empty set.
    */
  final case object CannotReplaceWithEmptyCollection
      extends PermissionsRejection("Cannot replace the permissions with an empty collection.")

  /**
    * Rejection returned when a subject intends to delete (empty) the current collection of permissions, but the
    * collection is already empty.
    */
  final case object CannotDeleteMinimumCollection
      extends PermissionsRejection("Cannot delete the minimum collection of permissions.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current collection of permissions, but
    * either provided an incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends PermissionsRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', permissions may have been updated since last seen."
      )

  /**
    * Rejection returned when a subject intends to retrieve the collection of permissions at a specific revision, but
    * the provided revision does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends PermissionsRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

}
