package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import monix.bio.{IO, UIO}

/**
  * Operations pertaining to managing permissions.
  */
trait Permissions {

  /**
    * @return the permissions singleton persistence id
    */
  def persistenceId: String

  /**
    * @return the minimum set of permissions
    */
  def minimum: Set[Permission]

  /**
    * @return the current permissions as a resource
    */
  def fetch: UIO[PermissionsResource]

  /**
    * @param rev the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  def fetchAt(rev: Long): IO[RevisionNotFound, PermissionsResource]

  /**
    * @return the current permissions collection without checking permissions
    */
  def fetchPermissionSet: UIO[Set[Permission]] =
    fetch.map(_.value)

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions the permissions to set
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to append
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to subtract
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev    the last known revision of the resource
    * @param caller a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]
}

object Permissions {

  private[delta] def next(
      minimum: Set[Permission]
  )(state: PermissionsState, event: PermissionsEvent): PermissionsState = {

    implicit class WithPermissionsState(s: PermissionsState) {
      def withPermissions(permissions: Set[Permission], instant: Instant, subject: Subject): PermissionsState =
        s match {
          case Initial    => Current(s.rev + 1L, permissions, instant, subject, instant, subject)
          case s: Current => Current(s.rev + 1L, permissions, s.createdAt, s.createdBy, instant, subject)
        }

      def permissions: Set[Permission] =
        s match {
          case Initial    => minimum
          case c: Current => c.permissions
        }
    }

    def appended(e: PermissionsAppended) =
      state.withPermissions(state.permissions ++ e.permissions ++ minimum, e.instant, e.subject)

    def replaced(e: PermissionsReplaced) =
      state.withPermissions(minimum ++ e.permissions, e.instant, e.subject)

    def subtracted(e: PermissionsSubtracted) =
      state.withPermissions(state.permissions -- e.permissions ++ minimum, e.instant, e.subject)

    def deleted(e: PermissionsDeleted) =
      state.withPermissions(minimum, e.instant, e.subject)

    event match {
      case e: PermissionsAppended   => appended(e)
      case e: PermissionsReplaced   => replaced(e)
      case e: PermissionsSubtracted => subtracted(e)
      case e: PermissionsDeleted    => deleted(e)
    }
  }

  private[delta] def evaluate(minimum: Set[Permission])(state: PermissionsState, cmd: PermissionsCommand)(implicit
      clock: Clock[UIO] = IO.timer.clock
  ): IO[PermissionsRejection, PermissionsEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils._

    def replace(c: ReplacePermissions) =
      if (c.rev != state.rev) IO.raiseError(IncorrectRev(c.rev, state.rev))
      else if (c.permissions.isEmpty) IO.raiseError(CannotReplaceWithEmptyCollection)
      else if ((c.permissions -- minimum).isEmpty) IO.raiseError(CannotReplaceWithEmptyCollection)
      else instant.map(PermissionsReplaced(c.rev + 1, c.permissions, _, c.subject))

    def append(c: AppendPermissions) =
      state match {
        case _ if state.rev != c.rev    => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => IO.raiseError(CannotAppendEmptyCollection)
        case Initial                    =>
          val appended = c.permissions -- minimum
          if (appended.isEmpty) IO.raiseError(CannotAppendEmptyCollection)
          else instant.map(PermissionsAppended(1L, c.permissions, _, c.subject))
        case s: Current                 =>
          val appended = c.permissions -- s.permissions -- minimum
          if (appended.isEmpty) IO.raiseError(CannotAppendEmptyCollection)
          else instant.map(PermissionsAppended(c.rev + 1, appended, _, c.subject))
      }

    def subtract(c: SubtractPermissions) =
      state match {
        case _ if state.rev != c.rev    => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => IO.raiseError(CannotSubtractEmptyCollection)
        case Initial                    => IO.raiseError(CannotSubtractFromMinimumCollection(minimum))
        case s: Current                 =>
          val intendedDelta = c.permissions -- s.permissions
          val delta         = c.permissions & s.permissions
          val subtracted    = delta -- minimum
          if (intendedDelta.nonEmpty) IO.raiseError(CannotSubtractUndefinedPermissions(intendedDelta))
          else if (subtracted.isEmpty) IO.raiseError(CannotSubtractFromMinimumCollection(minimum))
          else instant.map(PermissionsSubtracted(c.rev + 1, subtracted, _, c.subject))
      }

    def delete(c: DeletePermissions) =
      state match {
        case _ if state.rev != c.rev                => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case Initial                                => IO.raiseError(CannotDeleteMinimumCollection)
        case s: Current if s.permissions == minimum => IO.raiseError(CannotDeleteMinimumCollection)
        case _: Current                             => instant.map(PermissionsDeleted(c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplacePermissions  => replace(c)
      case c: AppendPermissions   => append(c)
      case c: SubtractPermissions => subtract(c)
      case c: DeletePermissions   => delete(c)
    }
  }
}
