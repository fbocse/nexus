package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of Organization collection command types.
  */
sealed trait OrganizationCommand extends Product with Serializable {

  /**
    * @return the organization Label
    */
  def label: Label

  /**
    * @return UUID of the organization
    */
  def uuid: UUID

  /**
    * @return the subject which created this command
    */
  def subject: Subject
}

object OrganizationCommand {

  /**
    * An intent to create an organization.
    * @param label        the organization label
    * @param uuid         the uuid of the organization
    * @param description  an optional description of the organization
    * @param subject      the subject which created this command.
    */
  final case class CreateOrganization(
      label: Label,
      uuid: UUID,
      description: Option[String],
      subject: Subject
  ) extends OrganizationCommand

  /**
    * An intent to create an organization.
    *
    * @param label        the organization label
    * @param uuid         the UUID of the organization
    * @param rev          the revision to update
    * @param description  an optional description of the organization
    * @param subject      the subject which created this command.
    */
  final case class UpdateOrganization(
      label: Label,
      uuid: UUID,
      rev: Long,
      description: Option[String],
      subject: Subject
  ) extends OrganizationCommand

  /**
    * An intent to deprecate an organization.
    *
    * @param label        the organization label
    * @param uuid         the UUID of the organization
    * @param rev          the revision to deprecate
    * @param subject      the subject which created this command.
    */
  final case class DeprecateOrganization(
      label: Label,
      uuid: UUID,
      rev: Long,
      subject: Subject
  ) extends OrganizationCommand
}
