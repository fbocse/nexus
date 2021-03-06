package ch.epfl.bluebrain.nexus.rdf

import cats.syntax.show._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.Curie.Prefix
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, RelativeIri}

/**
  * A Compact URI as defined by W3C in ''CURIE Syntax 1.0''.
  * A curie is form by a ''prefix'', a '':'' and a ''reference''.
  * Example: xsd:integer
  *
  * @param prefix    the curie prefix
  * @param reference the curie reference
  */
final case class Curie(prefix: Prefix, reference: RelativeIri) {

  /**
    * Converts the curie to an iri using the provided ''namespace'' and the curie ''reference''.
    *
    * @param namespace the namespace to produce the resulting iri
    * @return an [[AbsoluteIri]] when successfully joined the ''namespace'' and ''reference'' or
    *         an string with the error message otherwise
    */
  def toIri(namespace: AbsoluteIri): Either[String, AbsoluteIri] =
    Iri.absolute(namespace.asString + reference.asString)

  /**
    * Converts the curie to an iri using the provided ''prefixMappings'' to resolve the value of the ''prefix''.
    *
    * @param prefixMappings the mappings to attempt to apply to the ''prefix'' value of the curie
    * @return an [[AbsoluteIri]] when successfully joined the resolution of the prefix with the ''reference'' or
    *         an string with the error message otherwise
    */
  def toIriUnsafePrefix(prefixMappings: Map[String, AbsoluteIri]): Either[String, AbsoluteIri] =
    prefixMappings
      .get(prefix.value)
      .toRight(s"Could not find a namespace definition for '${prefix.value}'")
      .flatMap(toIri)

  /**
    * Converts the curie to an iri using the provided ''prefixMappings'' to resolve the value of the ''prefix''.
    *
    * @param prefixMappings the mappings to attempt to apply to the ''prefix'' value of the curie
    * @return an [[AbsoluteIri]] when successfully joined the resolution of the prefix with the ''reference'' or
    *         an string with the error message otherwise
    */
  def toIri(prefixMappings: Map[Prefix, AbsoluteIri]): Either[String, AbsoluteIri] =
    prefixMappings
      .get(prefix)
      .toRight(s"Could not find a namespace definition for '${prefix.value}'")
      .flatMap(toIri)
}

object Curie {

  /**
    * Attempt to construct a new [[Curie]] from the argument validating the structure and the character encodings as per
    * ''CURIE Syntax 1.0''.
    *
    * @param string the string to parse as a Curie.
    * @return Right(Curie) if the string conforms to specification, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Curie] =
    new IriParser(string).parseCurie

  /**
    * The Compact URI prefix as defined by W3C in ''CURIE Syntax 1.0''.
    *
    * @param value the prefix value
    */
  final case class Prefix private[rdf] (value: String)

  object Prefix {

    /**
      * Attempt to construct a new [[Prefix]] from the argument validating the structure and the character encodings as per
      * ''CURIE Syntax 1.0''.
      *
      * @param string the string to parse as a Prefix.
      * @return Right(prefix) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Prefix] =
      new IriParser(string).parseNcName

    implicit final val prefixShow: Show[Prefix] = Show.show(_.value)
    implicit final val prefixEq: Eq[Prefix]     = Eq.fromUniversalEquals
  }

  implicit final def curieShow(implicit p: Show[Prefix], r: Show[RelativeIri]): Show[Curie] =
    Show.show { case Curie(prefix, reference) => prefix.show + ":" + reference.show }

  implicit final val curieEq: Eq[Curie]                                                     = Eq.fromUniversalEquals
}
